import json
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

from versionserver.models import (
    PersistedArtifact,
    PersistedGrant,
    PersistedState,
    PersistedUser,
    Role,
    VersionSummary,
)
from versionserver.passwords import sha256_hex
from versionserver.timefmt import format_instant, parse_instant


def version_summaries(artifacts: list[PersistedArtifact]) -> list[VersionSummary]:
    grouped: dict[str, list[PersistedArtifact]] = {}
    for artifact in artifacts:
        grouped.setdefault(artifact.version, []).append(artifact)
    if not grouped:
        return []

    def sort_key(item: tuple[str, list[PersistedArtifact]]) -> tuple[datetime, str]:
        name, rows = item
        newest = max(parse_instant(artifact.uploaded_at) for artifact in rows)
        return (newest, name)

    latest_name = max(grouped.items(), key=sort_key)[0]
    return [
        VersionSummary(name=name, latest=name == latest_name)
        for name in sorted(grouped)
    ]


_DEFAULT_USERS = (
    PersistedUser(id="admin", passwordHash=sha256_hex("admin-pass"), role=Role.Admin),
    PersistedUser(id="alice", passwordHash=sha256_hex("alice-pass"), role=Role.Client),
    PersistedUser(id="bob", passwordHash=sha256_hex("bob-pass"), role=Role.Client),
)


class JsonDatabase:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.RLock()
        self._state = self._load_or_seed()

    def authenticate(self, user_id: str, password: str) -> PersistedUser | None:
        digest = sha256_hex(password)
        with self._lock:
            for user in self._state.users:
                if user.id == user_id and user.password_hash == digest:
                    return user
        return None

    def user(self, user_id: str) -> PersistedUser | None:
        with self._lock:
            for existing in self._state.users:
                if existing.id == user_id:
                    return existing
        return None

    def users(self) -> list[PersistedUser]:
        with self._lock:
            return list(self._state.users)

    def remove_user(self, user_id: str) -> PersistedUser | None:
        with self._lock:
            existing = self.user(user_id)
            if existing is None:
                return None
            self._state.users = [user for user in self._state.users if user.id != user_id]
            self._state.grants = [grant for grant in self._state.grants if grant.user_id != user_id]
            self._persist()
            return existing

    def add_user(self, user_id: str, password: str, role: Role) -> PersistedUser | None:
        if user_id.strip() == "" or password == "":
            return None
        with self._lock:
            if any(existing.id == user_id for existing in self._state.users):
                return None
            created = PersistedUser(id=user_id, passwordHash=sha256_hex(password), role=role)
            self._state.users.append(created)
            self._persist()
            return created

    def change_password(self, user_id: str, current_password: str, new_password: str) -> bool | None:
        if new_password == "":
            return None
        with self._lock:
            existing = self.authenticate(user_id, current_password)
            if existing is None:
                return False
            updated: list[PersistedUser] = []
            for user in self._state.users:
                if user.id == existing.id:
                    updated.append(
                        PersistedUser(
                            id=user.id,
                            passwordHash=sha256_hex(new_password),
                            role=user.role,
                        )
                    )
                else:
                    updated.append(user)
            self._state.users = updated
            self._persist()
            return True

    def add_grant(self, user_id: str, project: str) -> bool:
        with self._lock:
            if self.user(user_id) is None:
                return False
            if any(grant.user_id == user_id and grant.project == project for grant in self._state.grants):
                return True
            self._state.grants.append(PersistedGrant(userId=user_id, project=project))
            self._persist()
            return True

    def projects_for(self, user: PersistedUser) -> list[str]:
        with self._lock:
            if user.role == Role.Admin:
                return self._all_projects()
            seen: list[str] = []
            for grant in self._state.grants:
                if grant.user_id == user.id and grant.project not in seen:
                    seen.append(grant.project)
            return seen

    def is_authorized(self, user: PersistedUser, project: str) -> bool:
        if user.role == Role.Admin:
            return True
        with self._lock:
            return any(grant.user_id == user.id and grant.project == project for grant in self._state.grants)

    def add_artifact(
        self,
        project: str,
        version: str,
        file_name: str,
        uploaded_at: datetime | None = None,
    ) -> None:
        stamp = format_instant(uploaded_at or datetime.now(timezone.utc))
        with self._lock:
            existing = self.find_artifact(project, version, file_name)
            artifact = PersistedArtifact(
                project=project,
                version=version,
                fileName=file_name,
                uploadedAt=stamp,
            )
            if existing is None:
                self._state.artifacts.append(artifact)
            else:
                self._state.artifacts = [
                    artifact if item.project == project and item.version == version and item.file_name == file_name else item
                    for item in self._state.artifacts
                ]
            self._persist()

    def versions_for(self, project: str) -> list[VersionSummary]:
        with self._lock:
            rows = [artifact for artifact in self._state.artifacts if artifact.project == project]
        return version_summaries(rows)

    def artifacts(self, project: str, version: str) -> list[PersistedArtifact]:
        with self._lock:
            return [
                artifact
                for artifact in self._state.artifacts
                if artifact.project == project and artifact.version == version
            ]

    def find_artifact(self, project: str, version: str, file_name: str) -> PersistedArtifact | None:
        with self._lock:
            for artifact in self._state.artifacts:
                if artifact.project == project and artifact.version == version and artifact.file_name == file_name:
                    return artifact
        return None

    def remove_artifact(self, project: str, version: str, file_name: str) -> PersistedArtifact | None:
        with self._lock:
            existing = self.find_artifact(project, version, file_name)
            if existing is None:
                return None
            self._state.artifacts = [
                artifact
                for artifact in self._state.artifacts
                if not (
                    artifact.project == project
                    and artifact.version == version
                    and artifact.file_name == file_name
                )
            ]
            self._persist()
            return existing

    def remove_version(self, project: str, version: str) -> None:
        with self._lock:
            remaining = [
                artifact
                for artifact in self._state.artifacts
                if not (artifact.project == project and artifact.version == version)
            ]
            if len(remaining) == len(self._state.artifacts):
                return
            self._state.artifacts = remaining
            self._persist()

    def _all_projects(self) -> list[str]:
        seen: list[str] = []
        for grant in self._state.grants:
            if grant.project not in seen:
                seen.append(grant.project)
        for artifact in self._state.artifacts:
            if artifact.project not in seen:
                seen.append(artifact.project)
        return seen

    def _load_or_seed(self) -> PersistedState:
        if not self._path.exists():
            return self._write_defaults()
        try:
            text = self._path.read_text(encoding="utf-8")
        except OSError:
            return self._write_defaults()
        if text.strip() == "":
            return self._write_defaults()
        loaded = PersistedState.model_validate(json.loads(text))
        if not loaded.users:
            return self._write_defaults()
        if loaded.artifacts and "uploadedAt" not in text:
            base = datetime.now(timezone.utc)
            stamped = [
                artifact.model_copy(update={"uploaded_at": format_instant(base + timedelta(seconds=index))})
                for index, artifact in enumerate(loaded.artifacts)
            ]
            loaded.artifacts = stamped
            self._state = loaded
            self._persist()
            return loaded
        return loaded

    def _write_defaults(self) -> PersistedState:
        seeded = PersistedState(
            users=list(_DEFAULT_USERS),
            grants=[],
            artifacts=[],
        )
        self._state = seeded
        self._persist()
        return seeded

    def _persist(self) -> None:
        payload = self._state.model_dump(by_alias=True, mode="json")
        text = json.dumps(payload, indent=4) + "\n"
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(self._path.suffix + ".tmp")
        temporary.write_text(text, encoding="utf-8")
        temporary.replace(self._path)

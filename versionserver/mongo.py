import logging
from datetime import datetime, timezone
from urllib.parse import urlparse

from pymongo import MongoClient
from pymongo.errors import BulkWriteError, DuplicateKeyError

from versionserver.database import _DEFAULT_USERS, version_summaries
from versionserver.models import PersistedArtifact, PersistedUser, Role, VersionSummary
from versionserver.passwords import sha256_hex
from versionserver.timefmt import format_instant

logger = logging.getLogger("versionserver.mongo")

_DEFAULT_DATABASE = "version-server"


class MongoDatabase:
    def __init__(self, uri: str) -> None:
        self._client = MongoClient(uri, serverSelectionTimeoutMS=10000, tz_aware=True)
        self._client.admin.command("ping")
        self._db = self._client[database_name(uri)]
        self._users = self._db["users"]
        self._grants = self._db["grants"]
        self._artifacts = self._db["artifacts"]
        self._grants.create_index([("userId", 1), ("project", 1)], unique=True)
        self._artifacts.create_index(
            [("project", 1), ("version", 1), ("fileName", 1)],
            unique=True,
        )
        self._seed_if_empty()
        logger.info("catalog database=%s", self._db.name)

    def authenticate(self, user_id: str, password: str) -> PersistedUser | None:
        document = self._users.find_one({"_id": user_id, "passwordHash": sha256_hex(password)})
        if document is None:
            return None
        return _user(document)

    def user(self, user_id: str) -> PersistedUser | None:
        document = self._users.find_one({"_id": user_id})
        if document is None:
            return None
        return _user(document)

    def users(self) -> list[PersistedUser]:
        return [_user(document) for document in self._users.find().sort("_id", 1)]

    def remove_user(self, user_id: str) -> PersistedUser | None:
        existing = self.user(user_id)
        if existing is None:
            return None
        self._users.delete_one({"_id": user_id})
        self._grants.delete_many({"userId": user_id})
        return existing

    def add_user(self, user_id: str, password: str, role: Role) -> PersistedUser | None:
        if user_id.strip() == "" or password == "":
            return None
        document = {"_id": user_id, "passwordHash": sha256_hex(password), "role": role.value}
        try:
            self._users.insert_one(document)
        except DuplicateKeyError:
            return None
        return _user(document)

    def change_password(self, user_id: str, current_password: str, new_password: str) -> bool | None:
        if new_password == "":
            return None
        updated = self._users.update_one(
            {"_id": user_id, "passwordHash": sha256_hex(current_password)},
            {"$set": {"passwordHash": sha256_hex(new_password)}},
        )
        return updated.matched_count == 1

    def add_grant(self, user_id: str, project: str) -> bool:
        if self.user(user_id) is None:
            return False
        try:
            self._grants.insert_one({"userId": user_id, "project": project})
        except DuplicateKeyError:
            return True
        return True

    def projects_for(self, user: PersistedUser) -> list[str]:
        if user.role == Role.Admin:
            return self._all_projects()
        seen: list[str] = []
        for grant in self._grants.find({"userId": user.id}).sort("_id", 1):
            if grant["project"] not in seen:
                seen.append(grant["project"])
        return seen

    def is_authorized(self, user: PersistedUser, project: str) -> bool:
        if user.role == Role.Admin:
            return True
        return self._grants.find_one({"userId": user.id, "project": project}) is not None

    def add_artifact(
        self,
        project: str,
        version: str,
        file_name: str,
        uploaded_at=None,
    ) -> None:
        stamp = format_instant(uploaded_at or datetime.now(timezone.utc))
        self._artifacts.update_one(
            {"project": project, "version": version, "fileName": file_name},
            {
                "$set": {
                    "project": project,
                    "version": version,
                    "fileName": file_name,
                    "uploadedAt": stamp,
                }
            },
            upsert=True,
        )

    def versions_for(self, project: str) -> list[VersionSummary]:
        rows = [_artifact(document) for document in self._artifacts.find({"project": project})]
        return version_summaries(rows)

    def artifacts(self, project: str, version: str) -> list[PersistedArtifact]:
        documents = self._artifacts.find({"project": project, "version": version}).sort("_id", 1)
        return [_artifact(document) for document in documents]

    def find_artifact(self, project: str, version: str, file_name: str) -> PersistedArtifact | None:
        document = self._artifacts.find_one(
            {"project": project, "version": version, "fileName": file_name}
        )
        if document is None:
            return None
        return _artifact(document)

    def remove_artifact(self, project: str, version: str, file_name: str) -> PersistedArtifact | None:
        existing = self.find_artifact(project, version, file_name)
        if existing is None:
            return None
        self._artifacts.delete_one({"project": project, "version": version, "fileName": file_name})
        return existing

    def remove_version(self, project: str, version: str) -> None:
        self._artifacts.delete_many({"project": project, "version": version})

    def _all_projects(self) -> list[str]:
        seen: list[str] = []
        for grant in self._grants.find().sort("_id", 1):
            if grant["project"] not in seen:
                seen.append(grant["project"])
        for artifact in self._artifacts.find().sort("_id", 1):
            if artifact["project"] not in seen:
                seen.append(artifact["project"])
        return seen

    def _seed_if_empty(self) -> None:
        if self._users.count_documents({}, limit=1) != 0:
            return
        documents = [
            {"_id": user.id, "passwordHash": user.password_hash, "role": user.role.value}
            for user in _DEFAULT_USERS
        ]
        try:
            self._users.insert_many(documents, ordered=False)
        except BulkWriteError:
            return


def database_name(uri: str) -> str:
    path = urlparse(uri).path.lstrip("/")
    if path == "":
        return _DEFAULT_DATABASE
    return path.split("/", 1)[0]


def _user(document: dict) -> PersistedUser:
    return PersistedUser(
        id=str(document["_id"]),
        passwordHash=document["passwordHash"],
        role=Role(document["role"]),
    )


def _artifact(document: dict) -> PersistedArtifact:
    return PersistedArtifact(
        project=document["project"],
        version=document["version"],
        fileName=document["fileName"],
        uploadedAt=document["uploadedAt"],
    )


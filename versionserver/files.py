from __future__ import annotations

import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO, Protocol

from versionserver.timefmt import format_instant


@dataclass(frozen=True)
class StoredFileRef:
    project: str
    version: str
    file_name: str
    last_modified: datetime | None = None


class FileStore(Protocol):
    def store(self, project: str, version: str, file_name: str, source: BinaryIO) -> bool: ...

    def resolve(self, project: str, version: str, file_name: str) -> Path | None: ...

    def read(self, project: str, version: str, file_name: str) -> bytes | None: ...

    def list(self, project: str, version: str) -> list[str]: ...

    def list_all(self) -> list[StoredFileRef]: ...

    def delete(self, project: str, version: str, file_name: str) -> None: ...

    def delete_version(self, project: str, version: str) -> None: ...


class FilesystemFileStore:
    def __init__(self, root: Path) -> None:
        self._root = root

    def store(self, project: str, version: str, file_name: str, source: BinaryIO) -> bool:
        path = self.resolve(project, version, file_name)
        if path is None:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("wb") as output:
            shutil.copyfileobj(source, output)
        return True

    def resolve(self, project: str, version: str, file_name: str) -> Path | None:
        directory = self._directory_for(project, version)
        if directory is None:
            return None
        relative = _safe_relative_path(file_name)
        if relative is None:
            return None
        resolved = (directory / relative).resolve()
        try:
            resolved.relative_to(directory.resolve())
        except ValueError:
            return None
        return resolved

    def read(self, project: str, version: str, file_name: str) -> bytes | None:
        path = self.resolve(project, version, file_name)
        if path is None or not path.is_file():
            return None
        return path.read_bytes()

    def list(self, project: str, version: str) -> list[str]:
        directory = self._directory_for(project, version)
        if directory is None or not directory.is_dir():
            return []
        names: list[str] = []
        for path in directory.rglob("*"):
            if path.is_file():
                names.append(path.relative_to(directory).as_posix())
        return names

    def list_all(self) -> list[StoredFileRef]:
        if not self._root.is_dir():
            return []
        found: list[StoredFileRef] = []
        for project_dir in self._root.iterdir():
            if not project_dir.is_dir():
                continue
            project = _safe_segment(project_dir.name)
            if project is None:
                continue
            for version_dir in project_dir.iterdir():
                if not version_dir.is_dir():
                    continue
                version = _safe_segment(version_dir.name)
                if version is None:
                    continue
                for file_name in self.list(project, version):
                    path = self.resolve(project, version, file_name)
                    modified = None
                    if path is not None:
                        try:
                            modified = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
                        except OSError:
                            modified = None
                    found.append(
                        StoredFileRef(
                            project=project,
                            version=version,
                            file_name=file_name,
                            last_modified=modified,
                        )
                    )
        return found

    def delete(self, project: str, version: str, file_name: str) -> None:
        path = self.resolve(project, version, file_name)
        if path is None:
            return
        path.unlink(missing_ok=True)

    def delete_version(self, project: str, version: str) -> None:
        directory = self._directory_for(project, version)
        if directory is None or not directory.exists():
            return
        shutil.rmtree(directory)

    def _directory_for(self, project: str, version: str) -> Path | None:
        safe_project = _safe_segment(project)
        safe_version = _safe_segment(version)
        if safe_project is None or safe_version is None:
            return None
        return (self._root / safe_project / safe_version).resolve()


def format_modified(value: datetime | None) -> str:
    if value is None:
        return format_instant(datetime.fromtimestamp(0, timezone.utc))
    return format_instant(value)


def _safe_segment(value: str) -> str | None:
    if value == "" or value in {".", ".."}:
        return None
    if "/" in value or "\\" in value or "\x00" in value:
        return None
    return value


def _safe_relative_path(value: str) -> str | None:
    if value.strip() == "":
        return None
    normalized = value.replace("\\", "/").strip("/")
    if normalized == "":
        return None
    segments = normalized.split("/")
    if any(segment == "" or segment in {".", ".."} for segment in segments):
        return None
    return "/".join(segments)

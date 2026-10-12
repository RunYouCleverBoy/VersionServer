import logging
from datetime import datetime, timezone

from versionserver.database import JsonDatabase
from versionserver.files import FileStore

logger = logging.getLogger("versionserver.sync")


def sync_artifacts_from_disk(database: JsonDatabase, file_store: FileStore) -> int:
    """Index files present on disk that are missing from the database.

    Existing artifact rows, including uploadedAt, are left unchanged.
    """
    added = 0
    for stored in file_store.list_all():
        if database.find_artifact(stored.project, stored.version, stored.file_name) is not None:
            continue
        database.add_artifact(
            project=stored.project,
            version=stored.version,
            file_name=stored.file_name,
            uploaded_at=stored.last_modified or datetime.fromtimestamp(0, timezone.utc),
        )
        added += 1
        logger.info(
            "indexed on-disk file project=%s version=%s fileName=%s",
            stored.project,
            stored.version,
            stored.file_name,
        )
    if added > 0:
        logger.info("synced %s artifact(s) from disk into the JSON store", added)
    return added

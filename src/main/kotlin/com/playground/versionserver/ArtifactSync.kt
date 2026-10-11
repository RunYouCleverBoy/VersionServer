package com.playground.versionserver

import java.time.Instant
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.playground.versionserver.ArtifactSync")

/**
 * Indexes files present on disk that are missing from [database].
 * Existing artifact rows (including uploadedAt) are left unchanged.
 *
 * @return number of artifacts added
 */
fun syncArtifactsFromDisk(database: JsonDatabase, fileStore: FileStore): Int {
    var added = 0
    for (file in fileStore.listAll()) {
        if (database.findArtifact(file.project, file.version, file.fileName) != null) {
            continue
        }
        database.addArtifact(
            project = file.project,
            version = file.version,
            fileName = file.fileName,
            uploadedAt = file.lastModified ?: Instant.EPOCH,
        )
        added += 1
        log.info(
            "indexed on-disk file project={} version={} fileName={}",
            file.project,
            file.version,
            file.fileName,
        )
    }
    if (added > 0) {
        log.info("synced {} artifact(s) from disk into the JSON store", added)
    }
    return added
}

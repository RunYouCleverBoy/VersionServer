package com.playground.versionserver

import java.time.Instant

/**
 * Storage for versioned project files. A version may hold many files.
 * Implementations may use a local filesystem, object storage, etc.
 */
data class StoredFileRef(
    val project: String,
    val version: String,
    val fileName: String,
    val lastModified: Instant? = null,
)

interface FileStore {
    fun store(project: String, version: String, fileName: String, bytes: ByteArray): Boolean

    fun read(project: String, version: String, fileName: String): ByteArray?

    fun list(project: String, version: String): List<String>

    /** All files under storage, as `{project}/{version}/{file...}`. */
    fun listAll(): List<StoredFileRef>

    fun delete(project: String, version: String, fileName: String)

    fun deleteVersion(project: String, version: String)
}

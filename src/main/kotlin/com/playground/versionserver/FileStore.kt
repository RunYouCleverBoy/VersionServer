package com.playground.versionserver

/**
 * Storage for versioned project files. A version may hold many files.
 * Implementations may use a local filesystem, object storage, etc.
 */
interface FileStore {
    fun store(project: String, version: String, fileName: String, bytes: ByteArray): Boolean

    fun read(project: String, version: String, fileName: String): ByteArray?

    fun list(project: String, version: String): List<String>

    fun delete(project: String, version: String, fileName: String)
}

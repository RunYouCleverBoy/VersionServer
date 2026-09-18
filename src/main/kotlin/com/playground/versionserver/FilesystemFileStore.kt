package com.playground.versionserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class FilesystemFileStore(private val root: Path) : FileStore {
    override fun store(project: String, version: String, fileName: String, bytes: ByteArray): Boolean {
        val path = pathFor(project, version, fileName) ?: return false
        Files.createDirectories(path.parent)
        path.writeBytes(bytes)
        return true
    }

    override fun read(project: String, version: String, fileName: String): ByteArray? {
        val path = pathFor(project, version, fileName) ?: return null
        if (!path.exists()) return null
        return path.readBytes()
    }

    override fun list(project: String, version: String): List<String> {
        val directory = directoryFor(project, version) ?: return emptyList()
        if (!directory.exists()) return emptyList()
        return directory.listDirectoryEntries()
            .filter { Files.isRegularFile(it) }
            .map { it.fileName.toString() }
    }

    override fun delete(project: String, version: String, fileName: String) {
        val path = pathFor(project, version, fileName) ?: return
        Files.deleteIfExists(path)
    }

    private fun directoryFor(project: String, version: String): Path? {
        val safeProject = safeSegment(project) ?: return null
        val safeVersion = safeSegment(version) ?: return null
        return root.resolve(safeProject).resolve(safeVersion)
    }

    private fun pathFor(project: String, version: String, fileName: String): Path? {
        val directory = directoryFor(project, version) ?: return null
        val safeFile = safeSegment(fileName) ?: return null
        return directory.resolve(safeFile)
    }

    private fun safeSegment(value: String): String? {
        if (value.isEmpty() || value == "." || value == "..") return null
        if (value.contains('/') || value.contains('\\')) return null
        return value
    }
}

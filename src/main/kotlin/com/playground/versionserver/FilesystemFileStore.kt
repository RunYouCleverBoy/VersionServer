package com.playground.versionserver

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.writeBytes
import kotlin.streams.asSequence
import org.slf4j.LoggerFactory

class FilesystemFileStore(private val root: Path) : FileStore {
    private val log = LoggerFactory.getLogger(FilesystemFileStore::class.java)

    override fun store(project: String, version: String, fileName: String, bytes: ByteArray): Boolean {
        val path = pathFor(project, version, fileName)
        if (path == null) {
            log.warn(
                "store rejected unsafe path project={} version={} fileName={}",
                project,
                version,
                fileName,
            )
            return false
        }
        Files.createDirectories(path.parent)
        path.writeBytes(bytes)
        log.debug("stored project={} version={} fileName={} path={}", project, version, fileName, path)
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
        return Files.walk(directory).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .map { it.relativeTo(directory).toString().replace('\\', '/') }
                .toList()
        }
    }

    override fun listAll(): List<StoredFileRef> {
        if (!root.exists() || !root.isDirectory()) return emptyList()
        val found = mutableListOf<StoredFileRef>()
        for (projectDir in root.listDirectoryEntries()) {
            if (!projectDir.isDirectory()) continue
            val project = safeSegment(projectDir.fileName.toString()) ?: continue
            for (versionDir in projectDir.listDirectoryEntries()) {
                if (!versionDir.isDirectory()) continue
                val version = safeSegment(versionDir.fileName.toString()) ?: continue
                for (fileName in list(project, version)) {
                    val path = pathFor(project, version, fileName) ?: continue
                    val modified = runCatching {
                        Files.getLastModifiedTime(path).toInstant()
                    }.getOrNull()
                    found += StoredFileRef(
                        project = project,
                        version = version,
                        fileName = fileName,
                        lastModified = modified,
                    )
                }
            }
        }
        return found
    }

    override fun delete(project: String, version: String, fileName: String) {
        val path = pathFor(project, version, fileName) ?: return
        Files.deleteIfExists(path)
    }

    override fun deleteVersion(project: String, version: String) {
        val directory = directoryFor(project, version) ?: return
        if (!directory.exists()) return
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun directoryFor(project: String, version: String): Path? {
        val safeProject = safeSegment(project) ?: return null
        val safeVersion = safeSegment(version) ?: return null
        return root.resolve(safeProject).resolve(safeVersion).normalize()
    }

    private fun pathFor(project: String, version: String, fileName: String): Path? {
        val directory = directoryFor(project, version) ?: return null
        val relative = safeRelativePath(fileName) ?: return null
        val resolved = directory.resolve(relative).normalize()
        if (!resolved.startsWith(directory)) return null
        return resolved
    }

    private fun safeSegment(value: String): String? {
        if (value.isEmpty() || value == "." || value == "..") return null
        if (value.contains('/') || value.contains('\\')) return null
        return value
    }

    private fun safeRelativePath(value: String): String? {
        if (value.isBlank()) return null
        val normalized = value.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }
}

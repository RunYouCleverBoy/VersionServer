package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactSyncTest {
    @Test
    fun `startup sync indexes files on disk that are missing from the store`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStore = createTempFile("vs-store", ".json")
        jsonStore.writeText(
            """
            {
              "users": [
                { "id": "admin", "passwordHash": "${sha256Hex("admin-pass")}", "role": "Admin" }
              ],
              "grants": [],
              "artifacts": []
            }
            """.trimIndent(),
        )
        val nested = storageRoot.resolve("alpha/1.0/lib")
        nested.createDirectories()
        nested.resolve("util.bin").writeBytes(byteArrayOf(1, 2))
        storageRoot.resolve("alpha/1.0/app.bin").writeBytes(byteArrayOf(3))
        storageRoot.resolve("beta/2.0").createDirectories()
        storageRoot.resolve("beta/2.0/notes.txt").writeBytes(byteArrayOf(4))

        val database = JsonDatabase(jsonStore)
        val fileStore = FilesystemFileStore(storageRoot)
        val added = syncArtifactsFromDisk(database, fileStore)

        assertEquals(3, added)
        assertEquals(
            setOf("app.bin", "lib/util.bin"),
            database.artifacts("alpha", "1.0").map { it.fileName }.toSet(),
        )
        assertEquals(listOf("notes.txt"), database.artifacts("beta", "2.0").map { it.fileName })
        assertTrue(database.artifacts("alpha", "1.0").all { Instant.parse(it.uploadedAt) > Instant.EPOCH })
    }

    @Test
    fun `startup sync does not overwrite existing artifact metadata`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStore = createTempFile("vs-store", ".json")
        val stamped = "2024-01-01T00:00:00Z"
        jsonStore.writeText(
            """
            {
              "users": [
                { "id": "admin", "passwordHash": "${sha256Hex("admin-pass")}", "role": "Admin" }
              ],
              "grants": [],
              "artifacts": [
                {
                  "project": "alpha",
                  "version": "1.0",
                  "fileName": "app.bin",
                  "uploadedAt": "$stamped"
                }
              ]
            }
            """.trimIndent(),
        )
        storageRoot.resolve("alpha/1.0").createDirectories()
        storageRoot.resolve("alpha/1.0/app.bin").writeBytes(byteArrayOf(1))
        storageRoot.resolve("alpha/1.0/extra.bin").writeBytes(byteArrayOf(2))

        val database = JsonDatabase(jsonStore)
        val added = syncArtifactsFromDisk(database, FilesystemFileStore(storageRoot))

        assertEquals(1, added)
        assertEquals(stamped, database.findArtifact("alpha", "1.0", "app.bin")?.uploadedAt)
        assertEquals("extra.bin", database.findArtifact("alpha", "1.0", "extra.bin")?.fileName)
    }

    @Test
    fun `module startup sync makes on-disk files visible through the API`() = withVersionServer(
        storageRoot = createTempDirectory("vs-files").also { root ->
            root.resolve("alpha/1.0").createDirectories()
            root.resolve("alpha/1.0/from-disk.bin").writeBytes(byteArrayOf(9))
        },
        jsonStorePath = createTempFile("vs-store", ".json").also { path ->
            path.writeText(
                """
                {
                  "users": [
                    { "id": "admin", "passwordHash": "${sha256Hex("admin-pass")}", "role": "Admin" },
                    { "id": "alice", "passwordHash": "${sha256Hex("alice-pass")}", "role": "Client" }
                  ],
                  "grants": [
                    { "userId": "alice", "project": "alpha" }
                  ],
                  "artifacts": []
                }
                """.trimIndent(),
            )
        },
    ) {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, versions.status)
        assertEquals(listOf("1.0"), versions.body<VersionListResponse>().versionNames())

        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("from-disk.bin"), files.body<FileListResponse>().fileNames())
    }

    @Test
    fun `listAll walks project version trees including nested files`() {
        val root = createTempDirectory("vs-files")
        Files.createDirectories(root.resolve("alpha/1.0/lib"))
        root.resolve("alpha/1.0/app.bin").writeBytes(byteArrayOf(1))
        root.resolve("alpha/1.0/lib/util.bin").writeBytes(byteArrayOf(2))
        // junk that is not project/version/file
        root.resolve("readme.txt").writeBytes(byteArrayOf(3))
        Files.createDirectories(root.resolve("lonely-project"))

        val listed = FilesystemFileStore(root).listAll()
            .map { Triple(it.project, it.version, it.fileName) }
            .toSet()
        assertEquals(
            setOf(
                Triple("alpha", "1.0", "app.bin"),
                Triple("alpha", "1.0", "lib/util.bin"),
            ),
            listed,
        )
    }
}

package com.playground.versionserver

import java.time.Instant
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactVersioningTest {
    @Test
    fun `latest version is the one with the newest artifact upload`() {
        val database = JsonDatabase(createTempFile("vs-store", ".json"))
        database.addArtifact("alpha", "1.0", "a.bin", Instant.parse("2024-01-01T00:00:00Z"))
        database.addArtifact("alpha", "2.0", "b.bin", Instant.parse("2024-06-01T00:00:00Z"))
        database.addArtifact("alpha", "1.0", "c.bin", Instant.parse("2024-03-01T00:00:00Z"))

        val versions = database.versionsFor("alpha")
        assertEquals(listOf("1.0", "2.0"), versions.map { it.name })
        assertFalse(versions.first { it.name == "1.0" }.latest)
        assertTrue(versions.first { it.name == "2.0" }.latest)
    }

    @Test
    fun `reuploading a file refreshes its timestamp and can change latest`() {
        val database = JsonDatabase(createTempFile("vs-store", ".json"))
        database.addArtifact("alpha", "1.0", "a.bin", Instant.parse("2024-01-01T00:00:00Z"))
        database.addArtifact("alpha", "2.0", "b.bin", Instant.parse("2024-02-01T00:00:00Z"))
        assertTrue(database.versionsFor("alpha").first { it.name == "2.0" }.latest)

        database.addArtifact("alpha", "1.0", "a.bin", Instant.parse("2024-12-01T00:00:00Z"))
        val versions = database.versionsFor("alpha")
        assertTrue(versions.first { it.name == "1.0" }.latest)
        assertFalse(versions.first { it.name == "2.0" }.latest)
        assertEquals(
            "2024-12-01T00:00:00Z",
            database.findArtifact("alpha", "1.0", "a.bin")?.uploadedAt,
        )
    }

    @Test
    fun `existing store without uploadedAt is rewritten with the field`() {
        val path = createTempFile("vs-store", ".json")
        path.writeText(
            """
            {
              "users": [
                { "id": "admin", "passwordHash": "${sha256Hex("admin-pass")}", "role": "Admin" }
              ],
              "grants": [],
              "artifacts": [
                { "project": "alpha", "version": "1.0", "fileName": "app.bin" }
              ]
            }
            """.trimIndent(),
        )
        JsonDatabase(path)
        val rewritten = path.readText()
        assertTrue(rewritten.contains("uploadedAt"))
        assertTrue(rewritten.contains("app.bin"))
    }
}

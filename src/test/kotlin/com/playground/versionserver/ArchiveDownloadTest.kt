package com.playground.versionserver

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.readByteArray

class ArchiveDownloadTest {
    @Test
    fun `authorized user can download a whole version as zip`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.uploadFile(adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1, 2))
        client.uploadFile(adminToken, "alpha", "1.0", "lib/util.bin", byteArrayOf(3, 4))
        client.grantAccess(adminToken, "alpha", "alice")

        val aliceToken = client.login("alice", "alice-pass")
        val response = client.get("/projects/alpha/versions/1.0/archive") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.contains("zip") == true)
        val entries = unzip(response.bodyAsChannel().readRemaining().readByteArray())
        assertEquals(
            mapOf(
                "app.bin" to listOf(1.toByte(), 2.toByte()),
                "lib/util.bin" to listOf(3.toByte(), 4.toByte()),
            ),
            entries.mapValues { it.value.toList() },
        )
    }

    @Test
    fun `authorized user can download a directory prefix as zip`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.uploadFile(adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        client.uploadFile(adminToken, "alpha", "1.0", "lib/util.bin", byteArrayOf(2))
        client.uploadFile(adminToken, "alpha", "1.0", "lib/nested/x.bin", byteArrayOf(3))
        client.grantAccess(adminToken, "alpha", "alice")

        val aliceToken = client.login("alice", "alice-pass")
        val response = client.get("/projects/alpha/versions/1.0/archive?prefix=lib") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val entries = unzip(response.bodyAsChannel().readRemaining().readByteArray())
        assertEquals(
            mapOf(
                "util.bin" to listOf(2.toByte()),
                "nested/x.bin" to listOf(3.toByte()),
            ),
            entries.mapValues { it.value.toList() },
        )
    }

    @Test
    fun `archive with unknown prefix is not found`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.uploadFile(adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        val response = client.get("/projects/alpha/versions/1.0/archive?prefix=missing") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `unauthorized user cannot download archive`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.uploadFile(adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        val bobToken = client.login("bob", "bob-pass")
        val response = client.get("/projects/alpha/versions/1.0/archive") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}

private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            out[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    return out
}

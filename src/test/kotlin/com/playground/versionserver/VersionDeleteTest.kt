package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionDeleteTest {
    @Test
    fun `admin can delete a whole version and its files`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        upload(client, adminToken, "alpha", "1.0", "lib/util.bin", byteArrayOf(2))
        upload(client, adminToken, "alpha", "2.0", "app.bin", byteArrayOf(3))

        val deleted = client.delete("/projects/alpha/versions/1.0") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val aliceToken = client.login("alice", "alice-pass")
        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("2.0"), versions.body<VersionListResponse>().versionNames())

        val gone = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.NotFound, gone.status)

        val kept = client.get("/projects/alpha/versions/2.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, kept.status)
    }

    @Test
    fun `client cannot delete a version`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))

        val aliceToken = client.login("alice", "alice-pass")
        val deleted = client.delete("/projects/alpha/versions/1.0") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.Forbidden, deleted.status)

        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("1.0"), versions.body<VersionListResponse>().versionNames())
    }

    @Test
    fun `deleting an unknown version is idempotent`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val deleted = client.delete("/projects/alpha/versions/missing") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
    }

    private suspend fun upload(
        client: io.ktor.client.HttpClient,
        token: String,
        project: String,
        version: String,
        fileName: String,
        bytes: ByteArray,
    ) {
        client.submitFormWithBinaryData(
            url = "/projects/$project/versions/$version/files",
            formData = formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

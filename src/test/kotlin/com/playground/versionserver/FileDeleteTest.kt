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

class FileDeleteTest {
    @Test
    fun `admin delete removes the file from list and download`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        client.submitFormWithBinaryData(
            url = "/projects/alpha/versions/1.0/files",
            formData = formData {
                append(
                    "file",
                    byteArrayOf(1, 2, 3),
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"app.bin\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        val deleted = client.delete("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val aliceToken = client.login("alice", "alice-pass")
        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        val download = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(emptyList(), files.body<FileListResponse>().files)
        assertEquals(HttpStatusCode.NotFound, download.status)
    }

    @Test
    fun `client cannot delete a file`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        client.submitFormWithBinaryData(
            url = "/projects/alpha/versions/1.0/files",
            formData = formData {
                append(
                    "file",
                    byteArrayOf(1),
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"app.bin\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val aliceToken = client.login("alice", "alice-pass")
        val deleted = client.delete("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.Forbidden, deleted.status)
    }

    @Test
    fun `download of a never uploaded file fails as absence`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        val aliceToken = client.login("alice", "alice-pass")
        val download = client.get("/projects/alpha/versions/1.0/files/missing.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.NotFound, download.status)
    }
}

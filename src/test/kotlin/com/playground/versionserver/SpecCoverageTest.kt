package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Fills gaps against docs/specs/API-SYSTEM.md that aren't already covered
 * by the focused feature test classes.
 */
class SpecCoverageTest {
    @Test
    fun `protected admin and file routes reject missing tokens`() = withVersionServer {
        val client = jsonHttpClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/users").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/users") {
                contentType(ContentType.Application.Json)
                setBody(EnrollUserRequest("x", "y", Role.Client))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/projects/alpha/access") {
                contentType(ContentType.Application.Json)
                setBody(GrantAccessRequest("alice"))
            }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/projects/alpha/versions").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/projects/alpha/versions/1.0/files").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/projects/alpha/versions/1.0/files/app.bin").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/projects/alpha/versions/1.0/files/app.bin").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
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
            ).status,
        )
    }

    @Test
    fun `protected routes reject invalid tokens`() = withVersionServer {
        val client = jsonHttpClient()
        val auth = mapOf(HttpHeaders.Authorization to "Bearer not-a-token")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/users") { header(HttpHeaders.Authorization, auth.getValue(HttpHeaders.Authorization)) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/projects/alpha/versions") {
                header(HttpHeaders.Authorization, "Bearer not-a-token")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/projects/alpha/versions/1.0/files/app.bin") {
                header(HttpHeaders.Authorization, "Bearer not-a-token")
            }.status,
        )
    }

    @Test
    fun `enrolling with blank user id or empty password is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/users") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(EnrollUserRequest(userId = "  ", password = "pass", role = Role.Client))
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/users") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(EnrollUserRequest(userId = "carol", password = "", role = Role.Client))
            }.status,
        )
    }

    @Test
    fun `granting with blank user id is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/projects/alpha/access") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(GrantAccessRequest(userId = "  "))
            }.status,
        )
    }

    @Test
    fun `user list does not expose passwords`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val users = client.get("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.body<UserListResponse>().users
        assertEquals(setOf("admin", "alice", "bob"), users.map { it.userId }.toSet())
        val raw = client.get("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.bodyAsText()
        assertFalse(raw.contains("password"))
        assertFalse(raw.contains("admin-pass"))
    }

    @Test
    fun `admin sees projects that exist only as uploaded artifacts`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        upload(client, adminToken, "solo", "1.0", "app.bin", byteArrayOf(9))
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(listOf("solo"), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `admin can list and download files without a personal grant`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1, 2))
        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(listOf("app.bin"), files.body<FileListResponse>().files)
        val download = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(byteArrayOf(1, 2), download.body())
    }

    @Test
    fun `path traversal in uploaded file name is rejected over HTTP`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val upload = client.submitFormWithBinaryData(
            url = "/projects/alpha/versions/1.0/files",
            formData = formData {
                append(
                    "file",
                    byteArrayOf(1),
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"../escape.bin\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, upload.status)
    }

    @Test
    fun `nested file can be deleted by admin`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "lib/util.bin", byteArrayOf(3))
        val deleted = client.delete("/projects/alpha/versions/1.0/files/lib/util.bin") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val aliceToken = client.login("alice", "alice-pass")
        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(emptyList(), files.body<FileListResponse>().files)
        val download = client.get("/projects/alpha/versions/1.0/files/lib/util.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.NotFound, download.status)
    }

    @Test
    fun `enrolled user grants and credentials survive restart`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val adminToken = client.login("admin", "admin-pass")
            client.post("/users") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(EnrollUserRequest("carol", "carol-pass", Role.Client))
            }
            client.post("/projects/alpha/access") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(GrantAccessRequest("carol"))
            }
        }
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val carolToken = client.login("carol", "carol-pass")
            val list = client.get("/projects") {
                header(HttpHeaders.Authorization, "Bearer $carolToken")
            }
            assertEquals(listOf("alpha"), list.body<ProjectListResponse>().projects)
        }
    }
}

private suspend fun upload(
    client: io.ktor.client.HttpClient,
    adminToken: String,
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
        header(HttpHeaders.Authorization, "Bearer $adminToken")
    }
}

package com.playground.versionserver

import io.ktor.client.call.body
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
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FilePublishTest {
    @Test
    fun `admin uploads a file and authorized user lists and downloads it`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        val bytes = byteArrayOf(10, 20, 30)
        val upload = client.submitFormWithBinaryData(
            url = "/projects/alpha/versions/1.0/files",
            formData = formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"app.bin\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Created, upload.status)

        val aliceToken = client.login("alice", "alice-pass")
        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, versions.status)
        assertEquals(listOf("1.0"), versions.body<VersionListResponse>().versions)

        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("app.bin"), files.body<FileListResponse>().files)

        val download = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(bytes, download.body<ByteArray>())
    }

    @Test
    fun `client cannot upload`() = withVersionServer {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val upload = client.submitFormWithBinaryData(
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
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.Forbidden, upload.status)
    }

    @Test
    fun `unauthorized user cannot list versions files or download`() = withVersionServer {
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
                    byteArrayOf(1, 2),
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"app.bin\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val bobToken = client.login("bob", "bob-pass")
        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val download = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.Forbidden, versions.status)
        assertEquals(HttpStatusCode.Forbidden, files.status)
        assertEquals(HttpStatusCode.Forbidden, download.status)
    }

    @Test
    fun `second version on the same project is listed`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        upload(client, adminToken, "alpha", "2.0", "app.bin", byteArrayOf(2))
        val aliceToken = client.login("alice", "alice-pass")
        val versions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("1.0", "2.0"), versions.body<VersionListResponse>().versions.sorted())
    }

    @Test
    fun `a version can hold more than one file`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        upload(client, adminToken, "alpha", "1.0", "notes.txt", byteArrayOf(2, 3))
        val aliceToken = client.login("alice", "alice-pass")
        val files = client.get("/projects/alpha/versions/1.0/files") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("app.bin", "notes.txt"), files.body<FileListResponse>().files.sorted())
        val app = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        val notes = client.get("/projects/alpha/versions/1.0/files/notes.txt") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertContentEquals(byteArrayOf(1), app.body<ByteArray>())
        assertContentEquals(byteArrayOf(2, 3), notes.body<ByteArray>())
    }

    @Test
    fun `projects stay isolated when listing and downloading`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        client.post("/projects/beta/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(1))
        upload(client, adminToken, "beta", "1.0", "app.bin", byteArrayOf(9))
        val aliceToken = client.login("alice", "alice-pass")
        val alphaDownload = client.get("/projects/alpha/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        val betaDownload = client.get("/projects/beta/versions/1.0/files/app.bin") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertContentEquals(byteArrayOf(1), alphaDownload.body<ByteArray>())
        assertContentEquals(byteArrayOf(9), betaDownload.body<ByteArray>())
        val alphaVersions = client.get("/projects/alpha/versions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("1.0"), alphaVersions.body<VersionListResponse>().versions)
    }

    @Test
    fun `uploaded files are still listable and downloadable after restart`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val adminToken = client.login("admin", "admin-pass")
            client.post("/projects/alpha/access") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(GrantAccessRequest(userId = "alice"))
            }
            upload(client, adminToken, "alpha", "1.0", "app.bin", byteArrayOf(7, 8, 9))
        }
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val aliceToken = client.login("alice", "alice-pass")
            val files = client.get("/projects/alpha/versions/1.0/files") {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
            }
            val download = client.get("/projects/alpha/versions/1.0/files/app.bin") {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
            }
            assertEquals(listOf("app.bin"), files.body<FileListResponse>().files)
            assertContentEquals(byteArrayOf(7, 8, 9), download.body<ByteArray>())
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

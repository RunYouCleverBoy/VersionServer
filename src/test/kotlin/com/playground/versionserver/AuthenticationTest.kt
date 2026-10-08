package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthenticationTest {
    @Test
    fun `admin authenticates and receives a token`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "admin", password = "admin-pass"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertTrue(body.token.isJwtFormat())
        assertEquals("admin", body.userId)
        assertEquals(Role.Admin, body.role)
    }

    @Test
    fun `client authenticates and receives a token`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "alice", password = "alice-pass"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertTrue(body.token.isJwtFormat())
        assertEquals("alice", body.userId)
        assertEquals(Role.Client, body.role)
    }

    @Test
    fun `bad credentials are rejected and do not issue a token`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "admin", password = "wrong"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `users can still authenticate after a process restart`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val first = client.post("/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(userId = "admin", password = "admin-pass"))
            }
            assertEquals(HttpStatusCode.OK, first.status)
        }
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val second = client.post("/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(userId = "admin", password = "admin-pass"))
            }
            assertEquals(HttpStatusCode.OK, second.status)
            assertNotEquals("", second.body<LoginResponse>().token)
        }
    }
}

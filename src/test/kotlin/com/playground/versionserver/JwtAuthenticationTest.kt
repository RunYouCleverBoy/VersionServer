package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwtAuthenticationTest {
    @Test
    fun `login issues a JWT and sets it on an HttpOnly cookie`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "admin", password = "admin-pass"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertTrue(body.token.isJwtFormat())
        val cookie = response.setCookie().first { it.name == "VS_TOKEN" }
        assertEquals(body.token, cookie.value)
        assertTrue(cookie.httpOnly)
        assertEquals("/", cookie.path)
    }

    @Test
    fun `cookie JWT alone authenticates without Authorization header`() = withVersionServer {
        val client = jsonHttpClient()
        client.login("admin", "admin-pass")
        // HttpCookies plugin keeps VS_TOKEN; no Bearer header set here.
        val list = client.get("/projects")
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(emptyList(), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `invalid JWT string is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer not.a.jwt")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `tampered JWT is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val jwt = client.login("admin", "admin-pass")
        val tampered = jwt.dropLast(1) + if (jwt.last() == 'a') 'b' else 'a'
        val response = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $tampered")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `JWT remains valid after process restart with the same secret`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        val secret = "stable-secret"
        lateinit var jwt: String
        withVersionServer(storageRoot, jsonStorePath, jwtSecret = secret) {
            val client = jsonHttpClient()
            jwt = client.login("admin", "admin-pass")
            assertTrue(jwt.isJwtFormat())
        }
        withVersionServer(storageRoot, jsonStorePath, jwtSecret = secret) {
            val client = jsonHttpClient()
            val list = client.get("/projects") {
                header(HttpHeaders.Authorization, "Bearer $jwt")
            }
            assertEquals(HttpStatusCode.OK, list.status)
        }
    }

    @Test
    fun `JWT signed with a different secret is rejected after restart`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        lateinit var jwt: String
        withVersionServer(storageRoot, jsonStorePath, jwtSecret = "secret-a") {
            val client = jsonHttpClient()
            jwt = client.login("admin", "admin-pass")
        }
        withVersionServer(storageRoot, jsonStorePath, jwtSecret = "secret-b") {
            val client = jsonHttpClient()
            val list = client.get("/projects") {
                header(HttpHeaders.Authorization, "Bearer $jwt")
            }
            assertEquals(HttpStatusCode.Unauthorized, list.status)
        }
    }

    @Test
    fun `logout clears the auth cookie`() = withVersionServer {
        val client = jsonHttpClient()
        client.login("admin", "admin-pass")
        assertEquals(HttpStatusCode.OK, client.get("/projects").status)

        val logout = client.post("/logout")
        assertEquals(HttpStatusCode.NoContent, logout.status)
        val cleared = logout.setCookie().firstOrNull { it.name == "VS_TOKEN" }
        assertTrue(cleared != null)
        assertTrue(cleared!!.maxAge == 0 || cleared.value.isEmpty())

        // Cookie jar drops expired cookie; subsequent cookie-only requests are unauthenticated.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/projects").status)
    }

    @Test
    fun `login response body token is the same JWT stored in the cookie`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "alice", password = "alice-pass"))
        }
        val body = response.bodyAsText()
        assertTrue(body.contains("\"token\""))
        val jwt = response.body<LoginResponse>().token
        assertEquals(jwt, response.setCookie().first { it.name == "VS_TOKEN" }.value)
        assertTrue(jwt.isJwtFormat())
    }
}

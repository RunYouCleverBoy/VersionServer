package com.playground.versionserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentationTest {
    @Test
    fun `doc without trailing slash redirects`() = withVersionServer {
        val noRedirectClient = createClient {
            followRedirects = false
        }
        val response = noRedirectClient.get("/doc")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/doc/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `doc index lists documentation pages`() = withVersionServer {
        val response = client.get("/doc/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Documentation"))
        assertTrue(body.contains("/doc/API.md"))
        assertTrue(body.contains("/doc/specs/API-SYSTEM.md"))
        assertTrue(body.contains("/doc/specs/CLIENT-SYSTEM.md"))
        assertTrue(body.contains("/doc/styles.css"))
    }

    @Test
    fun `api markdown is rendered as HTML`() = withVersionServer {
        val response = client.get("/doc/API.md")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("text/html") == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("Version Server API") || body.contains("HTTP API"))
        assertTrue(body.contains("<table>") || body.contains("Authentication"))
        assertTrue(body.contains("/doc/specs/API-SYSTEM.md"))
        assertFalse(body.contains("<script>alert"))
    }

    @Test
    fun `system specs render with tables`() = withVersionServer {
        val response = client.get("/doc/specs/API-SYSTEM.md")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("<table>"))
        assertTrue(body.contains("JWT") || body.contains("jwt") || body.contains("token"))
    }

    @Test
    fun `doc stylesheet is served`() = withVersionServer {
        val response = client.get("/doc/styles.css")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("--accent"))
    }

    @Test
    fun `path traversal is rejected`() = withVersionServer {
        assertEquals(HttpStatusCode.NotFound, client.get("/doc/../Application.kt").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/doc/specs/../../build.gradle.kts").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/doc/missing.md").status)
    }

    @Test
    fun `relative markdown links resolve under doc`() {
        val rewritten = rewriteDocLinks(
            "See [spec](specs/API-SYSTEM.md) and [abs](/ui/).",
            "API.md",
        )
        assertTrue(rewritten.contains("(/doc/specs/API-SYSTEM.md)"))
        assertTrue(rewritten.contains("(/ui/)"))
    }

    @Test
    fun `safe doc path helper rejects traversal`() {
        assertTrue(isSafeDocPath("API.md"))
        assertTrue(isSafeDocPath("specs/API-SYSTEM.md"))
        assertFalse(isSafeDocPath("../secret.md"))
        assertFalse(isSafeDocPath("specs/../../secret.md"))
        assertFalse(isSafeDocPath("/etc/passwd.md"))
    }
}

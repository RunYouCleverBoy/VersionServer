package com.playground.versionserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientUiTest {
    @Test
    fun `root redirects to the web client`() = withVersionServer {
        val noRedirectClient = createClient {
            followRedirects = false
        }
        val response = noRedirectClient.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/ui/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `ui without trailing slash redirects so relative assets resolve`() = withVersionServer {
        val noRedirectClient = createClient {
            followRedirects = false
        }
        val response = noRedirectClient.get("/ui")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/ui/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `web client is served at ui path`() = withVersionServer {
        val response = client.get("/ui/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Version Server"))
        assertTrue(body.contains("Sign in"))
        assertTrue(body.contains("/ui/app.js"))
        assertTrue(body.contains("/ui/styles.css"))
        assertTrue(body.contains("/doc/"))
        assertTrue(body.contains("Docs"))
    }

    @Test
    fun `web client stylesheet is served`() = withVersionServer {
        val response = client.get("/ui/styles.css")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("--accent"))
    }

    @Test
    fun `web client script is served`() = withVersionServer {
        val response = client.get("/ui/app.js")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("/login"))
        assertTrue(body.contains("/projects"))
        assertTrue(body.contains("collectFromDataTransfer"))
        assertTrue(body.contains("webkitGetAsEntry"))
    }

    @Test
    fun `web client upload area supports drag and drop of files and folders`() = withVersionServer {
        val html = client.get("/ui/").bodyAsText()
        assertTrue(html.contains("drop-zone"))
        assertTrue(html.contains("Drag files or folders here"))
        assertTrue(html.contains("webkitdirectory"))
        assertTrue(html.contains("Choose folder"))
    }

    @Test
    fun `web client exposes admin enrollment controls`() = withVersionServer {
        val html = client.get("/ui/").bodyAsText()
        assertTrue(html.contains("enroll-form"))
        assertTrue(html.contains("Enroll user"))
        val script = client.get("/ui/app.js").bodyAsText()
        assertTrue(script.contains("POST"))
        assertTrue(script.contains("/users"))
    }

    @Test
    fun `web client exposes password change controls`() = withVersionServer {
        val html = client.get("/ui/").bodyAsText()
        assertTrue(html.contains("password-form"))
        assertTrue(html.contains("Change password"))
        val script = client.get("/ui/app.js").bodyAsText()
        assertTrue(script.contains("/password"))
    }

    @Test
    fun `login response includes role for the web client`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.loginResponse("admin", "admin-pass")
        assertEquals("admin", response.userId)
        assertEquals(Role.Admin, response.role)
        assertTrue(response.token.isNotBlank())
    }

    @Test
    fun `web client gates admin controls and encodes nested file paths`() = withVersionServer {
        val script = client.get("/ui/app.js").bodyAsText()
        assertTrue(script.contains("function isAdmin()"))
        assertTrue(script.contains("els.adminEnroll.hidden = !isAdmin()"))
        assertTrue(script.contains("els.adminGrant.hidden = !isAdmin()"))
        assertTrue(script.contains("els.adminUsers.hidden = !isAdmin()"))
        assertTrue(script.contains("els.adminUpload.hidden = !isAdmin()"))
        assertTrue(script.contains("data-accordion"))
        assertTrue(script.contains("other.open = false"))
        assertTrue(script.contains("encodeURIComponent(segment)"))
        assertTrue(script.contains("credentials"))
        assertTrue(script.contains("enterWorkspace"))
        assertTrue(script.contains("/logout"))
        assertTrue(script.contains("method: \"POST\""))
        assertTrue(script.contains("clearSession"))
        assertTrue(script.contains("showToast"))
        assertFalse(script.contains("localStorage"))
        assertTrue(script.contains("pick-files") || script.contains("pickFiles"))
        assertTrue(script.contains("pick-folder") || script.contains("pickFolder"))
        assertTrue(script.contains("grant-form") || script.contains("grantForm"))
        assertTrue(script.contains("User must exist") || script.contains("Grant failed"))
        assertTrue(script.contains("/versions/") && script.contains("DELETE"))
        assertTrue(script.contains("Delete version") || script.contains("Deleted version"))
    }

    @Test
    fun `web client includes grant and account controls in markup`() = withVersionServer {
        val html = client.get("/ui/").bodyAsText()
        assertTrue(html.contains("grant-form"))
        assertTrue(html.contains("Grant access"))
        assertTrue(html.contains("account-box"))
        assertTrue(html.contains("admin-upload"))
        assertTrue(html.contains("Choose files"))
        assertTrue(html.contains("data-accordion=\"rail\""))
        assertTrue(html.contains("<details id=\"admin-enroll\""))
        assertTrue(html.contains("<details id=\"admin-users\""))
        assertTrue(html.contains("<details id=\"admin-grant\""))
        assertTrue(html.contains("<details id=\"account-box\""))
    }
}

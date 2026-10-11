package com.playground.versionserver

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordChangeTest {
    @Test
    fun `client can change password and then old password fails`() = withVersionServer {
        assertPasswordChangeInvalidatesOldLogin(
            userId = "alice",
            currentPassword = "alice-pass",
            newPassword = "alice-new",
            useBearer = true,
        )
    }

    @Test
    fun `admin can change password and then old password fails`() = withVersionServer {
        assertPasswordChangeInvalidatesOldLogin(
            userId = "admin",
            currentPassword = "admin-pass",
            newPassword = "admin-new",
            useBearer = true,
        )
    }

    @Test
    fun `admin can change password with cookie session like the web client`() = withVersionServer {
        assertPasswordChangeInvalidatesOldLogin(
            userId = "admin",
            currentPassword = "admin-pass",
            newPassword = "admin-cookie-new",
            useBearer = false,
        )
    }

    @Test
    fun `client wrong current password is rejected and password stays unchanged`() = withVersionServer {
        assertWrongCurrentPasswordRejected(
            userId = "alice",
            currentPassword = "alice-pass",
            useBearer = true,
        )
    }

    @Test
    fun `admin wrong current password is rejected and password stays unchanged`() = withVersionServer {
        assertWrongCurrentPasswordRejected(
            userId = "admin",
            currentPassword = "admin-pass",
            useBearer = true,
        )
    }

    @Test
    fun `admin wrong current password with cookie keeps session usable`() = withVersionServer {
        val client = jsonHttpClient()
        client.login("admin", "admin-pass")
        val changed = client.post("/password") {
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "wrong",
                    newPassword = "should-not-apply",
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, changed.status)

        // Cookie session remains valid for authenticated reads (UI must not force sign-out).
        val projects = client.get("/projects")
        assertEquals(HttpStatusCode.OK, projects.status)

        val stillWorks = client.loginResponse("admin", "admin-pass")
        assertEquals("admin", stillWorks.userId)
    }

    private suspend fun ApplicationTestBuilder.assertPasswordChangeInvalidatesOldLogin(
        userId: String,
        currentPassword: String,
        newPassword: String,
        useBearer: Boolean,
    ) {
        val client = jsonHttpClient()
        val token = client.login(userId, currentPassword)
        val changed = client.post("/password") {
            if (useBearer) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)

        val oldLogin = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = userId, password = currentPassword))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)

        val newLogin = client.loginResponse(userId, newPassword)
        assertEquals(userId, newLogin.userId)
        assertTrue(newLogin.token.isNotBlank())
    }

    private suspend fun ApplicationTestBuilder.assertWrongCurrentPasswordRejected(
        userId: String,
        currentPassword: String,
        useBearer: Boolean,
    ) {
        val client = jsonHttpClient()
        val token = client.login(userId, currentPassword)
        val changed = client.post("/password") {
            if (useBearer) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "wrong",
                    newPassword = "$userId-new",
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, changed.status)

        val stillWorks = client.loginResponse(userId, currentPassword)
        assertEquals(userId, stillWorks.userId)
    }

    @Test
    fun `empty new password is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val token = client.login("alice", "alice-pass")
        val changed = client.post("/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "alice-pass",
                    newPassword = "",
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, changed.status)
    }

    @Test
    fun `password change without a token is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val changed = client.post("/password") {
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "alice-pass",
                    newPassword = "alice-new",
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, changed.status)
    }

    @Test
    fun `changed password survives process restart`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempFile("vs-store", ".json")
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val token = client.login("alice", "alice-pass")
            client.post("/password") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(
                    ChangePasswordRequest(
                        currentPassword = "alice-pass",
                        newPassword = "alice-persisted",
                    ),
                )
            }
        }
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val login = client.loginResponse("alice", "alice-persisted")
            assertEquals("alice", login.userId)
        }
    }
}

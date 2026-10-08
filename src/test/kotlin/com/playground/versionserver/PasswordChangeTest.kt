package com.playground.versionserver

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordChangeTest {
    @Test
    fun `user can change password and then log in with the new one`() = withVersionServer {
        val client = jsonHttpClient()
        val token = client.login("alice", "alice-pass")
        val changed = client.post("/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "alice-pass",
                    newPassword = "alice-new",
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)

        val oldLogin = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(userId = "alice", password = "alice-pass"))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)

        val newLogin = client.loginResponse("alice", "alice-new")
        assertEquals("alice", newLogin.userId)
        assertTrue(newLogin.token.isNotBlank())
    }

    @Test
    fun `wrong current password is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val token = client.login("alice", "alice-pass")
        val changed = client.post("/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = "wrong",
                    newPassword = "alice-new",
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, changed.status)

        val stillWorks = client.loginResponse("alice", "alice-pass")
        assertEquals("alice", stillWorks.userId)
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

package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonDatabaseSeedTest {
    @Test
    fun `missing store file is created with default users`() {
        val path = createTempDirectory("vs-data").resolve("nested/store.json")
        assertTrue(!path.exists())
        val database = JsonDatabase(path)
        assertTrue(path.exists())
        assertTrue(path.readText().contains("passwordHash"))
        assertEquals(Role.Admin, database.authenticate("admin", "admin-pass")?.role)
        assertEquals(Role.Client, database.authenticate("alice", "alice-pass")?.role)
        assertEquals(Role.Client, database.authenticate("bob", "bob-pass")?.role)
    }

    @Test
    fun `fresh server with missing json path accepts default admin login`() {
        val storageRoot = createTempDirectory("vs-files")
        val jsonStorePath = createTempDirectory("vs-json").resolve("store.json")
        assertTrue(!jsonStorePath.exists())
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val response = client.post("/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(userId = "admin", password = "admin-pass"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("admin", response.body<LoginResponse>().userId)
            assertTrue(jsonStorePath.exists())
        }
    }
}

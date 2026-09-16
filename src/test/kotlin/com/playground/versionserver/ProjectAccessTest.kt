package com.playground.versionserver

import io.ktor.client.call.body
import io.ktor.client.request.get
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

class ProjectAccessTest {
    @Test
    fun `listing projects without a token is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.get("/projects")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `listing projects with an invalid token is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val response = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer not-a-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin can authorize a user who then sees that project`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val grant = client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        assertEquals(HttpStatusCode.NoContent, grant.status)

        val aliceToken = client.login("alice", "alice-pass")
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(listOf("alpha"), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `client cannot authorize a user for a project`() = withVersionServer {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val grant = client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "bob"))
        }
        assertEquals(HttpStatusCode.Forbidden, grant.status)
    }

    @Test
    fun `user with no grants gets an empty project list`() = withVersionServer {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(emptyList(), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `user authorized for A but not B sees only A`() = withVersionServer {
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
            setBody(GrantAccessRequest(userId = "bob"))
        }
        val aliceToken = client.login("alice", "alice-pass")
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(listOf("alpha"), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `a token only sees that users projects`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "alice"))
        }
        val aliceToken = client.login("alice", "alice-pass")
        val bobToken = client.login("bob", "bob-pass")
        val bobList = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val aliceList = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(emptyList(), bobList.body<ProjectListResponse>().projects)
        assertEquals(listOf("alpha"), aliceList.body<ProjectListResponse>().projects)
    }

    @Test
    fun `grants still apply after a process restart`() {
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
        }
        withVersionServer(storageRoot, jsonStorePath) {
            val client = jsonHttpClient()
            val aliceToken = client.login("alice", "alice-pass")
            val list = client.get("/projects") {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
            }
            assertEquals(listOf("alpha"), list.body<ProjectListResponse>().projects)
        }
    }
}

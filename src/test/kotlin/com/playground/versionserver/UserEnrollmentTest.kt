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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserEnrollmentTest {
    @Test
    fun `admin can enroll a client who can then log in`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val enrolled = client.post("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                EnrollUserRequest(
                    userId = "carol",
                    password = "carol-pass",
                    role = Role.Client,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, enrolled.status)
        val body = enrolled.body<EnrollUserResponse>()
        assertEquals("carol", body.userId)
        assertEquals(Role.Client, body.role)

        val login = client.loginResponse("carol", "carol-pass")
        assertEquals("carol", login.userId)
        assertEquals(Role.Client, login.role)
        assertTrue(login.token.isNotBlank())
    }

    @Test
    fun `admin can enroll another admin`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val enrolled = client.post("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                EnrollUserRequest(
                    userId = "root2",
                    password = "root2-pass",
                    role = Role.Admin,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, enrolled.status)
        assertEquals(Role.Admin, enrolled.body<EnrollUserResponse>().role)
    }

    @Test
    fun `client cannot enroll a user`() = withVersionServer {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val enrolled = client.post("/users") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody(
                EnrollUserRequest(
                    userId = "carol",
                    password = "carol-pass",
                    role = Role.Client,
                ),
            )
        }
        assertEquals(HttpStatusCode.Forbidden, enrolled.status)
    }

    @Test
    fun `enrolling a duplicate user id is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val enrolled = client.post("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                EnrollUserRequest(
                    userId = "alice",
                    password = "new-pass",
                    role = Role.Client,
                ),
            )
        }
        assertEquals(HttpStatusCode.Conflict, enrolled.status)
    }

    @Test
    fun `granting access to an unknown user is rejected`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val grant = client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "nobody"))
        }
        assertEquals(HttpStatusCode.NotFound, grant.status)

        val aliceToken = client.login("alice", "alice-pass")
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(emptyList(), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `admin can grant access after enrolling a user`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        client.post("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                EnrollUserRequest(
                    userId = "carol",
                    password = "carol-pass",
                    role = Role.Client,
                ),
            )
        }
        val grant = client.post("/projects/alpha/access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(GrantAccessRequest(userId = "carol"))
        }
        assertEquals(HttpStatusCode.NoContent, grant.status)

        val carolToken = client.login("carol", "carol-pass")
        val list = client.get("/projects") {
            header(HttpHeaders.Authorization, "Bearer $carolToken")
        }
        assertEquals(listOf("alpha"), list.body<ProjectListResponse>().projects)
    }

    @Test
    fun `admin can list enrolled users`() = withVersionServer {
        val client = jsonHttpClient()
        val adminToken = client.login("admin", "admin-pass")
        val list = client.get("/users") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        val users = list.body<UserListResponse>().users.map { it.userId }.sorted()
        assertEquals(listOf("admin", "alice", "bob"), users)
    }

    @Test
    fun `client cannot list users`() = withVersionServer {
        val client = jsonHttpClient()
        val aliceToken = client.login("alice", "alice-pass")
        val list = client.get("/users") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.Forbidden, list.status)
    }
}

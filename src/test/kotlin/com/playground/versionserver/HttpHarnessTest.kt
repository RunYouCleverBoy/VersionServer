package com.playground.versionserver

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpHarnessTest {
    @Test
    fun `application responds to an HTTP request with injected storage paths`() = testApplication {
        val storageRoot = Files.createTempDirectory("vs-files")
        val jsonStore = Files.createTempFile("vs-store", ".json")
        application {
            module(AppConfig(storageRoot = storageRoot, jsonStorePath = jsonStore))
        }
        val response = client.get("/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

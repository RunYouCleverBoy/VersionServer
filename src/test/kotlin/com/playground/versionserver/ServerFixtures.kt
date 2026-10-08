package com.playground.versionserver

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

internal fun ApplicationTestBuilder.installVersionServer(
    storageRoot: Path = createTempDirectory("vs-files"),
    jsonStorePath: Path = createTempFile("vs-store", ".json"),
    jwtSecret: String = "test-secret",
) {
    application {
        module(
            AppConfig(
                storageRoot = storageRoot,
                jsonStorePath = jsonStorePath,
                jwtSecret = jwtSecret,
            ),
        )
    }
}

internal fun ApplicationTestBuilder.jsonHttpClient() = createClient {
    install(HttpCookies)
    install(ContentNegotiation) { json() }
}

internal fun withVersionServer(
    storageRoot: Path = createTempDirectory("vs-files"),
    jsonStorePath: Path = createTempFile("vs-store", ".json"),
    jwtSecret: String = "test-secret",
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    installVersionServer(storageRoot, jsonStorePath, jwtSecret)
    block()
}

internal suspend fun HttpClient.login(userId: String, password: String): String =
    loginResponse(userId, password).token

internal suspend fun HttpClient.loginResponse(userId: String, password: String): LoginResponse {
    val response = post("/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(userId = userId, password = password))
    }
    return response.body()
}

internal fun String.isJwtFormat(): Boolean {
    val parts = split('.')
    return parts.size == 3 && parts.all { it.isNotBlank() }
}

internal fun VersionListResponse.versionNames(): List<String> = versions.map { it.name }

internal fun FileListResponse.fileNames(): List<String> = files.map { it.name }

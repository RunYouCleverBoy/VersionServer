package com.playground.versionserver

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

internal fun ApplicationTestBuilder.installVersionServer(
    storageRoot: Path = createTempDirectory("vs-files"),
    jsonStorePath: Path = createTempFile("vs-store", ".json"),
    jwtSecret: String = "test-secret",
    jwtCertificate: X509Certificate? = null,
    jwtPrivateKey: RSAPrivateKey? = null,
) {
    application {
        module(
            AppConfig(
                storageRoot = storageRoot,
                jsonStorePath = jsonStorePath,
                jwtSecret = jwtSecret,
                jwtCertificate = jwtCertificate,
                jwtPrivateKey = jwtPrivateKey,
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
    jwtCertificate: X509Certificate? = null,
    jwtPrivateKey: RSAPrivateKey? = null,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    installVersionServer(storageRoot, jsonStorePath, jwtSecret, jwtCertificate, jwtPrivateKey)
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

internal suspend fun HttpClient.uploadFile(
    token: String,
    project: String,
    version: String,
    fileName: String,
    bytes: ByteArray,
) {
    submitFormWithBinaryData(
        url = "/projects/$project/versions/$version/files",
        formData = formData {
            append(
                "file",
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                },
            )
        },
    ) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

internal suspend fun HttpClient.grantAccess(token: String, project: String, userId: String) {
    post("/projects/$project/access") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(GrantAccessRequest(userId = userId))
    }
}

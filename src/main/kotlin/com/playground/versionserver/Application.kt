package com.playground.versionserver

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.readRemaining
import java.io.IOException
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val appLog = LoggerFactory.getLogger("com.playground.versionserver.Application")
private val uploadLog = LoggerFactory.getLogger("com.playground.versionserver.upload")
private val clientLog = LoggerFactory.getLogger("com.playground.versionserver.client")

fun Application.module(
    config: AppConfig,
    fileStore: FileStore = FilesystemFileStore(config.storageRoot),
) {
    val database = JsonDatabase(config.jsonStorePath)
    syncArtifactsFromDisk(database, fileStore)
    val tokens = JwtTokenService(
        secret = config.jwtSecret,
        issuer = config.jwtIssuer,
        audience = config.jwtAudience,
        validityMs = config.jwtValidityMs,
    )
    val cookieName = config.jwtCookieName

    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()?.value
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }

    install(StatusPages) {
        exception<IOException> { call, cause ->
            if (cause.isMultipartSizeLimit()) {
                appLog.warn(
                    "multipart too large {} {} : {}",
                    call.request.httpMethod.value,
                    call.request.path(),
                    cause.message,
                )
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@exception
            }
            appLog.error(
                "unhandled {} {} : {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause.toString(),
                cause,
            )
            call.respond(HttpStatusCode.InternalServerError)
        }
        exception<Throwable> { call, cause ->
            appLog.error(
                "unhandled {} {} : {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause.toString(),
                cause,
            )
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    install(ContentNegotiation) {
        json()
    }

    routing {
        post("/client-log") {
            val body = call.receiveText().take(8_192)
            clientLog.info(body)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/") {
            call.respondRedirect("/ui/")
        }

        get("/ui") {
            call.respondRedirect("/ui/")
        }

        staticResources("/ui", "ui") {
            default("index.html")
        }

        documentationRoutes()

        post("/login") {
            val request = call.receive<LoginRequest>()
            val user = database.authenticate(request.userId, request.password)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val jwt = tokens.issue(user.id, user.role)
            call.response.cookies.append(
                name = cookieName,
                value = jwt,
                maxAge = (config.jwtValidityMs / 1000L).coerceAtLeast(1L),
                path = "/",
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            )
            call.respond(
                LoginResponse(
                    token = jwt,
                    userId = user.id,
                    role = user.role,
                ),
            )
        }

        post("/logout") {
            call.response.cookies.append(
                name = cookieName,
                value = "",
                maxAge = 0L,
                expires = GMTDate.START,
                path = "/",
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        post("/password") {
            val user = call.requireUser(tokens, database, cookieName) ?: return@post
            val request = call.receive<ChangePasswordRequest>()
            when (database.changePassword(user.id, request.currentPassword, request.newPassword)) {
                null -> {
                    appLog.warn("password change rejected: empty new password userId={}", user.id)
                    call.respond(HttpStatusCode.BadRequest)
                }
                false -> {
                    appLog.warn("password change rejected: wrong current password userId={}", user.id)
                    call.respond(HttpStatusCode.Unauthorized)
                }
                true -> {
                    appLog.info("password change ok userId={}", user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        get("/projects") {
            val user = call.requireUser(tokens, database, cookieName) ?: return@get
            call.respond(
                ProjectListResponse(
                    userId = user.id,
                    role = user.role,
                    projects = database.projectsFor(user),
                ),
            )
        }

        get("/users") {
            call.requireAdmin(tokens, database, cookieName) ?: return@get
            call.respond(
                UserListResponse(
                    users = database.users().map { UserSummary(userId = it.id, role = it.role) },
                ),
            )
        }

        post("/users") {
            call.requireAdmin(tokens, database, cookieName) ?: return@post
            val request = call.receive<EnrollUserRequest>()
            if (request.userId.isBlank() || request.password.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (database.user(request.userId) != null) {
                call.respond(HttpStatusCode.Conflict)
                return@post
            }
            val created = database.addUser(request.userId, request.password, request.role)
            if (created == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.respond(
                HttpStatusCode.Created,
                EnrollUserResponse(userId = created.id, role = created.role),
            )
        }

        post("/projects/{project}/access") {
            call.requireAdmin(tokens, database, cookieName) ?: return@post
            val project = call.parameters["project"]
            if (project.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val request = call.receive<GrantAccessRequest>()
            if (request.userId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (!database.addGrant(request.userId, project)) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/projects/{project}/versions") {
            val project = call.parameters["project"]
            if (project.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, cookieName, project) ?: return@get
            call.respond(VersionListResponse(versions = database.versionsFor(project)))
        }


        post("/projects/{project}/versions/{version}/files") {
            call.requireAdmin(tokens, database, cookieName) ?: return@post
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                uploadLog.warn("upload rejected: blank project/version project={} version={}", project, version)
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            var fileName: String? = null
            var bytes: ByteArray? = null
            var partCount = 0
            try {
                call.receiveMultipart(formFieldLimit = config.multipartFormFieldLimitBytes).forEachPart { part ->
                    partCount += 1
                    when (part) {
                        is PartData.FileItem -> {
                            uploadLog.info(
                                "upload part file project={} version={} partName={} originalFileName={} contentType={} disposition={}",
                                project,
                                version,
                                part.name,
                                part.originalFileName,
                                part.contentType,
                                part.headers["Content-Disposition"],
                            )
                            fileName = part.originalFileName
                            bytes = part.provider().readRemaining().readByteArray()
                        }
                        is PartData.FormItem -> {
                            uploadLog.info(
                                "upload part form project={} version={} partName={} value={}",
                                project,
                                version,
                                part.name,
                                part.value.take(256),
                            )
                        }
                        else -> {
                            uploadLog.info(
                                "upload part other project={} version={} type={} partName={}",
                                project,
                                version,
                                part::class.simpleName,
                                part.name,
                            )
                        }
                    }
                    part.release()
                }
            } catch (error: IOException) {
                if (error.isMultipartSizeLimit()) {
                    uploadLog.warn(
                        "upload rejected: multipart limit project={} version={} limitBytes={} error={}",
                        project,
                        version,
                        config.multipartFormFieldLimitBytes,
                        error.message,
                    )
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@post
                }
                throw error
            }
            val storedName = fileName
            val storedBytes = bytes
            if (storedName.isNullOrBlank() || storedBytes == null) {
                uploadLog.warn(
                    "upload rejected: missing file part project={} version={} parts={} fileName={}",
                    project,
                    version,
                    partCount,
                    storedName,
                )
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (!fileStore.store(project, version, storedName, storedBytes)) {
                uploadLog.warn(
                    "upload rejected: store failed project={} version={} fileName={} bytes={}",
                    project,
                    version,
                    storedName,
                    storedBytes.size,
                )
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            database.addArtifact(project, version, storedName)
            uploadLog.info(
                "upload ok project={} version={} fileName={} bytes={}",
                project,
                version,
                storedName,
                storedBytes.size,
            )
            call.respond(HttpStatusCode.Created)
        }

        get("/projects/{project}/versions/{version}/files") {
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, cookieName, project) ?: return@get
            val meta = database.artifacts(project, version).associateBy { it.fileName }
            call.respond(
                FileListResponse(
                    files = fileStore.list(project, version)
                        .sorted()
                        .map { name ->
                            FileSummary(
                                name = name,
                                uploadedAt = meta[name]?.uploadedAt ?: Instant.EPOCH.toString(),
                            )
                        },
                ),
            )
        }

        get("/projects/{project}/versions/{version}/archive") {
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, cookieName, project) ?: return@get
            val prefix = normalizeArchivePrefix(call.request.queryParameters["prefix"])
            if (prefix == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val files = fileStore.list(project, version)
                .filter { matchesArchivePrefix(it, prefix) }
                .sorted()
            if (files.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val downloadName = archiveDownloadName(version, prefix)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, downloadName)
                    .toString(),
            )
            call.respondOutputStream(ContentType.Application.Zip) {
                ZipOutputStream(this).use { zip ->
                    for (fileName in files) {
                        val bytes = fileStore.read(project, version, fileName) ?: continue
                        val entryName = archiveEntryName(fileName, prefix)
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        }

        get("/projects/{project}/versions/{version}/files/{fileName...}") {
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            val fileName = call.parameters.getAll("fileName")?.joinToString("/")
            if (project.isNullOrBlank() || version.isNullOrBlank() || fileName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, cookieName, project) ?: return@get
            val bytes = fileStore.read(project, version, fileName)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        delete("/projects/{project}/versions/{version}/files/{fileName...}") {
            call.requireAdmin(tokens, database, cookieName) ?: return@delete
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            val fileName = call.parameters.getAll("fileName")?.joinToString("/")
            if (project.isNullOrBlank() || version.isNullOrBlank() || fileName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            fileStore.delete(project, version, fileName)
            database.removeArtifact(project, version, fileName)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/projects/{project}/versions/{version}") {
            call.requireAdmin(tokens, database, cookieName) ?: return@delete
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            fileStore.deleteVersion(project, version)
            database.removeVersion(project, version)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun IOException.isMultipartSizeLimit(): Boolean =
    message?.contains("Limit of") == true && message?.contains("bytes exceeded") == true

internal fun normalizeArchivePrefix(raw: String?): String? {
    if (raw.isNullOrBlank()) return ""
    val normalized = raw.replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return ""
    val segments = normalized.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return normalized
}

internal fun matchesArchivePrefix(fileName: String, prefix: String): Boolean {
    if (prefix.isEmpty()) return true
    return fileName == prefix || fileName.startsWith("$prefix/")
}

internal fun archiveEntryName(fileName: String, prefix: String): String {
    if (prefix.isEmpty()) return fileName
    if (fileName == prefix) return fileName.substringAfterLast('/')
    return fileName.removePrefix("$prefix/")
}

internal fun archiveDownloadName(version: String, prefix: String): String {
    if (prefix.isEmpty()) return "$version.zip"
    val leaf = prefix.substringAfterLast('/')
    return "$leaf.zip"
}

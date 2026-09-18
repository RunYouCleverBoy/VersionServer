package com.playground.versionserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

fun Application.module(
    config: AppConfig,
    fileStore: FileStore = FilesystemFileStore(config.storageRoot),
) {
    val database = JsonDatabase(config.jsonStorePath)
    val tokens = TokenService()

    install(ContentNegotiation) {
        json()
    }

    routing {
        post("/login") {
            val request = call.receive<LoginRequest>()
            val user = database.authenticate(request.userId, request.password)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            call.respond(LoginResponse(token = tokens.issue(user.id)))
        }

        get("/projects") {
            val user = call.requireUser(tokens, database) ?: return@get
            call.respond(ProjectListResponse(projects = database.projectsFor(user)))
        }

        post("/projects/{project}/access") {
            call.requireAdmin(tokens, database) ?: return@post
            val project = call.parameters["project"]
            if (project.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val request = call.receive<GrantAccessRequest>()
            database.addGrant(request.userId, project)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/projects/{project}/versions") {
            val project = call.parameters["project"]
            if (project.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, project) ?: return@get
            call.respond(VersionListResponse(versions = database.versionsFor(project)))
        }

        post("/projects/{project}/versions/{version}/files") {
            call.requireAdmin(tokens, database) ?: return@post
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            var fileName: String? = null
            var bytes: ByteArray? = null
            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FileItem) {
                    fileName = part.originalFileName
                    bytes = part.provider().readRemaining().readByteArray()
                }
                part.release()
            }
            val storedName = fileName
            val storedBytes = bytes
            if (storedName.isNullOrBlank() || storedBytes == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (!fileStore.store(project, version, storedName, storedBytes)) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            database.addArtifact(project, version, storedName)
            call.respond(HttpStatusCode.Created)
        }

        get("/projects/{project}/versions/{version}/files") {
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            if (project.isNullOrBlank() || version.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, project) ?: return@get
            call.respond(
                FileListResponse(
                    files = fileStore.list(project, version),
                ),
            )
        }

        get("/projects/{project}/versions/{version}/files/{fileName}") {
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            val fileName = call.parameters["fileName"]
            if (project.isNullOrBlank() || version.isNullOrBlank() || fileName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.requireProjectAccess(tokens, database, project) ?: return@get
            val bytes = fileStore.read(project, version, fileName)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        delete("/projects/{project}/versions/{version}/files/{fileName}") {
            call.requireAdmin(tokens, database) ?: return@delete
            val project = call.parameters["project"]
            val version = call.parameters["version"]
            val fileName = call.parameters["fileName"]
            if (project.isNullOrBlank() || version.isNullOrBlank() || fileName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            fileStore.delete(project, version, fileName)
            database.removeArtifact(project, version, fileName)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

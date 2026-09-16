package com.playground.versionserver

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond

suspend fun ApplicationCall.requireUser(
    tokens: TokenService,
    database: JsonDatabase,
): PersistedUser? {
    val header = request.header(HttpHeaders.Authorization)
    val token = header
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.ifBlank { null }
    if (token == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val userId = tokens.userIdFor(token)
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val user = database.user(userId)
    if (user == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}

suspend fun ApplicationCall.requireAdmin(
    tokens: TokenService,
    database: JsonDatabase,
): PersistedUser? {
    val user = requireUser(tokens, database) ?: return null
    if (user.role != Role.Admin) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return user
}

suspend fun ApplicationCall.requireProjectAccess(
    tokens: TokenService,
    database: JsonDatabase,
    project: String,
): PersistedUser? {
    val user = requireUser(tokens, database) ?: return null
    if (!database.isAuthorized(user, project)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return user
}

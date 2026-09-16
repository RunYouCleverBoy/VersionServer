package com.playground.versionserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class PersistedUser(
    val id: String,
    val password: String,
    val role: Role,
)

@Serializable
data class PersistedGrant(
    val userId: String,
    val project: String,
)

@Serializable
data class PersistedArtifact(
    val project: String,
    val version: String,
    val fileName: String,
)

@Serializable
data class PersistedState(
    val users: List<PersistedUser> = emptyList(),
    val grants: List<PersistedGrant> = emptyList(),
    val artifacts: List<PersistedArtifact> = emptyList(),
)

class JsonDatabase(private val path: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var state: PersistedState = loadOrSeed()

    fun authenticate(userId: String, password: String): PersistedUser? =
        state.users.firstOrNull { it.id == userId && it.password == password }

    fun user(userId: String): PersistedUser? =
        state.users.firstOrNull { it.id == userId }

    fun addGrant(userId: String, project: String) {
        if (state.grants.any { it.userId == userId && it.project == project }) return
        state = state.copy(grants = state.grants + PersistedGrant(userId, project))
        persist()
    }

    fun projectsFor(userId: String): List<String> =
        state.grants.filter { it.userId == userId }.map { it.project }.distinct()

    fun isAuthorized(userId: String, project: String): Boolean =
        state.grants.any { it.userId == userId && it.project == project }

    fun addArtifact(project: String, version: String, fileName: String) {
        if (state.artifacts.any { it.project == project && it.version == version && it.fileName == fileName }) return
        state = state.copy(
            artifacts = state.artifacts + PersistedArtifact(project, version, fileName),
        )
        persist()
    }

    fun versionsFor(project: String): List<String> =
        state.artifacts.filter { it.project == project }.map { it.version }.distinct()

    fun artifacts(project: String, version: String): List<PersistedArtifact> =
        state.artifacts.filter { it.project == project && it.version == version }

    fun findArtifact(project: String, version: String, fileName: String): PersistedArtifact? =
        state.artifacts.firstOrNull { it.project == project && it.version == version && it.fileName == fileName }

    fun removeArtifact(project: String, version: String, fileName: String): PersistedArtifact? {
        val existing = findArtifact(project, version, fileName) ?: return null
        state = state.copy(artifacts = state.artifacts - existing)
        persist()
        return existing
    }

    private fun loadOrSeed(): PersistedState {
        val text = if (path.exists()) path.readText() else ""
        val loaded = if (text.isBlank()) {
            PersistedState()
        } else {
            json.decodeFromString<PersistedState>(text)
        }
        if (loaded.users.isNotEmpty()) return loaded
        val seeded = PersistedState(users = defaultUsers)
        persist(seeded)
        return seeded
    }

    private fun persist(next: PersistedState = state) {
        path.createParentDirectories()
        path.writeText(json.encodeToString(PersistedState.serializer(), next))
    }

    companion object {
        val defaultUsers = listOf(
            PersistedUser(id = "admin", password = "admin-pass", role = Role.Admin),
            PersistedUser(id = "alice", password = "alice-pass", role = Role.Client),
            PersistedUser(id = "bob", password = "bob-pass", role = Role.Client),
        )
    }
}

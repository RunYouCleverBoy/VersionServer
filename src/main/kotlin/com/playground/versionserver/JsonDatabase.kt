package com.playground.versionserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class PersistedUser(
    val id: String,
    val passwordHash: String,
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
    val uploadedAt: String = Instant.EPOCH.toString(),
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

    fun authenticate(userId: String, password: String): PersistedUser? {
        val hash = sha256Hex(password)
        return state.users.firstOrNull { it.id == userId && it.passwordHash == hash }
    }

    fun user(userId: String): PersistedUser? =
        state.users.firstOrNull { it.id == userId }

    fun users(): List<PersistedUser> = state.users

    fun addUser(userId: String, password: String, role: Role): PersistedUser? {
        if (userId.isBlank() || password.isEmpty()) return null
        if (state.users.any { it.id == userId }) return null
        val created = PersistedUser(id = userId, passwordHash = sha256Hex(password), role = role)
        state = state.copy(users = state.users + created)
        persist()
        return created
    }

    fun changePassword(userId: String, currentPassword: String, newPassword: String): Boolean? {
        if (newPassword.isEmpty()) return null
        val existing = authenticate(userId, currentPassword) ?: return false
        state = state.copy(
            users = state.users.map { user ->
                if (user.id == existing.id) user.copy(passwordHash = sha256Hex(newPassword)) else user
            },
        )
        persist()
        return true
    }

    fun addGrant(userId: String, project: String): Boolean {
        if (user(userId) == null) return false
        if (state.grants.any { it.userId == userId && it.project == project }) return true
        state = state.copy(grants = state.grants + PersistedGrant(userId, project))
        persist()
        return true
    }

    fun projectsFor(user: PersistedUser): List<String> {
        if (user.role == Role.Admin) return allProjects()
        return state.grants.filter { it.userId == user.id }.map { it.project }.distinct()
    }

    fun isAuthorized(user: PersistedUser, project: String): Boolean {
        if (user.role == Role.Admin) return true
        return state.grants.any { it.userId == user.id && it.project == project }
    }

    private fun allProjects(): List<String> =
        (state.grants.map { it.project } + state.artifacts.map { it.project }).distinct()

    fun addArtifact(
        project: String,
        version: String,
        fileName: String,
        uploadedAt: Instant = Instant.now(),
    ) {
        val stamp = uploadedAt.toString()
        val existing = findArtifact(project, version, fileName)
        val artifact = PersistedArtifact(
            project = project,
            version = version,
            fileName = fileName,
            uploadedAt = stamp,
        )
        state = state.copy(
            artifacts = if (existing == null) {
                state.artifacts + artifact
            } else {
                state.artifacts.map { if (it == existing) artifact else it }
            },
        )
        persist()
    }

    fun versionsFor(project: String): List<VersionSummary> {
        val byVersion = state.artifacts
            .filter { it.project == project }
            .groupBy { it.version }
        if (byVersion.isEmpty()) return emptyList()
        val latestName = byVersion.maxWith(
            compareBy<Map.Entry<String, List<PersistedArtifact>>> { (_, artifacts) ->
                artifacts.maxOf { Instant.parse(it.uploadedAt) }
            }.thenBy { it.key },
        ).key
        return byVersion.keys.sorted().map { name ->
            VersionSummary(name = name, latest = name == latestName)
        }
    }

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

    fun removeVersion(project: String, version: String) {
        val remaining = state.artifacts.filterNot { it.project == project && it.version == version }
        if (remaining.size == state.artifacts.size) return
        state = state.copy(artifacts = remaining)
        persist()
    }

    private fun loadOrSeed(): PersistedState {
        if (!path.exists()) {
            return writeDefaults()
        }
        val text = path.readText()
        if (text.isBlank()) {
            return writeDefaults()
        }
        val loaded = json.decodeFromString(PersistedState.serializer(), text)
        if (loaded.users.isEmpty()) {
            return writeDefaults()
        }
        // Rewrite older stores that omit artifact uploadedAt so the field is persisted.
        if (loaded.artifacts.isNotEmpty() && !text.contains("uploadedAt")) {
            val base = Instant.now()
            val withTimestamps = loaded.copy(
                artifacts = loaded.artifacts.mapIndexed { index, artifact ->
                    artifact.copy(uploadedAt = base.plusSeconds(index.toLong()).toString())
                },
            )
            persist(withTimestamps)
            return withTimestamps
        }
        return loaded
    }

    private fun writeDefaults(): PersistedState {
        val seeded = PersistedState(
            users = defaultUsers,
            grants = emptyList(),
            artifacts = emptyList(),
        )
        persist(seeded)
        return seeded
    }

    private fun persist(next: PersistedState = state) {
        path.createParentDirectories()
        path.writeText(json.encodeToString(PersistedState.serializer(), next))
        state = next
    }

    companion object {
        val defaultUsers = listOf(
            PersistedUser(id = "admin", passwordHash = sha256Hex("admin-pass"), role = Role.Admin),
            PersistedUser(id = "alice", passwordHash = sha256Hex("alice-pass"), role = Role.Client),
            PersistedUser(id = "bob", passwordHash = sha256Hex("bob-pass"), role = Role.Client),
        )
    }
}

package com.playground.versionserver

import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    Admin,
    Client,
}

@Serializable
data class LoginRequest(
    val userId: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
)

@Serializable
data class GrantAccessRequest(
    val userId: String,
)

@Serializable
data class ProjectListResponse(
    val projects: List<String>,
)

@Serializable
data class VersionListResponse(
    val versions: List<String>,
)

@Serializable
data class FileListResponse(
    val files: List<String>,
)

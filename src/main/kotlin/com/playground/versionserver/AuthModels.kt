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
    val userId: String,
    val role: Role,
)

@Serializable
data class GrantAccessRequest(
    val userId: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class EnrollUserRequest(
    val userId: String,
    val password: String,
    val role: Role,
)

@Serializable
data class EnrollUserResponse(
    val userId: String,
    val role: Role,
)

@Serializable
data class UserSummary(
    val userId: String,
    val role: Role,
)

@Serializable
data class UserListResponse(
    val users: List<UserSummary>,
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

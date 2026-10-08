package com.playground.versionserver

import java.nio.file.Path

data class AppConfig(
    val storageRoot: Path,
    val jsonStorePath: Path,
    val jwtSecret: String = "dev-only-change-me",
    val jwtIssuer: String = "version-server",
    val jwtAudience: String = "version-server",
    val jwtCookieName: String = "VS_TOKEN",
    val jwtValidityMs: Long = 24L * 60L * 60L * 1000L,
)

package com.playground.versionserver

import java.nio.file.Path
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey

data class AppConfig(
    val storageRoot: Path,
    val jsonStorePath: Path,
    val jwtSecret: String = "dev-only-change-me",
    val jwtCertificate: X509Certificate? = null,
    val jwtPrivateKey: RSAPrivateKey? = null,
    val jwtIssuer: String = "version-server",
    val jwtAudience: String = "version-server",
    val jwtCookieName: String = "VS_TOKEN",
    val jwtValidityMs: Long = 24L * 60L * 60L * 1000L,
    /** Max bytes per multipart file/form field (Ktor default is 50 MiB). */
    val multipartFormFieldLimitBytes: Long = 1024L * 1024L * 1024L,
)

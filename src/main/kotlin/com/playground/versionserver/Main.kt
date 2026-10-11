package com.playground.versionserver

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

fun main() {
    val logDir = Path.of(System.getenv("VERSION_SERVER_LOG_DIR") ?: "logs")
    Files.createDirectories(logDir)
    val log = LoggerFactory.getLogger("com.playground.versionserver.Main")
    val config = AppConfig(
        storageRoot = Path.of(System.getenv("VERSION_SERVER_STORAGE") ?: "data/files"),
        jsonStorePath = Path.of(System.getenv("VERSION_SERVER_JSON") ?: "data/store.json"),
        jwtSecret = System.getenv("VERSION_SERVER_JWT_SECRET") ?: "dev-only-change-me",
        multipartFormFieldLimitBytes = System.getenv("VERSION_SERVER_UPLOAD_MAX_BYTES")
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: (1024L * 1024L * 1024L),
    )
    log.info(
        "starting VersionServer storage={} json={} logs={} uploadMaxBytes={}",
        config.storageRoot.toAbsolutePath(),
        config.jsonStorePath.toAbsolutePath(),
        logDir.toAbsolutePath(),
        config.multipartFormFieldLimitBytes,
    )
    embeddedServer(Netty, port = 8080) {
        module(config)
    }.start(wait = true)
}

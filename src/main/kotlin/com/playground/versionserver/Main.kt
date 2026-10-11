package com.playground.versionserver

import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
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
    val tls = when (val loaded = loadServerTls(System::getenv)) {
        ServerTlsLoad.Missing -> {
            log.warn(TlsGuidance.missing)
            null
        }
        is ServerTlsLoad.Invalid -> {
            log.error(TlsGuidance.invalid(loaded.detail))
            exitProcess(1)
        }
        is ServerTlsLoad.Ready -> loaded.tls
    }
    log.info(
        "starting VersionServer storage={} json={} logs={} uploadMaxBytes={} tls={}",
        config.storageRoot.toAbsolutePath(),
        config.jsonStorePath.toAbsolutePath(),
        logDir.toAbsolutePath(),
        config.multipartFormFieldLimitBytes,
        tls?.description ?: "off",
    )
    embeddedServer(Netty, configure = {
        connector {
            port = 8080
            host = "0.0.0.0"
        }
        if (tls != null) {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.keyAlias,
                keyStorePassword = { tls.password },
                privateKeyPassword = { tls.password },
            ) {
                port = tls.httpsPort
                host = "0.0.0.0"
            }
        }
    }) {
        module(config)
    }.start(wait = true)
}

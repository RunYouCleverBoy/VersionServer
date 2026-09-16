package com.playground.versionserver

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

fun main() {
    val config = AppConfig(
        storageRoot = Path.of(System.getenv("VERSION_SERVER_STORAGE") ?: "data/files"),
        jsonStorePath = Path.of(System.getenv("VERSION_SERVER_JSON") ?: "data/store.json"),
    )
    embeddedServer(Netty, port = 8080) {
        module(config)
    }.start(wait = true)
}

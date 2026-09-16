package com.playground.versionserver

import java.nio.file.Path

data class AppConfig(
    val storageRoot: Path,
    val jsonStorePath: Path,
)

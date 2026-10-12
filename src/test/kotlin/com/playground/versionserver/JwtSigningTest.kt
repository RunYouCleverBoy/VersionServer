package com.playground.versionserver

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JwtSigningTest {
    @Test
    fun `unset certificate keeps HMAC signing`() {
        assertIs<JwtSigningLoad.Hmac>(loadJwtSigning { null })
    }

    @Test
    fun `certificate without a key is rejected`() {
        val loaded = loadJwtSigning { name ->
            if (name == "VERSION_SERVER_JWT_CERT") "certs/jwt.crt" else null
        }
        val invalid = assertIs<JwtSigningLoad.Invalid>(loaded)
        assertTrue(invalid.detail.contains("VERSION_SERVER_JWT_KEY"))
    }

    @Test
    fun `private key must match the certificate`() {
        val dir = createTempDirectory("vs-jwt")
        val certificate = dir.resolve("jwt.crt")
        val key = dir.resolve("jwt.key")
        val otherKey = dir.resolve("other.key")
        openssl(dir, certificate, key)
        openssl(dir, dir.resolve("other.crt"), otherKey)
        val loaded = loadJwtSigning { name ->
            when (name) {
                "VERSION_SERVER_JWT_CERT" -> certificate.toString()
                "VERSION_SERVER_JWT_KEY" -> otherKey.toString()
                else -> null
            }
        }
        val invalid = assertIs<JwtSigningLoad.Invalid>(loaded)
        assertTrue(invalid.detail.contains("does not match"))
    }

    private fun openssl(directory: Path, certificate: Path, key: Path) {
        val process = ProcessBuilder(
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
            "-keyout", key.toString(), "-out", certificate.toString(),
            "-subj", "/CN=version-server-jwt",
        )
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        val code = process.waitFor()
        if (code != 0) {
            error("openssl failed: $output")
        }
    }
}

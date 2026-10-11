package com.playground.versionserver

import java.nio.file.Path
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerTlsTest {
    @Test
    fun `missing certificate is reported with setup guidance`() {
        val loaded = loadServerTls { null }
        assertIs<ServerTlsLoad.Missing>(loaded)
        assertTrue(TlsGuidance.missing.contains("openssl req"))
        assertTrue(TlsGuidance.missing.contains("Do not commit"))
        assertTrue(TlsGuidance.missing.contains("Docker image"))
        assertTrue(TlsGuidance.missing.contains("VERSION_SERVER_TLS_CERT"))
        assertTrue(TlsGuidance.missing.contains("VERSION_SERVER_TLS_KEY"))
        assertTrue(TlsGuidance.missing.contains("VERSION_SERVER_TLS_DIR"))
    }

    @Test
    fun `pem paths and inlined pem both load the same certificate`() {
        val dir = createTempDirectory("vs-tls")
        val certificate = dir.resolve("server.crt")
        val key = dir.resolve("server.key")
        openssl(
            dir,
            "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
            "-keyout", key.toString(), "-out", certificate.toString(),
            "-subj", "/CN=localhost",
        )

        val fromPaths = ready(
            "VERSION_SERVER_TLS_CERT" to certificate.toString(),
            "VERSION_SERVER_TLS_KEY" to key.toString(),
        )
        assertTrue(fromPaths.description.contains(certificate.toString()))
        assertEquals("localhost", subjectCn(fromPaths))

        val fromText = ready(
            "VERSION_SERVER_TLS_CERT" to certificate.readText(),
            "VERSION_SERVER_TLS_KEY" to key.readText(),
            "VERSION_SERVER_HTTPS_PORT" to "9443",
        )
        assertEquals("inlined PEM certificate", fromText.description)
        assertEquals(9443, fromText.httpsPort)
        assertEquals(subjectCn(fromPaths), subjectCn(fromText))
    }

    @Test
    fun `pkcs1 private key is accepted`() {
        val dir = createTempDirectory("vs-tls-pkcs1")
        val certificate = dir.resolve("server.crt")
        val key = dir.resolve("server.key")
        openssl(dir, "genrsa", "-out", key.toString(), "2048")
        openssl(
            dir,
            "req", "-new", "-x509", "-key", key.toString(), "-out", certificate.toString(),
            "-days", "1", "-subj", "/CN=localhost",
        )
        val loaded = ready(
            "VERSION_SERVER_TLS_CERT" to certificate.toString(),
            "VERSION_SERVER_TLS_KEY" to key.toString(),
        )
        assertEquals("localhost", subjectCn(loaded))
    }

    @Test
    fun `keystore path is accepted as the certificate or as VERSION_SERVER_TLS_KEYSTORE`() {
        val dir = createTempDirectory("vs-tls-p12")
        val certificate = dir.resolve("server.crt")
        val key = dir.resolve("server.key")
        val store = dir.resolve("server.p12")
        openssl(
            dir,
            "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
            "-keyout", key.toString(), "-out", certificate.toString(),
            "-subj", "/CN=localhost",
        )
        openssl(
            dir,
            "pkcs12", "-export",
            "-inkey", key.toString(),
            "-in", certificate.toString(),
            "-out", store.toString(),
            "-passout", "pass:test-pass",
            "-name", "version-server",
        )

        val fromCertPath = ready(
            "VERSION_SERVER_TLS_CERT" to store.toString(),
            "VERSION_SERVER_TLS_KEYSTORE_PASSWORD" to "test-pass",
        )
        assertEquals("keystore ${store}", fromCertPath.description)

        val fromKeystore = ready(
            "VERSION_SERVER_TLS_KEYSTORE" to store.toString(),
            "VERSION_SERVER_TLS_KEYSTORE_PASSWORD" to "test-pass",
            "VERSION_SERVER_TLS_KEY_ALIAS" to "version-server",
        )
        assertEquals("localhost", subjectCn(fromKeystore))
    }

    @Test
    fun `a certificate without a key is rejected`() {
        val loaded = loadServerTls { name ->
            if (name == "VERSION_SERVER_TLS_CERT") "certs/server.crt" else null
        }
        val invalid = assertIs<ServerTlsLoad.Invalid>(loaded)
        assertTrue(invalid.detail.contains("VERSION_SERVER_TLS_KEY"))
        assertTrue(TlsGuidance.invalid(invalid.detail).contains("openssl req"))
    }

    @Test
    fun `a missing certificate file is rejected`() {
        val loaded = loadServerTls { name ->
            when (name) {
                "VERSION_SERVER_TLS_CERT" -> "/tmp/version-server-missing.crt"
                "VERSION_SERVER_TLS_KEY" -> "/tmp/version-server-missing.key"
                else -> null
            }
        }
        val invalid = assertIs<ServerTlsLoad.Invalid>(loaded)
        assertTrue(invalid.detail.contains("not found"))
    }

    @Test
    fun `https port cannot reuse the http port`() {
        val dir = createTempDirectory("vs-tls-port")
        val certificate = dir.resolve("server.crt")
        val key = dir.resolve("server.key")
        openssl(
            dir,
            "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
            "-keyout", key.toString(), "-out", certificate.toString(),
            "-subj", "/CN=localhost",
        )
        val loaded = loadServerTls { name ->
            when (name) {
                "VERSION_SERVER_TLS_CERT" -> certificate.toString()
                "VERSION_SERVER_TLS_KEY" -> key.toString()
                "VERSION_SERVER_HTTPS_PORT" -> "8080"
                else -> null
            }
        }
        assertIs<ServerTlsLoad.Invalid>(loaded)
    }

    private fun ready(vararg entries: Pair<String, String>): ServerTls {
        val values = entries.toMap()
        val loaded = loadServerTls { name -> values[name] }
        assertIs<ServerTlsLoad.Ready>(loaded)
        return loaded.tls
    }

    private fun subjectCn(tls: ServerTls): String {
        val certificate = tls.keyStore.getCertificate(tls.keyAlias) as X509Certificate
        return certificate.subjectX500Principal.name.substringAfter("CN=")
    }

    private fun openssl(directory: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("openssl") + args)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        val code = process.waitFor()
        if (code != 0) {
            error("openssl ${args.joinToString(" ")} failed: $output")
        }
    }
}

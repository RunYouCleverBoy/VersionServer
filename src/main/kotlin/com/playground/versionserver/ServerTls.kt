package com.playground.versionserver

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.path.isRegularFile

private const val httpPort = 8080
private const val defaultHttpsPort = 8443
private const val keyAliasDefault = "version-server"

class ServerTls(
    val keyStore: KeyStore,
    val keyAlias: String,
    val password: CharArray,
    val httpsPort: Int,
    val description: String,
)

sealed class ServerTlsLoad {
    data object Missing : ServerTlsLoad()
    data class Invalid(val detail: String) : ServerTlsLoad()
    data class Ready(val tls: ServerTls) : ServerTlsLoad()
}

object TlsGuidance {
    val howToCreate: String = """
Create the certificate yourself. Do not commit it, and do not copy it into the Docker image.

Create a key and certificate:

  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
    -keyout server.key -out server.crt \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

Point the server at those files, or paste the PEM text. A value containing -----BEGIN is the certificate itself:

  VERSION_SERVER_TLS_CERT=/absolute/path/server.crt
  VERSION_SERVER_TLS_KEY=/absolute/path/server.key

A PKCS#12 or JKS file can be given as a single path:

  VERSION_SERVER_TLS_CERT=/absolute/path/server.p12
  VERSION_SERVER_TLS_KEYSTORE_PASSWORD=your-password

In Docker, keep the files on the host and mount that directory. Paths under VERSION_SERVER_TLS_DIR are exposed in the container as /certs/...:

  VERSION_SERVER_TLS_DIR=/absolute/host/path/to/certs
  VERSION_SERVER_TLS_CERT=/certs/server.crt
  VERSION_SERVER_TLS_KEY=/certs/server.key

HTTPS listens on port 8443 (VERSION_SERVER_HTTPS_PORT) after a certificate is configured.
Keep the private key readable only by the account that runs the server.
""".trimIndent()

    val missing: String =
        "TLS certificate is not configured. Version Server is serving HTTP only, on port $httpPort.\n\n$howToCreate"

    fun invalid(detail: String): String = "$detail\n\n$howToCreate"
}

fun loadServerTls(env: (String) -> String?): ServerTlsLoad {
    val certValue = envValue(env, "VERSION_SERVER_TLS_CERT")
    val keyValue = envValue(env, "VERSION_SERVER_TLS_KEY")
    val keystoreValue = envValue(env, "VERSION_SERVER_TLS_KEYSTORE")
    if (certValue == null && keyValue == null && keystoreValue == null) {
        return ServerTlsLoad.Missing
    }
    val httpsPort = httpsPort(env) ?: return ServerTlsLoad.Invalid(
        "VERSION_SERVER_HTTPS_PORT must be a TCP port other than $httpPort.",
    )
    if (keystoreValue != null && (certValue != null || keyValue != null)) {
        return ServerTlsLoad.Invalid(
            "Set VERSION_SERVER_TLS_KEYSTORE or VERSION_SERVER_TLS_CERT, not both.",
        )
    }

    val keystorePath = keystoreValue ?: certValue?.takeIf { isKeyStorePath(it) }
    if (keystorePath != null) {
        if (keyValue != null) {
            return ServerTlsLoad.Invalid(
                "A PKCS#12 or JKS file does not use VERSION_SERVER_TLS_KEY. " +
                    "Set VERSION_SERVER_TLS_KEYSTORE_PASSWORD instead.",
            )
        }
        return loadKeyStoreTls(keystorePath, env, httpsPort)
    }

    if (certValue == null || keyValue == null) {
        return ServerTlsLoad.Invalid(
            "Set both VERSION_SERVER_TLS_CERT and VERSION_SERVER_TLS_KEY, " +
                "or set VERSION_SERVER_TLS_KEYSTORE.",
        )
    }

    val certificateText = readMaterial(certValue)
        ?: return ServerTlsLoad.Invalid("VERSION_SERVER_TLS_CERT was not found at $certValue.")
    val keyText = readMaterial(keyValue)
        ?: return ServerTlsLoad.Invalid("VERSION_SERVER_TLS_KEY was not found at $keyValue.")
    val keyPassword = (envValue(env, "VERSION_SERVER_TLS_KEY_PASSWORD") ?: "").toCharArray()
    val built = buildPemKeyStore(certificateText, keyText, keyPassword)
        ?: return ServerTlsLoad.Invalid(pemFailureDetail())
    return ServerTlsLoad.Ready(
        ServerTls(
            keyStore = built.store,
            keyAlias = keyAliasDefault,
            password = built.password,
            httpsPort = httpsPort,
            description = materialDescription(certValue, "certificate"),
        ),
    )
}

private class BuiltKeyStore(val store: KeyStore, val password: CharArray)

private fun loadKeyStoreTls(pathValue: String, env: (String) -> String?, httpsPort: Int): ServerTlsLoad {
    if (pathValue.contains("-----BEGIN")) {
        return ServerTlsLoad.Invalid(
            "VERSION_SERVER_TLS_KEYSTORE must be a file path, not PEM text.",
        )
    }
    val path = Path.of(pathValue)
    if (!path.isRegularFile()) {
        return ServerTlsLoad.Invalid("Keystore was not found at $pathValue.")
    }
    val password = (
        envValue(env, "VERSION_SERVER_TLS_KEYSTORE_PASSWORD")
            ?: envValue(env, "VERSION_SERVER_TLS_KEY_PASSWORD")
            ?: ""
        ).toCharArray()
    val store = try {
        openKeyStore(path, password)
    } catch (exception: GeneralSecurityException) {
        return ServerTlsLoad.Invalid("Could not open keystore $pathValue: ${exception.message}")
    } catch (exception: IOException) {
        return ServerTlsLoad.Invalid("Could not open keystore $pathValue: ${exception.message}")
    }
    val alias = envValue(env, "VERSION_SERVER_TLS_KEY_ALIAS") ?: firstKeyAlias(store)
        ?: return ServerTlsLoad.Invalid("Keystore $pathValue has no private key entry.")
    if (!store.isKeyEntry(alias)) {
        return ServerTlsLoad.Invalid("Keystore $pathValue has no private key named $alias.")
    }
    return ServerTlsLoad.Ready(
        ServerTls(
            keyStore = store,
            keyAlias = alias,
            password = password,
            httpsPort = httpsPort,
            description = "keystore $pathValue",
        ),
    )
}

private fun pemFailureDetail(): String =
    "Could not read the PEM certificate and private key. " +
        "Use an unencrypted PKCS#8 key (BEGIN PRIVATE KEY) or an RSA key (BEGIN RSA PRIVATE KEY). " +
        "The private key must match the first certificate in the file. " +
        "Encrypted keys need VERSION_SERVER_TLS_KEY_PASSWORD. " +
        "Convert a key with: openssl pkcs8 -topk8 -nocrypt -in server.key -out server.pkcs8.key"

private fun buildPemKeyStore(certificateText: String, keyText: String, keyPassword: CharArray): BuiltKeyStore? {
    val certificates = parseCertificates(certificateText) ?: return null
    val privateKey = parsePrivateKey(keyText, keyPassword) ?: return null
    val leaf = certificates.firstOrNull() ?: return null
    if (!privateKeyMatches(leaf, privateKey)) return null
    val password = randomPassword()
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, password)
    store.setKeyEntry(keyAliasDefault, privateKey, password, certificates.toTypedArray())
    return BuiltKeyStore(store, password)
}

private fun parseCertificates(text: String): List<X509Certificate>? {
    val blocks = pemBlocks(text).filter { it.type == "CERTIFICATE" }
    if (blocks.isEmpty()) return null
    val factory = CertificateFactory.getInstance("X.509")
    return blocks.map { block ->
        factory.generateCertificate(block.body.inputStream()) as? X509Certificate ?: return null
    }
}

private fun parsePrivateKey(text: String, password: CharArray): PrivateKey? {
    val block = pemBlocks(text).firstOrNull {
        it.type == "PRIVATE KEY" || it.type == "ENCRYPTED PRIVATE KEY" || it.type == "RSA PRIVATE KEY"
    } ?: return null
    val pkcs8 = when (block.type) {
        "PRIVATE KEY" -> block.body
        "RSA PRIVATE KEY" -> pkcs1ToPkcs8(block.body)
        "ENCRYPTED PRIVATE KEY" -> decryptPkcs8(block.body, password) ?: return null
        else -> return null
    }
    return generatePrivate(pkcs8)
}

private fun generatePrivate(pkcs8: ByteArray): PrivateKey? {
    val spec = PKCS8EncodedKeySpec(pkcs8)
    for (algorithm in listOf("RSA", "EC")) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(spec)
        } catch (_: GeneralSecurityException) {
            continue
        }
    }
    return null
}

private fun decryptPkcs8(encoded: ByteArray, password: CharArray): ByteArray? {
    if (password.isEmpty()) return null
    return try {
        val info = EncryptedPrivateKeyInfo(encoded)
        val secret = SecretKeyFactory.getInstance(info.algName)
            .generateSecret(PBEKeySpec(password))
        val cipher = Cipher.getInstance(info.algName)
        cipher.init(Cipher.DECRYPT_MODE, secret, info.algParameters)
        info.getKeySpec(cipher).encoded
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    }
}

private fun privateKeyMatches(certificate: X509Certificate, privateKey: PrivateKey): Boolean {
    val publicKey = certificate.publicKey
    if (privateKey is RSAPrivateCrtKey && publicKey is RSAPublicKey) {
        return privateKey.modulus == publicKey.modulus
    }
    return certificate.publicKey.algorithm == privateKey.algorithm
}

private fun openKeyStore(path: Path, password: CharArray): KeyStore {
    val type = when (path.fileName.toString().substringAfterLast('.').lowercase()) {
        "jks" -> "JKS"
        else -> "PKCS12"
    }
    val store = KeyStore.getInstance(type)
    Files.newInputStream(path).use { input -> store.load(input, password) }
    return store
}

private fun firstKeyAlias(store: KeyStore): String? =
    store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }

private fun httpsPort(env: (String) -> String?): Int? {
    val raw = envValue(env, "VERSION_SERVER_HTTPS_PORT") ?: return defaultHttpsPort
    val port = raw.toIntOrNull() ?: return null
    if (port !in 1..65535 || port == httpPort) return null
    return port
}

private fun envValue(env: (String) -> String?, name: String): String? =
    env(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun isKeyStorePath(value: String): Boolean {
    if (value.contains("-----BEGIN")) return false
    val name = Path.of(value).fileName.toString().lowercase()
    return name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks")
}

private fun readMaterial(value: String): String? {
    if (value.contains("-----BEGIN")) return value
    val path = Path.of(value)
    if (!path.isRegularFile()) return null
    return try {
        Files.readString(path)
    } catch (_: IOException) {
        null
    }
}

private fun materialDescription(value: String, kind: String): String =
    if (value.contains("-----BEGIN")) "inlined PEM $kind" else "$kind path $value"

private fun pemBlocks(text: String): List<PemBlock> {
    val normalized = text.replace("\r\n", "\n")
    val pattern = Regex("-----BEGIN ([A-Z0-9 ]+)-----\n([A-Za-z0-9+/=\n]+)-----END \\1-----")
    return pattern.findAll(normalized).map { match ->
        PemBlock(
            type = match.groupValues[1],
            body = Base64.getMimeDecoder().decode(match.groupValues[2]),
        )
    }.toList()
}

private class PemBlock(val type: String, val body: ByteArray)

private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
    val algorithm = byteArrayOf(
        0x06, 0x09,
        0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
    )
    val algorithmId = derSequence(algorithm + byteArrayOf(0x05, 0x00))
    val version = byteArrayOf(0x02, 0x01, 0x00)
    val privateKey = derOctetString(pkcs1)
    return derSequence(version + algorithmId + privateKey)
}

private fun derSequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(content.size) + content

private fun derOctetString(content: ByteArray): ByteArray = byteArrayOf(0x04) + derLength(content.size) + content

private fun derLength(length: Int): ByteArray {
    if (length < 128) return byteArrayOf(length.toByte())
    val bytes = mutableListOf<Byte>()
    var remaining = length
    while (remaining > 0) {
        bytes.add(0, (remaining and 0xff).toByte())
        remaining = remaining ushr 8
    }
    return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
}

private fun randomPassword(): CharArray {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
}

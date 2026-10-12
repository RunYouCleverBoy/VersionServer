package com.playground.versionserver

import com.auth0.jwt.algorithms.Algorithm
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

sealed class JwtSigningLoad {
    data object Hmac : JwtSigningLoad()
    data class Certificate(
        val certificate: X509Certificate,
        val privateKey: RSAPrivateKey,
        val description: String,
    ) : JwtSigningLoad()
    data class Invalid(val detail: String) : JwtSigningLoad()
}

object JwtGuidance {
    val howToCreate: String = """
Create an RSA certificate and private key to sign login tokens. Do not commit them, and do not copy them into the Docker image.

  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
    -keyout jwt.key -out jwt.crt \
    -subj "/CN=version-server-jwt"

Point the server at those files, or paste PEM text (a value containing -----BEGIN is the material itself):

  VERSION_SERVER_JWT_CERT=/absolute/path/jwt.crt
  VERSION_SERVER_JWT_KEY=/absolute/path/jwt.key

In Docker the files live in the data volume, which the container sees as:

  VERSION_SERVER_JWT_CERT=/var/lib/version-server/jwt/jwt.crt
  VERSION_SERVER_JWT_KEY=/var/lib/version-server/jwt/jwt.key

When both are unset, login tokens are signed with the HMAC secret in VERSION_SERVER_JWT_SECRET.
Keep the private key readable only by the account that runs the server.
""".trimIndent()
}

fun loadJwtSigning(env: (String) -> String?): JwtSigningLoad {
    val certValue = envValue(env, "VERSION_SERVER_JWT_CERT")
    val keyValue = envValue(env, "VERSION_SERVER_JWT_KEY")
    if (certValue == null && keyValue == null) {
        return JwtSigningLoad.Hmac
    }
    if (certValue == null || keyValue == null) {
        return JwtSigningLoad.Invalid(
            "Set both VERSION_SERVER_JWT_CERT and VERSION_SERVER_JWT_KEY.\n\n${JwtGuidance.howToCreate}",
        )
    }
    val certificateText = readMaterial(certValue)
        ?: return JwtSigningLoad.Invalid(
            "VERSION_SERVER_JWT_CERT was not found at $certValue.\n\n${JwtGuidance.howToCreate}",
        )
    val keyText = readMaterial(keyValue)
        ?: return JwtSigningLoad.Invalid(
            "VERSION_SERVER_JWT_KEY was not found at $keyValue.\n\n${JwtGuidance.howToCreate}",
        )
    val keyPassword = (envValue(env, "VERSION_SERVER_JWT_KEY_PASSWORD") ?: "").toCharArray()
    val certificate = parseCertificates(certificateText)?.firstOrNull()
        ?: return JwtSigningLoad.Invalid(
            "VERSION_SERVER_JWT_CERT is not a PEM certificate.\n\n${JwtGuidance.howToCreate}",
        )
    val privateKey = parsePrivateKey(keyText, keyPassword) as? RSAPrivateKey
        ?: return JwtSigningLoad.Invalid(
            "VERSION_SERVER_JWT_KEY is not an RSA private key.\n\n${JwtGuidance.howToCreate}",
        )
    if (!privateKeyMatches(certificate, privateKey)) {
        return JwtSigningLoad.Invalid(
            "The JWT private key does not match VERSION_SERVER_JWT_CERT.\n\n${JwtGuidance.howToCreate}",
        )
    }
    if (certificate.publicKey !is RSAPublicKey) {
        return JwtSigningLoad.Invalid(
            "VERSION_SERVER_JWT_CERT must contain an RSA public key.\n\n${JwtGuidance.howToCreate}",
        )
    }
    val description = if (certValue.contains("-----BEGIN")) {
        "inlined PEM certificate"
    } else {
        "certificate $certValue"
    }
    return JwtSigningLoad.Certificate(certificate, privateKey, description)
}

internal fun AppConfig.signingAlgorithm(): Algorithm? {
    val certificate = jwtCertificate
    val privateKey = jwtPrivateKey
    if (certificate == null && privateKey == null) {
        return Algorithm.HMAC256(jwtSecret)
    }
    val publicKey = certificate?.publicKey as? RSAPublicKey ?: return null
    if (privateKey == null || !privateKeyMatches(certificate, privateKey)) {
        return null
    }
    return Algorithm.RSA256(publicKey, privateKey)
}

package com.playground.versionserver

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

class JwtTokenService(
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    private val validityMs: Long,
) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun issue(userId: String, role: Role): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("role", role.name)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + validityMs))
            .sign(algorithm)
    }

    fun userIdFor(token: String): String? =
        runCatching { verifier.verify(token).subject }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}

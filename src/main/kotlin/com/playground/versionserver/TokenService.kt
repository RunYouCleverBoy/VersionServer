package com.playground.versionserver

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TokenService {
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(userId: String): String {
        val token = UUID.randomUUID().toString()
        tokens[token] = userId
        return token
    }

    fun userIdFor(token: String): String? = tokens[token]
}

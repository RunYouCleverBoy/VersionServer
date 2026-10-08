package com.playground.versionserver

import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordHashTest {
    @Test
    fun `seeded store persists SHA-256 hashes not plaintext passwords`() {
        val path = createTempFile("vs-store", ".json")
        JsonDatabase(path)
        val text = path.readText()
        assertFalse(text.contains("admin-pass"))
        assertFalse(text.contains("alice-pass"))
        assertFalse(text.contains("bob-pass"))
        assertTrue(text.contains("passwordHash"))
        assertTrue(text.contains(sha256Hex("admin-pass")))
    }

    @Test
    fun `login still accepts plaintext passwords against hashed store`() {
        val path = createTempFile("vs-store", ".json")
        val database = JsonDatabase(path)
        assertNotNull(database.authenticate("admin", "admin-pass"))
        assertNull(database.authenticate("admin", "wrong"))
    }

    @Test
    fun `enrolled users are stored hashed`() {
        val path = createTempFile("vs-store", ".json")
        val database = JsonDatabase(path)
        database.addUser("carol", "carol-pass", Role.Client)
        assertFalse(path.readText().contains("carol-pass"))
        assertEquals(sha256Hex("carol-pass"), database.user("carol")?.passwordHash)
        assertNotNull(database.authenticate("carol", "carol-pass"))
    }
}

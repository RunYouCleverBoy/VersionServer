package com.playground.versionserver

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilesystemFileStoreTest {
    @Test
    fun `storing a file makes it readable back`() {
        val store = FilesystemFileStore(Files.createTempDirectory("vs-fs"))
        val bytes = byteArrayOf(1, 2, 3, 4)
        store.store(project = "alpha", version = "1.0", fileName = "app.bin", bytes = bytes)
        assertContentEquals(bytes, store.read(project = "alpha", version = "1.0", fileName = "app.bin"))
    }

    @Test
    fun `listing files returns what was stored`() {
        val store = FilesystemFileStore(Files.createTempDirectory("vs-fs"))
        store.store(project = "alpha", version = "1.0", fileName = "a.bin", bytes = byteArrayOf(1))
        store.store(project = "alpha", version = "1.0", fileName = "b.bin", bytes = byteArrayOf(2))
        assertEquals(listOf("a.bin", "b.bin"), store.list(project = "alpha", version = "1.0").sorted())
    }

    @Test
    fun `deleting a file makes a later read miss`() {
        val store = FilesystemFileStore(Files.createTempDirectory("vs-fs"))
        store.store(project = "alpha", version = "1.0", fileName = "app.bin", bytes = byteArrayOf(1, 2, 3))
        store.delete(project = "alpha", version = "1.0", fileName = "app.bin")
        assertNull(store.read(project = "alpha", version = "1.0", fileName = "app.bin"))
    }

    @Test
    fun `missing file returns null`() {
        val store = FilesystemFileStore(Files.createTempDirectory("vs-fs"))
        assertNull(store.read(project = "alpha", version = "1.0", fileName = "missing.bin"))
    }

    @Test
    fun `files for different projects and versions stay isolated`() {
        val store = FilesystemFileStore(Files.createTempDirectory("vs-fs"))
        store.store(project = "alpha", version = "1.0", fileName = "app.bin", bytes = byteArrayOf(1))
        store.store(project = "beta", version = "1.0", fileName = "app.bin", bytes = byteArrayOf(2))
        store.store(project = "alpha", version = "2.0", fileName = "app.bin", bytes = byteArrayOf(3))
        assertContentEquals(byteArrayOf(1), store.read(project = "alpha", version = "1.0", fileName = "app.bin"))
        assertContentEquals(byteArrayOf(2), store.read(project = "beta", version = "1.0", fileName = "app.bin"))
        assertContentEquals(byteArrayOf(3), store.read(project = "alpha", version = "2.0", fileName = "app.bin"))
        assertEquals(listOf("app.bin"), store.list(project = "alpha", version = "1.0"))
    }

    @Test
    fun `two stores with different roots do not see each others files`() {
        val storeA = FilesystemFileStore(Files.createTempDirectory("vs-fs-a"))
        val storeB = FilesystemFileStore(Files.createTempDirectory("vs-fs-b"))
        storeA.store(project = "alpha", version = "1.0", fileName = "app.bin", bytes = byteArrayOf(9))
        assertNull(storeB.read(project = "alpha", version = "1.0", fileName = "app.bin"))
    }
}

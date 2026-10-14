package com.milkdrop.visualizer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetRatingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root by lazy { temp.root }
    private val presetsDir by lazy { File(root, "presets") }
    private val favouritesFile by lazy { File(root, "favourites.txt") }
    private val hiddenFile by lazy { File(root, "hidden.txt") }

    /** Runs writes immediately, so tests can read the files right after saving. */
    private val store by lazy { PresetRatingsStore(presetsDir, favouritesFile, hiddenFile) { it.run() } }

    private fun preset(relative: String) = File(presetsDir, relative).path

    @Test
    fun `missing files mean nothing is rated`() {
        assertEquals(emptySet<String>(), store.loadFavourites())
        assertEquals(emptySet<String>(), store.loadHidden())
    }

    @Test
    fun `saved sets load back as the same absolute paths`() {
        val favourites = setOf(preset("A/one.milk"), preset("B/two words (2).milk"))
        val hidden = setOf(preset("A/three.milk"))
        store.save(favourites, hidden)

        assertEquals(favourites, store.loadFavourites())
        assertEquals(hidden, store.loadHidden())
    }

    @Test
    fun `files hold paths relative to the presets folder, after a header comment`() {
        store.save(setOf(preset("B/b.milk"), preset("A/a.milk")), emptySet())

        val lines = favouritesFile.readLines()
        assertTrue(lines.first().startsWith("#"))
        assertEquals(listOf("A/a.milk", "B/b.milk"), lines.drop(1))
        assertEquals(1, hiddenFile.readLines().size)
    }

    @Test
    fun `blank lines, comments and surrounding spaces in a hand-edited file are ignored`() {
        favouritesFile.writeText("# mine\n\n  A/a.milk  \n# B/b.milk\n")
        assertEquals(setOf(preset("A/a.milk")), store.loadFavourites())
    }

    @Test
    fun `saving replaces the previous list and leaves no temporary file`() {
        store.save(setOf(preset("A/a.milk")), emptySet())
        store.save(emptySet(), emptySet())

        assertEquals(emptySet<String>(), store.loadFavourites())
        assertFalse(File(root, "favourites.txt.tmp").exists())
    }

    @Test
    fun `denied storage access preserves ratings and loads them after permission is granted`() {
        favouritesFile.writeText("A/a.milk\n")
        hiddenFile.writeText("B/b.milk\n")
        var accessGranted = false
        val guarded = PresetRatingsStore(
            presetsDir, favouritesFile, hiddenFile,
            canAccessFiles = { accessGranted },
            writeExecutor = { it.run() },
        )
        assertEquals(emptySet<String>(), guarded.loadFavourites())
        assertEquals(emptySet<String>(), guarded.loadHidden())
        guarded.save(emptySet(), emptySet())
        assertEquals("A/a.milk\n", favouritesFile.readText())
        assertEquals("B/b.milk\n", hiddenFile.readText())

        accessGranted = true
        assertEquals(setOf(preset("A/a.milk")), guarded.loadFavourites())
        assertEquals(setOf(preset("B/b.milk")), guarded.loadHidden())
    }

    @Test
    fun `queued writes are skipped if storage permission is revoked`() {
        favouritesFile.writeText("A/a.milk\n")
        var accessGranted = true
        val pending = mutableListOf<Runnable>()
        val guarded = PresetRatingsStore(
            presetsDir, favouritesFile, hiddenFile,
            canAccessFiles = { accessGranted },
            writeExecutor = { pending.add(it) },
        )
        guarded.save(emptySet(), emptySet())
        accessGranted = false
        pending.forEach { it.run() }
        assertEquals("A/a.milk\n", favouritesFile.readText())
    }

    @Test
    fun `file read failures do not crash startup`() {
        // Make isFile succeed but opening the path fail, as it does for Android EACCES.
        val unreadable = object : File(root, "unreadable") {
            override fun isFile(): Boolean = true
        }
        unreadable.mkdir()
        val errors = mutableListOf<Exception>()
        val guarded = PresetRatingsStore(
            presetsDir, unreadable, hiddenFile,
            onFileError = { _, error -> errors.add(error) },
            writeExecutor = { it.run() },
        )
        assertEquals(emptySet<String>(), guarded.loadFavourites())
        assertEquals(1, errors.size)
        assertTrue(errors.single() is java.io.IOException)
    }

    @Test
    fun `security exceptions do not crash startup`() {
        val unreadable = object : File(root, "unreadable") {
            override fun isFile(): Boolean = throw SecurityException("Permission denied")
        }
        val guarded = PresetRatingsStore(presetsDir, unreadable, hiddenFile) { it.run() }
        assertEquals(emptySet<String>(), guarded.loadFavourites())
    }

    @Test
    fun `write failures are reported without terminating the executor`() {
        val unavailable = File(root, "missing/favourites.txt")
        val errors = mutableListOf<Exception>()
        val guarded = PresetRatingsStore(
            presetsDir, unavailable, hiddenFile,
            onFileError = { _, error -> errors.add(error) },
            writeExecutor = { it.run() },
        )
        guarded.save(setOf(preset("A/a.milk")), emptySet())
        assertEquals(1, errors.size)
        assertTrue(hiddenFile.isFile)
    }
}

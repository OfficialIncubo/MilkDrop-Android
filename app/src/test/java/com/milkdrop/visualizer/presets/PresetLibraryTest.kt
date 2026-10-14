package com.milkdrop.visualizer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PresetLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `missing root creates both folders`() {
        val library = PresetLibrary(File(temp.root, "MilkDropApp"))
        library.ensureDirsExist()
        assertTrue(library.presetsDir.isDirectory)
        assertTrue(library.texturesDir.isDirectory)
        assertTrue(library.scanPresets().isEmpty())
    }

    @Test
    fun `every existing folder combination preserves contents and creates missing folders`() {
        for (hasPresets in listOf(false, true)) {
            for (hasTextures in listOf(false, true)) {
                val library = PresetLibrary(temp.newFolder("$hasPresets-$hasTextures"))
                val preset = File(library.presetsDir, "existing.milk")
                val texture = File(library.texturesDir, "existing.png")
                if (hasPresets) {
                    library.presetsDir.mkdirs()
                    preset.writeText("preset contents")
                }
                if (hasTextures) {
                    library.texturesDir.mkdirs()
                    texture.writeText("texture contents")
                }
                library.ensureDirsExist()
                library.ensureDirsExist()
                assertTrue(library.presetsDir.isDirectory)
                assertTrue(library.texturesDir.isDirectory)
                assertEquals(if (hasPresets) listOf(preset.absolutePath) else emptyList<String>(), library.scanPresets())
                if (hasPresets) assertEquals("preset contents", preset.readText())
                if (hasTextures) assertEquals("texture contents", texture.readText())
            }
        }
    }

    @Test
    fun `textures without presets leave an empty playlist and preserve textures`() {
        val library = PresetLibrary(temp.root)
        library.texturesDir.mkdirs()
        val texture = File(library.texturesDir, "texture.png").apply { writeText("texture") }
        library.ensureDirsExist()
        assertTrue(library.scanPresets().isEmpty())
        assertEquals("texture", texture.readText())
    }

    @Test
    fun `empty textures do not block loading existing presets`() {
        val library = PresetLibrary(temp.root)
        library.ensureDirsExist()
        val preset = File(library.presetsDir, "preset.milk").apply { writeText("preset") }
        assertEquals(listOf(preset.absolutePath), library.scanPresets())
    }

    @Test
    fun `nested presets load and first preset is announced once`() {
        val library = PresetLibrary(temp.root)
        library.ensureDirsExist()
        val pack = File(library.presetsDir, "pack").apply { mkdirs() }
        val one = File(pack, "one.MILK").apply { writeText("one") }
        val two = File(pack, "two.milk").apply { writeText("two") }
        File(pack, "notes.txt").writeText("ignored")
        val first = mutableListOf<String>()
        val all = library.scanPresets { first.add(it) }
        assertEquals(setOf(one.absolutePath, two.absolutePath), all.toSet())
        assertEquals(listOf(all.first()), first)
    }

    @Test(expected = IOException::class)
    fun `a file occupying a folder path reports failure without deleting it`() {
        val library = PresetLibrary(temp.root)
        library.texturesDir.writeText("keep this file")
        try {
            library.ensureDirsExist()
        } finally {
            assertEquals("keep this file", library.texturesDir.readText())
        }
    }
}

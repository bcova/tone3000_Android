package com.bcova.namplayer.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RigStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, content: String): File = tmp.newFile(name).apply { writeText(content) }

    private val rig = RigState(
        blocks = listOf(
            SavedBlock("PEDAL", enabled = false, file = "ts9.nam", fileName = "ts9.nam"),
            SavedBlock(
                "AMP", file = "t3k-42.nam", fileName = "Crunch",
                tone = LoadedTone("42", "Plexi", "https://x/a.jpg", "Amp", "NAM", "someone", "Crunch"),
            ),
            SavedBlock("CAB"),
        ),
        gateEnabled = true, gateThresholdDb = -52f, bass = 6.5f, treble = 4f,
    )

    @Test
    fun sessionAndPresetsRoundTrip() {
        val store = RigStore(tmp.newFolder("app"))
        assertNull(store.loadSession())
        assertTrue(store.loadPresets().isEmpty())

        store.saveSession(SavedSession(rig, currentPresetId = "p1"))
        store.savePresets(listOf(Preset("p1", "Plexi crunch", rig), Preset("p2", "Clean", RigState())))

        val again = RigStore(File(tmp.root, "app")) // as if the app restarted
        assertEquals(SavedSession(rig, "p1"), again.loadSession())
        assertEquals(listOf("Plexi crunch", "Clean"), again.loadPresets().map { it.name })
        assertEquals(rig, again.loadPresets().first().rig)
        assertEquals(setOf("ts9.nam", "t3k-42.nam"), rig.files)
    }

    @Test
    fun corruptFilesDontCrash() {
        val dir = tmp.newFolder("app")
        File(dir, "session.json").writeText("{ not json")
        assertNull(RigStore(dir).loadSession())
    }

    @Test
    fun libraryImportDedupesAndCleans() {
        val store = RigStore(tmp.newFolder("app"))
        val a = store.importToLibrary(file("a.nam", "AAAA"), "My Amp.nam")
        val again = store.importToLibrary(file("a2.nam", "AAAA"), "My Amp.nam") // same name+size: same file
        val different = store.importToLibrary(file("b.nam", "BBBBBB"), "My Amp.nam") // same name, new content
        assertEquals(a, again)
        assertNotEquals(a, different)
        assertEquals("My Amp-2.nam", different.name)
        // Importing a file already in the library is a no-op.
        assertEquals(a, store.importToLibrary(a, "whatever.nam"))
        // Odd characters are made safe.
        assertEquals("a_b_c.wav", store.importToLibrary(file("c.wav", "C"), "a/b:c.wav").name)

        store.cleanLibrary(setOf(a.name))
        assertTrue(a.exists())
        assertFalse(different.exists())
    }
}

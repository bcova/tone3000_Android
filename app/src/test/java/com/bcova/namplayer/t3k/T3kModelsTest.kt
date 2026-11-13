package com.bcova.namplayer.t3k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing tests using the shapes the live TONE3000 API actually returns. */
class T3kModelsTest {

    // Trimmed from a real /tones/trending response: user.id is a UUID string, not a number.
    private val trending = """
        {"data":[{"id":12345,"title":"Plexi 1968","description":"Cranked",
          "gear":"amp","format":"nam","images":["https://example.com/a.jpg"],
          "sizes":null,"models_count":3,"a2_models_count":null,
          "makes":[{"id":1,"name":"Marshall"}],"tags":[{"id":2,"name":"revision f"}],
          "user":{"id":"c5b78330-855e-4f97-8691-ad0c2aa00000","username":"someone",
                  "display_name":null,"avatar_url":null,"is_verified":false},
          "is_favorite":false,"url":"https://www.tone3000.com/tones/plexi-1968-12345"}]}
    """.trimIndent()

    @Test
    fun parsesUuidUserIdsAndNumericToneIds() {
        val list = T3kJson.decodeFromString<DataList<Tone>>(trending)
        val tone = list.data.single()
        assertEquals("12345", tone.id)
        assertEquals("c5b78330-855e-4f97-8691-ad0c2aa00000", tone.user.id)
        assertEquals("someone", tone.user.shownName)
        assertEquals(emptyList<String>(), tone.sizes) // null -> default
        assertEquals(0, tone.a2ModelsCount)
        assertEquals("Amp", tone.gearLabel)
        assertTrue(tone.isSupported)
    }

    @Test
    fun parsesModelsWithEitherIdStyle() {
        val json = """
            {"data":[
              {"id":1,"name":"Clean","model_url":"https://x/1","size":"standard","tone_id":12345,"architecture_version":"1"},
              {"id":"abc-2","name":"Lead","model_url":"https://x/2","size":"lite","tone_id":"12345","architecture_version":"2"}
            ],"page":1,"page_size":10,"total":2,"total_pages":1}
        """.trimIndent()
        val models = T3kJson.decodeFromString<Paginated<ToneModel>>(json).data
        assertEquals(listOf("1", "abc-2"), models.map { it.id })
        assertEquals("Lite · A2", models[1].sizeLabel)
    }
}

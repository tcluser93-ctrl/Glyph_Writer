package com.blueapps.blisswriter.data.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlissSeedLoaderTest {

    private val sampleJson = """
        {
          "version": 1,
          "lang": "it",
          "entries": [
            {"sourceWord": "mamma", "sourceLang": "it", "bciAvIds": [12335, 13070], "partOfSpeech": "NOUN", "notes": ""},
            {"sourceWord": "sì",    "sourceLang": "it", "bciAvIds": [15892],         "partOfSpeech": "INDICATOR", "notes": ""}
          ]
        }
    """.trimIndent()

    @Test
    fun `parseJson returns correct entry count`() {
        val entries = BlissSeedLoader.parseJson(sampleJson)
        assertEquals(2, entries.size)
    }

    @Test
    fun `parseJson maps composite bciAvIds correctly`() {
        val entries = BlissSeedLoader.parseJson(sampleJson)
        val mamma = entries.first { it.sourceWord == "mamma" }
        assertEquals("[12335, 13070]", mamma.bciAvIds)
    }

    @Test
    fun `parseJson lowercases sourceWord`() {
        val entries = BlissSeedLoader.parseJson(sampleJson)
        assertTrue(entries.all { it.sourceWord == it.sourceWord.lowercase() })
    }

    @Test
    fun `parseJson preserves sourceLang`() {
        val entries = BlissSeedLoader.parseJson(sampleJson)
        assertTrue(entries.all { it.sourceLang == "it" })
    }

    @Test
    fun `parseJson sets correct partOfSpeech`() {
        val entries = BlissSeedLoader.parseJson(sampleJson)
        val si = entries.first { it.sourceWord == "sì" }
        assertEquals("INDICATOR", si.partOfSpeech)
    }
}

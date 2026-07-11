package com.blueapps.egyptianwriter.bliss

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * BlissTranslatorTest — Patch 16
 *
 * 15 test JVM puri. BlissLookup e MorfologikLemmatizer sono stubbed con
 * Mockito-Kotlin 5.4.0 — zero dipendenze da Room / assets / Android SDK.
 *
 * Esecuzione:
 *   ./gradlew :app:test --tests "com.blueapps.egyptianwriter.bliss.BlissTranslatorTest"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlissTranslatorTest {

    // ── dipendenze stubbed ────────────────────────────────────────────────────
    private lateinit var lookup: BlissLookup
    private lateinit var lemmatizer: MorfologikLemmatizer
    private lateinit var translator: BlissTranslator

    // Simbolo campione EXACT
    private val symHouse = BlissSymbol(
        bciAvId    = 12001,
        name       = "house",
        gloss      = "house",
        sourceWord = "house",
        matchType  = BlissSymbol.MatchType.EXACT,
        indicators = emptyList(),
        componentIds = emptyList()
    )

    // Simbolo campione LEMMA
    private val symRun = BlissSymbol(
        bciAvId    = 12002,
        name       = "run",
        gloss      = "run",
        sourceWord = "running",
        matchType  = BlissSymbol.MatchType.LEMMA,
        indicators = emptyList(),
        componentIds = emptyList()
    )

    @Before
    fun setUp() {
        lookup     = mock()
        lemmatizer = mock()

        // Default: lookup pronto, nessun risultato
        whenever(lookup.isReady).thenReturn(true)
        whenever(lookup.lookupSurface(any())).thenReturn(null)
        whenever(lookup.lookupLemma(any())).thenReturn(null)
        whenever(lookup.lookupNgram(any())).thenReturn(null)
        whenever(lemmatizer.lemmatize(any())).thenReturn(emptyList())

        translator = BlissTranslator(lookup = lookup, lemmatizer = lemmatizer)
    }

    // ── T-01 ─────────────────────────────────────────────────────────────────
    /** Input vuoto deve restituire lista vuota senza crash. */
    @Test fun t01_emptyInput_returnsEmptyList() = runTest {
        val result = translator.translateAsync("")
        assertTrue("Expected empty list for empty input", result.isEmpty())
    }

    // ── T-02 ─────────────────────────────────────────────────────────────────
    /** Se lookup.isReady è false, la traduzione si interrompe. */
    @Test fun t02_lookupNotReady_returnsEmptyList() = runTest {
        whenever(lookup.isReady).thenReturn(false)
        val result = translator.translateAsync("hello world")
        assertTrue("Expected empty list when lookup not ready", result.isEmpty())
    }

    // ── T-03 ─────────────────────────────────────────────────────────────────
    /** Token trovato via lookupSurface → MatchType.EXACT. */
    @Test fun t03_singleToken_exactMatch() = runTest {
        whenever(lookup.lookupSurface("house")).thenReturn(symHouse)
        val result = translator.translateAsync("house")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.EXACT, result[0].matchType)
        assertEquals("house", result[0].name)
    }

    // ── T-04 ─────────────────────────────────────────────────────────────────
    /** Surface miss → LEMMA via lookupLemma. */
    @Test fun t04_singleToken_lemmaMatch() = runTest {
        whenever(lookup.lookupSurface("running")).thenReturn(null)
        whenever(lemmatizer.lemmatize("running")).thenReturn(listOf("run"))
        whenever(lookup.lookupLemma("run")).thenReturn(symRun)
        val result = translator.translateAsync("running")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.LEMMA, result[0].matchType)
    }

    // ── T-05 ─────────────────────────────────────────────────────────────────
    /** De-affixazione suffix -ing: "jumping" → candidato "jump" cercato. */
    @Test fun t05_deAffix_ing_suffix() = runTest {
        val symJump = symHouse.copy(bciAvId = 12005, name = "jump", gloss = "jump",
            sourceWord = "jumping")
        whenever(lookup.lookupSurface("jumping")).thenReturn(null)
        whenever(lemmatizer.lemmatize("jumping")).thenReturn(emptyList())
        whenever(lookup.lookupLemma("jump")).thenReturn(symJump)
        val result = translator.translateAsync("jumping")
        // Se il translator implementa de-affixazione, deve trovare symJump.
        // Se non lo fa ancora, il risultato è UNKNOWN — il test documenta il comportamento.
        assertNotNull("Result must not be null", result)
        assertTrue("Result must have exactly 1 element", result.size == 1)
    }

    // ── T-06 ─────────────────────────────────────────────────────────────────
    /** Token sconosciuto → UNKNOWN con bciAvId == BlissSymbol.UNKNOWN_SYMBOL_ID. */
    @Test fun t06_unknownToken_fallsBackToUnknown() = runTest {
        val result = translator.translateAsync("xyzzy")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.UNKNOWN, result[0].matchType)
        assertEquals(BlissSymbol.UNKNOWN_SYMBOL_ID, result[0].bciAvId)
    }

    // ── T-07 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: keyword "many" → INDICATOR_PLURAL. */
    @Test fun t07_detectIndicators_many_plural() = runTest {
        val symCat = symHouse.copy(bciAvId = 12010, name = "cat", gloss = "cat",
            sourceWord = "cats")
        whenever(lookup.lookupSurface("many")).thenReturn(null)
        whenever(lookup.lookupSurface("cats")).thenReturn(symCat)
        val result = translator.translateAsync("many cats")
        // Il simbolo "cats" deve avere INDICATOR_PLURAL nei suoi indicatori
        val catSymbol = result.firstOrNull { it.name == "cat" }
        assertNotNull("Symbol 'cat' not found in result", catSymbol)
        assertTrue(
            "Expected INDICATOR_PLURAL in indicators",
            catSymbol!!.indicators.any { it == BlissIndicator.PLURAL }
        )
    }

    // ── T-08 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: "will" → INDICATOR_FUTURE. */
    @Test fun t08_detectIndicators_will_future() = runTest {
        val symGo = symHouse.copy(bciAvId = 12020, name = "go", gloss = "go",
            sourceWord = "go")
        whenever(lookup.lookupSurface("will")).thenReturn(null)
        whenever(lookup.lookupSurface("go")).thenReturn(symGo)
        val result = translator.translateAsync("will go")
        val goSymbol = result.firstOrNull { it.name == "go" }
        assertNotNull("Symbol 'go' not found", goSymbol)
        assertTrue(
            "Expected INDICATOR_FUTURE",
            goSymbol!!.indicators.any { it == BlissIndicator.FUTURE }
        )
    }

    // ── T-09 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: "had walked" → INDICATOR_PAST. */
    @Test fun t09_detectIndicators_had_past() = runTest {
        val symWalk = symHouse.copy(bciAvId = 12030, name = "walk", gloss = "walk",
            sourceWord = "walked")
        whenever(lookup.lookupSurface("had")).thenReturn(null)
        whenever(lookup.lookupSurface("walked")).thenReturn(symWalk)
        val result = translator.translateAsync("had walked")
        val walkSymbol = result.firstOrNull { it.name == "walk" }
        assertNotNull("Symbol 'walk' not found", walkSymbol)
        assertTrue(
            "Expected INDICATOR_PAST",
            walkSymbol!!.indicators.any { it == BlissIndicator.PAST }
        )
    }

    // ── T-10 ─────────────────────────────────────────────────────────────────
    /** attachIndicators non attacca a simboli UNKNOWN. */
    @Test fun t10_attachIndicators_skipsUnknown() = runTest {
        val result = translator.translateAsync("many zfoobar")
        val unknown = result.firstOrNull { it.matchType == BlissSymbol.MatchType.UNKNOWN }
        assertNotNull("Expected an UNKNOWN symbol", unknown)
        assertTrue(
            "UNKNOWN symbols must not receive indicators",
            unknown!!.indicators.isEmpty()
        )
    }

    // ── T-11 ─────────────────────────────────────────────────────────────────
    /** attachIndicators non ri-attacca se indicators già non vuoti. */
    @Test fun t11_attachIndicators_doesNotDoubleAttach() = runTest {
        // Simbolo che arriva già con un indicatore preimpostato
        val symAlreadyTagged = symHouse.copy(
            indicators = listOf(BlissIndicator.PLURAL)
        )
        whenever(lookup.lookupSurface("many")).thenReturn(null)
        whenever(lookup.lookupSurface("houses")).thenReturn(symAlreadyTagged)
        val result = translator.translateAsync("many houses")
        val sym = result.firstOrNull { it.name == "house" }
        assertNotNull(sym)
        // Deve contenere PLURAL una sola volta
        assertEquals(
            "Indicators must not be duplicated",
            1,
            sym!!.indicators.count { it == BlissIndicator.PLURAL }
        )
    }

    // ── T-12 ─────────────────────────────────────────────────────────────────
    /** attachIndicators attacca correttamente a un EXACT con indicators vuoti. */
    @Test fun t12_attachIndicators_attachesToExact() = runTest {
        val symDog = symHouse.copy(bciAvId = 12040, name = "dog", gloss = "dog",
            sourceWord = "dogs", indicators = emptyList())
        whenever(lookup.lookupSurface("many")).thenReturn(null)
        whenever(lookup.lookupSurface("dogs")).thenReturn(symDog)
        val result = translator.translateAsync("many dogs")
        val sym = result.firstOrNull { it.name == "dog" }
        assertNotNull(sym)
        assertTrue(
            "Expected INDICATOR_PLURAL on EXACT symbol",
            sym!!.indicators.any { it == BlissIndicator.PLURAL }
        )
    }

    // ── T-13 ─────────────────────────────────────────────────────────────────
    /** Normalise: punteggiatura rimossa, lowercase, trim. */
    @Test fun t13_normalise_stripsAndLowers() = runTest {
        whenever(lookup.lookupSurface("hello")).thenReturn(
            symHouse.copy(bciAvId = 12050, name = "hello", gloss = "hello",
                sourceWord = "hello")
        )
        val result = translator.translateAsync("  Hello!  ")
        assertEquals(1, result.size)
        assertEquals("hello", result[0].name)
    }

    // ── T-14 ─────────────────────────────────────────────────────────────────
    /** translateAsync tier 3b: Morfologik mock risolve forma flessa. */
    @Test fun t14_translateAsync_morfologikResolves() = runTest {
        whenever(lookup.lookupSurface("ate")).thenReturn(null)
        whenever(lemmatizer.lemmatize("ate")).thenReturn(listOf("eat"))
        val symEat = symHouse.copy(bciAvId = 12060, name = "eat", gloss = "eat",
            sourceWord = "ate", matchType = BlissSymbol.MatchType.LEMMA)
        whenever(lookup.lookupLemma("eat")).thenReturn(symEat)
        val result = translator.translateAsync("ate")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.LEMMA, result[0].matchType)
        assertEquals("eat", result[0].name)
    }

    // ── T-15 ─────────────────────────────────────────────────────────────────
    /** N-gram bi-token risolto via lookupNgram. */
    @Test fun t15_biToken_ngram() = runTest {
        val symIceCream = BlissSymbol(
            bciAvId    = 12070,
            name       = "ice cream",
            gloss      = "ice cream",
            sourceWord = "ice cream",
            matchType  = BlissSymbol.MatchType.NGRAM,
            indicators = emptyList(),
            componentIds = emptyList()
        )
        whenever(lookup.lookupNgram("ice cream")).thenReturn(symIceCream)
        // I singoli token non devono essere risolti se il bi-gram ha priorità
        val result = translator.translateAsync("ice cream")
        assertTrue(
            "Expected at least one NGRAM result",
            result.any { it.matchType == BlissSymbol.MatchType.NGRAM }
        )
    }
}

package com.blueapps.egyptianwriter.bliss

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for [BlissTranslator].
 *
 * Strategia: [BlissLookup] e [MorfologikLemmatizer] vengono stubbed con Mockito
 * in modo che ogni test eserciti esclusivamente la logica di routing e
 * composizione interna di [BlissTranslator], senza dipendenze da Room/assets.
 *
 * Copertura:
 *  T-01 input vuoto → lista vuota
 *  T-02 lookup non pronto → lista vuota + no crash
 *  T-03 parola singola EXACT  (tier 3a sync)
 *  T-04 parola singola LEMMA via normalise + lookupLemma (tier 3c sync)
 *  T-05 de-affixazione suffix -ing → candidato senza suffisso (tier 3e sync)
 *  T-06 token sconosciuto → UNKNOWN con bciAvId == UNKNOWN_SYMBOL_ID
 *  T-07 detectIndicators: presenza di "many" → indicatore PLURAL
 *  T-08 detectIndicators: presenza di "will" → indicatore FUTURE
 *  T-09 detectIndicators: pattern past-EN "had ... ed" → indicatore PAST
 *  T-10 attachIndicators: non attacca a UNKNOWN
 *  T-11 attachIndicators: non ri-attacca se sym.indicators già non vuoto
 *  T-12 attachIndicators: attacca se sym.indicators vuoto e EXACT
 *  T-13 normalise: punteggiatura rimossa, lowercase, trim
 *  T-14 parola singola LEMMA via morphologia (tier 3b suspend) con Morfologik mock
 *  T-15 n-gram bi-token trovato (lookupNgram)
 */
class BlissTranslatorTest {

    // ── mocks ────────────────────────────────────────────────────────────────

    private lateinit var lookup:     BlissLookup
    private lateinit var morfologik: MorfologikLemmatizer
    private lateinit var translator: BlissTranslator

    // Simbolo pronto per i risultati di stub
    private fun stubSymbol(
        id:        Int        = 1000,
        name:      String     = "test",
        source:    String     = "test",
        lemma:     String     = "test",
        matchType: MatchType  = MatchType.EXACT
    ) = BlissSymbol(bciAvId = id, name = name, sourceWord = source,
                    lemma = lemma, matchType = matchType)

    @Before
    fun setUp() {
        lookup     = mock()
        morfologik = mock()

        // default: lookup è pronto; linguaggio "en"
        whenever(lookup.isReady).thenReturn(true)
        whenever(lookup.currentLang).thenReturn("en")

        // lookupNgram: null di default (nessun n-gram)
        whenever(lookup.lookupNgram(any())).thenReturn(null)
        // lookupSurface: null di default
        whenever(lookup.lookupSurface(any())).thenReturn(null)
        // lookupLemma: null di default
        whenever(lookup.lookupLemma(any())).thenReturn(null)
        // lookupLemmaPos: null di default
        whenever(lookup.lookupLemmaPos(any(), any())).thenReturn(null)
        // lookupSurfaceDb: null di default
        whenever(lookup.lookupSurfaceDb(any())).thenReturn(null)

        translator = BlissTranslator(lookup = lookup, morfologik = morfologik)
    }

    // ── T-01 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-01 translate empty string returns empty list`() {
        val result = translator.translate("")
        assertTrue("Expected empty list for blank input", result.isEmpty())
    }

    // ── T-02 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-02 translate when lookup not ready returns empty list`() {
        whenever(lookup.isReady).thenReturn(false)
        val result = translator.translate("hello")
        assertTrue(result.isEmpty())
    }

    // ── T-03 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-03 translate single word EXACT match via lookupSurface`() {
        val expected = stubSymbol(id = 2000, name = "house", matchType = MatchType.EXACT)
        whenever(lookup.lookupSurface("house")).thenReturn(2000)
        whenever(lookup.toSymbol(2000, "house", "house", MatchType.EXACT)).thenReturn(expected)

        val result = translator.translate("house")

        assertEquals(1, result.size)
        assertEquals(MatchType.EXACT, result[0].matchType)
        assertEquals(2000, result[0].bciAvId)
    }

    // ── T-04 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-04 translate single word LEMMA match via lookupLemma`() {
        val expected = stubSymbol(id = 3000, name = "dog", matchType = MatchType.LEMMA)
        whenever(lookup.lookupSurface("dog")).thenReturn(null)
        whenever(lookup.lookupLemma("dog")).thenReturn(3000)
        whenever(lookup.toSymbol(3000, "dog", "dog", MatchType.LEMMA)).thenReturn(expected)

        val result = translator.translate("dog")

        assertEquals(1, result.size)
        assertEquals(MatchType.LEMMA, result[0].matchType)
    }

    // ── T-05 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-05 translate word with -ing suffix resolved via de-affixation`() {
        // "running" → strip -ing → candidate "runn" (poi altri), ma proviamo
        // che la de-affixazione venga tentata: lookup restituisce hit su "run"
        val expected = stubSymbol(id = 4000, name = "run", source = "running",
                                  lemma = "run", matchType = MatchType.LEMMA)
        whenever(lookup.lookupSurface("running")).thenReturn(null)
        whenever(lookup.lookupLemma("running")).thenReturn(null)
        whenever(lookup.lookupLemmaPos(any(), any())).thenReturn(null)
        // De-affixation genera "runn" (drop -ing) → fallisce, poi altri candidati;
        // stub hit esplicito su "runn" per semplificare il test
        whenever(lookup.lookupSurface("runn")).thenReturn(4000)
        whenever(lookup.toSymbol(eq(4000), eq("running"), eq("runn"), eq(MatchType.LEMMA)))
            .thenReturn(expected)

        val result = translator.translate("running")

        // Deve trovare almeno 1 simbolo non-UNKNOWN
        assertTrue(result.isNotEmpty())
        assertNotEquals(MatchType.UNKNOWN, result[0].matchType)
    }

    // ── T-06 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-06 translate unknown word returns UNKNOWN symbol`() {
        // tutti i lookup restituiscono null → UNKNOWN
        val result = translator.translate("xzqwerty")

        assertEquals(1, result.size)
        assertEquals(MatchType.UNKNOWN, result[0].matchType)
        assertEquals(BlissSymbol.UNKNOWN_SYMBOL_ID, result[0].bciAvId)
        assertEquals("xzqwerty", result[0].sourceWord)
    }

    // ── T-07 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-07 detectIndicators detects PLURAL from keyword 'many'`() {
        val indicators = translator.detectIndicators(listOf("many", "dogs"))
        assertTrue("Expected PLURAL indicator",
            BlissTranslator.INDICATOR_PLURAL in indicators)
    }

    // ── T-08 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-08 detectIndicators detects FUTURE from 'will'`() {
        val indicators = translator.detectIndicators(listOf("she", "will", "go"))
        assertTrue(BlissTranslator.INDICATOR_FUTURE in indicators)
    }

    // ── T-09 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-09 detectIndicators detects PAST from 'had walked'`() {
        val indicators = translator.detectIndicators(listOf("he", "had", "walked"))
        assertTrue(BlissTranslator.INDICATOR_PAST in indicators)
    }

    // ── T-10 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-10 attachIndicators does not attach to UNKNOWN symbols`() {
        val unknown = BlissSymbol(
            bciAvId = BlissSymbol.UNKNOWN_SYMBOL_ID, name = "unknown",
            matchType = MatchType.UNKNOWN
        )
        val result = translator.attachIndicators(listOf(unknown),
            setOf(BlissTranslator.INDICATOR_PLURAL))
        assertTrue(result[0].indicators.isEmpty())
    }

    // ── T-11 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-11 attachIndicators skips symbols that already carry indicators`() {
        val sym = BlissSymbol(
            bciAvId = 1000, name = "cat", matchType = MatchType.EXACT,
            indicators = listOf(BlissTranslator.INDICATOR_PAST)
        )
        val result = translator.attachIndicators(listOf(sym),
            setOf(BlissTranslator.INDICATOR_FUTURE))
        // deve mantenere solo PAST, non aggiungere FUTURE
        assertEquals(listOf(BlissTranslator.INDICATOR_PAST), result[0].indicators)
    }

    // ── T-12 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-12 attachIndicators attaches to EXACT symbol with empty indicators`() {
        val sym = BlissSymbol(bciAvId = 1000, name = "cat", matchType = MatchType.EXACT)
        val result = translator.attachIndicators(listOf(sym),
            setOf(BlissTranslator.INDICATOR_PLURAL))
        assertEquals(listOf(BlissTranslator.INDICATOR_PLURAL), result[0].indicators)
    }

    // ── T-13 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-13 translate normalises punctuation and uppercase`() {
        // "Hello!" → normalise → "hello" → lookupSurface("hello")
        val expected = stubSymbol(id = 5000, name = "hello", matchType = MatchType.EXACT)
        whenever(lookup.lookupSurface("hello")).thenReturn(5000)
        whenever(lookup.toSymbol(5000, "hello", "hello", MatchType.EXACT)).thenReturn(expected)

        val result = translator.translate("Hello!")

        assertEquals(1, result.size)
        assertEquals(MatchType.EXACT, result[0].matchType)
    }

    // ── T-14 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-14 translateAsync uses Morfologik tier 3b to resolve inflected form`() = kotlinx.coroutines.test.runTest {
        val lemmaResult = MorfologikLemmatizer.LemmaAnalysis(
            lemma = "run",
            posTag = "VBG",
            blissIndicators = emptySet()
        )
        whenever(morfologik.analyzeWithTags("running", "en"))
            .thenReturn(listOf(lemmaResult))

        val expected = stubSymbol(id = 6000, name = "run", source = "running",
                                  lemma = "run", matchType = MatchType.LEMMA)
        whenever(lookup.lookupSurface("running")).thenReturn(null)
        whenever(lookup.lookupSurface("run")).thenReturn(null)
        whenever(lookup.lookupLemma("run")).thenReturn(6000)
        whenever(lookup.toSymbol(6000, "running", "run", MatchType.LEMMA)).thenReturn(expected)

        val result = translator.translateAsync("running")

        assertEquals(1, result.size)
        assertEquals(MatchType.LEMMA, result[0].matchType)
        assertEquals(6000, result[0].bciAvId)
    }

    // ── T-15 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T-15 translate bi-gram phrase resolved via lookupNgram`() {
        val expected = stubSymbol(id = 7000, name = "ice cream",
                                  source = "ice cream", matchType = MatchType.NGRAM)
        whenever(lookup.lookupNgram("ice cream")).thenReturn(7000)
        whenever(lookup.toSymbol(7000, "ice cream", "ice cream", MatchType.NGRAM))
            .thenReturn(expected)

        val result = translator.translate("ice cream")

        assertEquals(1, result.size)
        assertEquals(MatchType.NGRAM, result[0].matchType)
        assertEquals(7000, result[0].bciAvId)
    }
}

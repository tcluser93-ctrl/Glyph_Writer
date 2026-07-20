package com.blueapps.egyptianwriter.bliss

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

/**
 * BlissTranslatorTest — Patch 16
 *
 * 15 test JVM puri. [BlissLookup] e [MorfologikLemmatizer] sono stubbed con
 * Mockito-Kotlin — zero dipendenze da Room / assets / Android SDK.
 *
 * ## Fix (enterprise-grade audit, 2026-07-20)
 * Questo file non compilava affatto contro l'API reale di [BlissTranslator]:
 * - `BlissSymbol`/`BlissRenderAttachment` erano costruiti con un parametro
 *   `gloss` che non esiste (vedi [BlissSymbol] — `gloss` è una proprietà
 *   derivata da `name`, non un campo del costruttore).
 * - Il costruttore veniva chiamato con `lemmatizer = ...`, ma il parametro
 *   reale si chiama `morfologik`.
 * - `lookup.lookupSurface()`/`lookupLemma()` venivano mockati per
 *   restituire un `BlissSymbol?` intero, ma la firma reale restituisce
 *   `Int?` (il solo BCI-AV id) — la costruzione del `BlissSymbol` avviene
 *   dopo, via `lookup.toSymbol(id, source, lemma, matchType)`.
 * - `lemmatizer.lemmatize(word)` veniva mockato con un solo argomento e
 *   senza contesto coroutine, ma è `suspend fun lemmatize(word, lang)`;
 *   inoltre la pipeline reale (tier 3b) usa `analyzeWithTags(word, lang)`,
 *   non `lemmatize()`.
 * Riscritto per rispecchiare la pipeline reale a più livelli descritta nel
 * KDoc di [BlissTranslator] (tier 0 function-word → 3a exact → 3b Morfologik
 * → 3c lemma → 3d POS-aware → 3e de-affissazione → 3f Room FTS4 →
 * 3g composizione semantica → 3h UNKNOWN).
 *
 * Esecuzione:
 *   ./gradlew :app:test --tests "com.blueapps.egyptianwriter.bliss.BlissTranslatorTest"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlissTranslatorTest {

    // ── dipendenze stubbed ────────────────────────────────────────────────────
    private lateinit var lookup:     BlissLookup
    private lateinit var lemmatizer: MorfologikLemmatizer
    private lateinit var translator: BlissTranslator

    /**
     * Mappa id BCI-AV → nome, usata dallo stub di [BlissLookup.toSymbol] per
     * costruire lo stesso identico [BlissSymbol] che costruirebbe la vera
     * implementazione (che internamente chiama `nameOf(id)`). Un mock() non
     * esegue mai il corpo reale del metodo mockato, quindi questa mappa è la
     * "fonte di verità" locale del test per ciascun id usato.
     */
    private val symbolNames = mapOf(
        12001 to "house",
        12002 to "run",
        12005 to "jump",
        12010 to "cat",
        12020 to "go",
        12030 to "walk",
        12040 to "dog",
        12050 to "hello",
        12060 to "eat",
        12070 to "ice cream"
    )

    @BeforeEach
    fun setUp() {
        lookup     = mock()
        lemmatizer = mock()

        // Default: lookup pronto, nessun risultato su nessun tier.
        whenever(lookup.isReady).thenReturn(true)
        whenever(lookup.currentLang).thenReturn("en")
        whenever(lookup.lookupSurface(any())).thenReturn(null)
        whenever(lookup.lookupLemma(any())).thenReturn(null)
        whenever(lookup.lookupLemmaPos(any(), any())).thenReturn(null)
        whenever(lookup.lookupNgram(any())).thenReturn(null)
        runBlocking { whenever(lookup.lookupSurfaceDb(any())).thenReturn(null) }
        runBlocking { whenever(lemmatizer.analyzeWithTags(any(), any())).thenReturn(emptyList()) }

        // toSymbol() è un metodo concreto (non suspend) di BlissLookup: dato
        // che `lookup` è un mock() e non uno spy(), il suo corpo reale non
        // viene mai eseguito — va stubbato esplicitamente per riprodurre lo
        // stesso comportamento (bciAvId=id, name=nameOf(id), sourceWord,
        // lemma, matchType passati attraverso invariati).
        whenever(lookup.toSymbol(any(), any(), any(), any())).thenAnswer { inv ->
            val id     = inv.getArgument<Int>(0)
            val source = inv.getArgument<String>(1)
            val lemma  = inv.getArgument<String>(2)
            val mt     = inv.getArgument<BlissSymbol.MatchType>(3)
            BlissSymbol(
                bciAvId    = id,
                name       = symbolNames[id] ?: "sym$id",
                sourceWord = source,
                lemma      = lemma,
                matchType  = mt
            )
        }

        translator = BlissTranslator(lookup = lookup, morfologik = lemmatizer)
    }

    // ── T-01 ─────────────────────────────────────────────────────────────────
    /** Input vuoto deve restituire lista vuota senza crash. */
    @Test
    fun t01_emptyInput_returnsEmptyList() = runTest {
        val result = translator.translateAsync("")
        assertTrue(result.isEmpty(), "Expected empty list for empty input")
    }

    // ── T-02 ─────────────────────────────────────────────────────────────────
    /** Se lookup.isReady è false, la traduzione si interrompe. */
    @Test
    fun t02_lookupNotReady_returnsEmptyList() = runTest {
        whenever(lookup.isReady).thenReturn(false)
        val result = translator.translateAsync("hello world")
        assertTrue(result.isEmpty(), "Expected empty list when lookup not ready")
    }

    // ── T-03 ─────────────────────────────────────────────────────────────────
    /** Tier 3a: token trovato via lookupSurface → MatchType.EXACT. */
    @Test
    fun t03_singleToken_exactMatch() = runTest {
        whenever(lookup.lookupSurface("house")).thenReturn(12001)
        val result = translator.translateAsync("house")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.EXACT, result[0].matchType)
        assertEquals("house", result[0].name)
    }

    // ── T-04 ─────────────────────────────────────────────────────────────────
    /** Tier 3c: surface miss → plain lemma lookup sulla forma grezza. */
    @Test
    fun t04_singleToken_lemmaMatch() = runTest {
        whenever(lookup.lookupSurface("running")).thenReturn(null)
        whenever(lookup.lookupLemma("running")).thenReturn(12002)
        val result = translator.translateAsync("running")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.LEMMA, result[0].matchType)
        assertEquals("run", result[0].name)
    }

    // ── T-05 ─────────────────────────────────────────────────────────────────
    /** Tier 3e: de-affissazione suffix "-ing" → candidato "jump" trovato. */
    @Test
    fun t05_deAffix_ing_suffix() = runTest {
        whenever(lookup.lookupSurface("jumping")).thenReturn(null)
        whenever(lookup.lookupLemma("jump")).thenReturn(12005)
        val result = translator.translateAsync("jumping")
        assertEquals(1, result.size, "Result must have exactly 1 element")
        assertEquals(BlissSymbol.MatchType.LEMMA, result[0].matchType)
        assertEquals("jump", result[0].name)
    }

    // ── T-06 ─────────────────────────────────────────────────────────────────
    /** Tier 3h: token sconosciuto → UNKNOWN con bciAvId == UNKNOWN_SYMBOL_ID. */
    @Test
    fun t06_unknownToken_fallsBackToUnknown() = runTest {
        val result = translator.translateAsync("xyzzy")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.UNKNOWN, result[0].matchType)
        assertEquals(BlissSymbol.UNKNOWN_SYMBOL_ID, result[0].bciAvId)
    }

    // ── T-07 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: keyword "many" → BlissIndicator.PLURAL a livello frase. */
    @Test
    fun t07_detectIndicators_many_plural() = runTest {
        whenever(lookup.lookupSurface("cats")).thenReturn(12010)
        val result = translator.translateAsync("many cats")
        val catSymbol = result.firstOrNull { it.name == "cat" }
        assertNotNull(catSymbol, "Symbol 'cat' not found in result")
        assertTrue(
            catSymbol!!.indicators.any { it == BlissIndicator.PLURAL },
            "Expected BlissIndicator.PLURAL in indicators"
        )
    }

    // ── T-08 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: "will" → BlissIndicator.FUTURE a livello frase. */
    @Test
    fun t08_detectIndicators_will_future() = runTest {
        whenever(lookup.lookupSurface("go")).thenReturn(12020)
        val result = translator.translateAsync("will go")
        val goSymbol = result.firstOrNull { it.name == "go" }
        assertNotNull(goSymbol, "Symbol 'go' not found")
        assertTrue(
            goSymbol!!.indicators.any { it == BlissIndicator.FUTURE },
            "Expected BlissIndicator.FUTURE"
        )
    }

    // ── T-09 ─────────────────────────────────────────────────────────────────
    /** detectIndicators: "had walked" → BlissIndicator.PAST a livello frase. */
    @Test
    fun t09_detectIndicators_had_past() = runTest {
        whenever(lookup.lookupSurface("walked")).thenReturn(12030)
        val result = translator.translateAsync("had walked")
        val walkSymbol = result.firstOrNull { it.name == "walk" }
        assertNotNull(walkSymbol, "Symbol 'walk' not found")
        assertTrue(
            walkSymbol!!.indicators.any { it == BlissIndicator.PAST },
            "Expected BlissIndicator.PAST"
        )
    }

    // ── T-10 ─────────────────────────────────────────────────────────────────
    /** attachIndicators non attacca indicatori a simboli UNKNOWN. */
    @Test
    fun t10_attachIndicators_skipsUnknown() = runTest {
        val result = translator.translateAsync("many zfoobar")
        val unknown = result.firstOrNull { it.matchType == BlissSymbol.MatchType.UNKNOWN }
        assertNotNull(unknown, "Expected an UNKNOWN symbol")
        assertTrue(
            unknown!!.indicators.isEmpty(),
            "UNKNOWN symbols must not receive indicators"
        )
    }

    // ── T-11 ─────────────────────────────────────────────────────────────────
    /**
     * attachIndicators non ri-attacca se il simbolo arriva già con indicatori
     * non vuoti. Scenario realistico: tier 3b (Morfologik) rileva già PLURAL
     * dal tag morfologico e lo attacca subito via [BlissSymbol.withIndicators];
     * il pass a livello frase (regex-based, "many" → PLURAL) non deve
     * duplicarlo.
     */
    @Test
    fun t11_attachIndicators_doesNotDoubleAttach() = runTest {
        whenever(lookup.lookupSurface("house")).thenReturn(12001)
        runBlocking {
            whenever(lemmatizer.analyzeWithTags("houses", "en")).thenReturn(listOf(
                LemmaAnalysis(lemma = "house", blissIndicators = listOf(BlissIndicator.PLURAL))
            ))
        }
        val result = translator.translateAsync("many houses")
        val sym = result.firstOrNull { it.name == "house" }
        assertNotNull(sym)
        assertEquals(
            1,
            sym!!.indicators.count { it == BlissIndicator.PLURAL },
            "Indicators must not be duplicated"
        )
    }

    // ── T-12 ─────────────────────────────────────────────────────────────────
    /** attachIndicators attacca correttamente a un EXACT con indicators vuoti. */
    @Test
    fun t12_attachIndicators_attachesToExact() = runTest {
        whenever(lookup.lookupSurface("dogs")).thenReturn(12040)
        val result = translator.translateAsync("many dogs")
        val sym = result.firstOrNull { it.name == "dog" }
        assertNotNull(sym)
        assertTrue(
            sym!!.indicators.any { it == BlissIndicator.PLURAL },
            "Expected BlissIndicator.PLURAL on EXACT symbol"
        )
    }

    // ── T-13 ─────────────────────────────────────────────────────────────────
    /** Normalise: punteggiatura rimossa, lowercase, trim. */
    @Test
    fun t13_normalise_stripsAndLowers() = runTest {
        whenever(lookup.lookupSurface("hello")).thenReturn(12050)
        val result = translator.translateAsync("  Hello!  ")
        assertEquals(1, result.size)
        assertEquals("hello", result[0].name)
    }

    // ── T-14 ─────────────────────────────────────────────────────────────────
    /** Tier 3b: Morfologik mock risolve forma flessa → lemma "eat". */
    @Test
    fun t14_translateAsync_morfologikResolves() = runTest {
        whenever(lookup.lookupSurface("ate")).thenReturn(null)
        runBlocking {
            whenever(lemmatizer.analyzeWithTags("ate", "en")).thenReturn(listOf(
                LemmaAnalysis(lemma = "eat")
            ))
        }
        whenever(lookup.lookupSurface("eat")).thenReturn(12060)
        val result = translator.translateAsync("ate")
        assertEquals(1, result.size)
        assertEquals(BlissSymbol.MatchType.LEMMA, result[0].matchType)
        assertEquals("eat", result[0].name)
    }

    // ── T-15 ─────────────────────────────────────────────────────────────────
    /** N-gram bi-token risolto via lookupNgram (priorità sui singoli token). */
    @Test
    fun t15_biToken_ngram() = runTest {
        whenever(lookup.lookupNgram("ice cream")).thenReturn(12070)
        val result = translator.translateAsync("ice cream")
        assertTrue(
            result.any { it.matchType == BlissSymbol.MatchType.NGRAM },
            "Expected at least one NGRAM result"
        )
    }
}

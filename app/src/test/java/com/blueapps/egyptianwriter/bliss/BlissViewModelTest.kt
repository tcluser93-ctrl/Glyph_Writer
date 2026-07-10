package com.blueapps.egyptianwriter.bliss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for [BlissViewModel].
 *
 * [MatchType] alias is declared in BlissTestUtils.kt (package-internal).
 *
 * Note: InstantTaskExecutorRule (JUnit 4) is NOT used here — this suite tests
 * only StateFlow-based state via FakeViewModel, which does not require the
 * ArchTaskExecutor override.
 *
 * ## Patch 9 additions
 * - [UiStateTests.renderModeDefaultIsClassic]: asserts [UiState.renderMode] defaults to CLASSIC.
 * - [UiStateTests.composedWordsDefaultIsEmpty]: asserts [UiState.composedWords] defaults empty.
 * - [StatsTests.statsCountsSemanticBucket]: asserts [TranslationStats.semantic] counts SEMANTIC symbols.
 * - [StatsTests.statsMixedWithSemantic]: asserts SEMANTIC counted separately from EXACT/LEMMA.
 * - [StateMutations.setRenderModeStructured]: FakeViewModel helper sets renderMode=STRUCTURED.
 * - [StateMutations.setComposedWords]: FakeViewModel helper sets composedWords list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("BlissViewModel — state, suggestions, stats")
class BlissViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── UiState tests (pure data class — no ViewModel instance needed) ─────────

    @Nested
    @DisplayName("UiState data class")
    inner class UiStateTests {

        @Test
        @DisplayName("Default UiState has empty collections and sensible defaults")
        fun initialStateDefaults() {
            val state = BlissViewModel.UiState()
            assertTrue(state.symbols.isEmpty())
            assertNull(state.glyphXDoc)
            assertNull(state.stats)
            assertEquals("it", state.langCode)
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertTrue(state.suggestions.isEmpty())
            assertTrue(state.history.isEmpty())
            assertTrue(state.recentInputs.isEmpty())
            assertFalse(state.historyVisible)
        }

        @Test
        @DisplayName("copy() creates a new instance without mutating the original")
        fun uiStateCopyImmutability() {
            val original = BlissViewModel.UiState(langCode = "it")
            val modified = original.copy(langCode = "en", isLoading = true)
            assertEquals("it", original.langCode)
            assertFalse(original.isLoading)
            assertEquals("en", modified.langCode)
            assertTrue(modified.isLoading)
        }

        // ── Patch 9: renderMode and composedWords ──────────────────────────

        @Test
        @DisplayName("Patch 9 — default UiState.renderMode is CLASSIC")
        fun renderModeDefaultIsClassic() {
            val state = BlissViewModel.UiState()
            assertEquals(BlissViewModel.RenderMode.CLASSIC, state.renderMode)
        }

        @Test
        @DisplayName("Patch 9 — default UiState.composedWords is empty")
        fun composedWordsDefaultIsEmpty() {
            val state = BlissViewModel.UiState()
            assertTrue(state.composedWords.isEmpty())
        }

        @Test
        @DisplayName("Patch 9 — copy with renderMode=STRUCTURED preserves other fields")
        fun copyWithStructuredRenderMode() {
            val base     = BlissViewModel.UiState(langCode = "it")
            val modified = base.copy(renderMode = BlissViewModel.RenderMode.STRUCTURED)
            assertEquals(BlissViewModel.RenderMode.STRUCTURED, modified.renderMode)
            assertEquals("it", modified.langCode)
        }

        @Test
        @DisplayName("Patch 9 — copy with composedWords non-empty preserves other fields")
        fun copyWithComposedWords() {
            val base     = BlissViewModel.UiState(langCode = "en")
            val modified = base.copy(composedWords = listOf(null, null))
            assertEquals(2, modified.composedWords.size)
            assertEquals("en", modified.langCode)
        }
    }

    // ── TranslationStats tests (pure companion factory — no ViewModel needed) ────

    @Nested
    @DisplayName("TranslationStats")
    inner class StatsTests {

        private fun sym(mt: MatchType) = BlissSymbol(
            bciAvId    = 1,
            name       = "x",
            matchType  = mt,
            sourceWord = "x"
        )

        @Test
        @DisplayName("from() empty list → all zeros, coverage = 0.0")
        fun statsEmpty() {
            val s = TranslationStats.from(emptyList<BlissSymbol>())
            assertEquals(0, s.total)
            assertEquals(0f, s.coverage)
        }

        @Test
        @DisplayName("from() all EXACT → exact = total, coverage = 1.0")
        fun statsAllExact() {
            val s = TranslationStats.from(List(3) { sym(MatchType.EXACT) })
            assertEquals(3, s.total)
            assertEquals(3, s.exact)
            assertEquals(0, s.unknown)
            assertEquals(1.0f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("from() mixed types counts each bucket correctly")
        fun statsMixed() {
            val symbols = listOf(
                sym(MatchType.EXACT),
                sym(MatchType.LEMMA),
                sym(MatchType.NGRAM),
                sym(MatchType.UNKNOWN)
            )
            val s = TranslationStats.from(symbols)
            assertEquals(4, s.total)
            assertEquals(1, s.exact)
            assertEquals(1, s.lemma)
            assertEquals(1, s.ngram)
            assertEquals(1, s.unknown)
        }

        @Test
        @DisplayName("coverage = 1.0 when no UNKNOWNs")
        fun coverageFullWhenNoUnknowns() {
            val s = TranslationStats.from(List(5) { sym(MatchType.EXACT) })
            assertEquals(1.0f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("coverage = 0.0 when all UNKNOWN")
        fun coverageZeroWhenAllUnknowns() {
            val s = TranslationStats.from(List(4) { sym(MatchType.UNKNOWN) })
            assertEquals(0.0f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("coverage = 0.5 for half UNKNOWN, half EXACT")
        fun coverageHalf() {
            val s = TranslationStats.from(
                List(2) { sym(MatchType.EXACT) } + List(2) { sym(MatchType.UNKNOWN) }
            )
            assertEquals(0.5f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("coverage = 0.0 for empty list")
        fun coverageEmptyList() {
            assertEquals(0f, TranslationStats.from(emptyList<BlissSymbol>()).coverage)
        }

        // ── Patch 9: semantic counter ──────────────────────────────────────

        @Test
        @DisplayName("Patch 9 — from() counts SEMANTIC symbols in .semantic field")
        fun statsCountsSemanticBucket() {
            val symbols = List(3) { sym(MatchType.SEMANTIC) }
            val s = TranslationStats.from(symbols)
            assertEquals(3, s.semantic)
            assertEquals(3, s.total)
            assertEquals(0, s.unknown)
            assertEquals(1.0f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("Patch 9 — from() mixed with SEMANTIC counts each bucket independently")
        fun statsMixedWithSemantic() {
            val symbols = listOf(
                sym(MatchType.EXACT),
                sym(MatchType.SEMANTIC),
                sym(MatchType.SEMANTIC),
                sym(MatchType.UNKNOWN)
            )
            val s = TranslationStats.from(symbols)
            assertEquals(4,    s.total)
            assertEquals(1,    s.exact)
            assertEquals(2,    s.semantic)
            assertEquals(1,    s.unknown)
            assertEquals(0.75f, s.coverage, 0.001f)
        }

        @Test
        @DisplayName("Patch 9 — semantic=0 when no SEMANTIC symbols present")
        fun statsSemanticZeroWhenAbsent() {
            val s = TranslationStats.from(List(3) { sym(MatchType.EXACT) })
            assertEquals(0, s.semantic)
        }
    }

    // ── StateFlow / ViewModel state mutation tests ─────────────────────────────────────

    private class FakeViewModel {
        private val _state = MutableStateFlow(BlissViewModel.UiState())
        val uiState get() = _state.value

        fun setError(msg: String?) {
            _state.value = _state.value.copy(error = msg, isLoading = false)
        }
        fun clearError() {
            _state.value = _state.value.copy(error = null)
        }
        fun clearSuggestions() {
            _state.value = _state.value.copy(suggestions = emptyList())
        }
        fun toggleHistoryPanel() {
            _state.value = _state.value.copy(historyVisible = !_state.value.historyVisible)
        }
        fun translateWithoutTranslator() {
            _state.value = _state.value.copy(error = "Engine not ready")
        }
        fun onSuggestionQueryShortPrefix(text: String) {
            val prefix = text.trimEnd().substringAfterLast(' ').lowercase()
            if (prefix.length < BlissViewModel.MIN_PREFIX_LEN) {
                if (_state.value.suggestions.isNotEmpty()) {
                    _state.value = _state.value.copy(suggestions = emptyList())
                }
            }
        }
        // Patch 9 helpers
        fun setRenderMode(mode: BlissViewModel.RenderMode) {
            _state.value = _state.value.copy(renderMode = mode)
        }
        fun setComposedWords(words: List<ComposedBlissWord?>) {
            _state.value = _state.value.copy(composedWords = words)
        }
    }

    @Nested
    @DisplayName("State mutations")
    inner class StateMutations {

        private val vm = FakeViewModel()

        @Test
        @DisplayName("setError() sets error field and clears isLoading")
        fun setErrorSetsState() {
            vm.setError("Asset not found")
            assertEquals("Asset not found", vm.uiState.error)
            assertFalse(vm.uiState.isLoading)
        }

        @Test
        @DisplayName("clearError() sets error = null")
        fun clearErrorClearsState() {
            vm.setError("oops")
            vm.clearError()
            assertNull(vm.uiState.error)
        }

        @Test
        @DisplayName("clearSuggestions() empties the suggestions list")
        fun clearSuggestionsEmptiesList() {
            val field = FakeViewModel::class.java.getDeclaredField("_state")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(vm) as MutableStateFlow<BlissViewModel.UiState>
            flow.value = flow.value.copy(suggestions = listOf("walk", "run"))
            vm.clearSuggestions()
            assertTrue(vm.uiState.suggestions.isEmpty())
        }

        @Test
        @DisplayName("toggleHistoryPanel() flips historyVisible true")
        fun toggleHistoryPanelToggles() {
            assertFalse(vm.uiState.historyVisible)
            vm.toggleHistoryPanel()
            assertTrue(vm.uiState.historyVisible)
        }

        @Test
        @DisplayName("toggleHistoryPanel() twice → back to false")
        fun toggleHistoryPanelTwice() {
            vm.toggleHistoryPanel()
            vm.toggleHistoryPanel()
            assertFalse(vm.uiState.historyVisible)
        }

        @Test
        @DisplayName("translate() without translator → error = 'Engine not ready'")
        fun translateWithoutTranslatorSetsError() {
            vm.translateWithoutTranslator()
            assertEquals("Engine not ready", vm.uiState.error)
        }

        @Test
        @DisplayName("onSuggestionQuery with 1-char prefix clears suggestions")
        fun shortPrefixClearsSuggestions() {
            val field = FakeViewModel::class.java.getDeclaredField("_state")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(vm) as MutableStateFlow<BlissViewModel.UiState>
            flow.value = flow.value.copy(suggestions = listOf("walk"))
            vm.onSuggestionQueryShortPrefix("a")
            assertTrue(vm.uiState.suggestions.isEmpty())
        }

        @Test
        @DisplayName("onSuggestionQuery with empty string does not crash")
        fun oneLengthBoundary() {
            assertDoesNotThrow { vm.onSuggestionQueryShortPrefix("") }
        }

        // ── Patch 9: renderMode and composedWords mutations ────────────────

        @Test
        @DisplayName("Patch 9 — setRenderMode(STRUCTURED) transitions renderMode correctly")
        fun setRenderModeStructured() {
            assertEquals(BlissViewModel.RenderMode.CLASSIC, vm.uiState.renderMode)
            vm.setRenderMode(BlissViewModel.RenderMode.STRUCTURED)
            assertEquals(BlissViewModel.RenderMode.STRUCTURED, vm.uiState.renderMode)
        }

        @Test
        @DisplayName("Patch 9 — setRenderMode(CLASSIC) after STRUCTURED reverts correctly")
        fun setRenderModeBackToClassic() {
            vm.setRenderMode(BlissViewModel.RenderMode.STRUCTURED)
            vm.setRenderMode(BlissViewModel.RenderMode.CLASSIC)
            assertEquals(BlissViewModel.RenderMode.CLASSIC, vm.uiState.renderMode)
        }

        @Test
        @DisplayName("Patch 9 — setComposedWords stores list with null entries")
        fun setComposedWordsWithNulls() {
            vm.setComposedWords(listOf(null, null, null))
            assertEquals(3, vm.uiState.composedWords.size)
            assertTrue(vm.uiState.composedWords.all { it == null })
        }

        @Test
        @DisplayName("Patch 9 — setComposedWords empty list resets to empty")
        fun setComposedWordsEmpty() {
            vm.setComposedWords(emptyList())
            assertTrue(vm.uiState.composedWords.isEmpty())
        }
    }

    @Nested
    @DisplayName("Constants")
    inner class Constants {

        @Test
        @DisplayName("MIN_PREFIX_LEN == 2")
        fun minPrefixLen() = assertEquals(2, BlissViewModel.MIN_PREFIX_LEN)

        @Test
        @DisplayName("MAX_SUGGESTIONS == 8")
        fun maxSuggestions() = assertEquals(8, BlissViewModel.MAX_SUGGESTIONS)
    }
}

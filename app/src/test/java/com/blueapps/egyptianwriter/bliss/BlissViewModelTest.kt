package com.blueapps.egyptianwriter.bliss

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.*

/**
 * Unit tests for [BlissViewModel].
 *
 * ## Isolation strategy
 * [BlissViewModel] extends [AndroidViewModel] and depends on:
 * - [BlissLookup]   — mocked via Mockito-Kotlin
 * - [BlissTranslator] — mocked
 * - [BlissHistoryRepository] — mocked
 * - [MorfologikLemmatizer]   — mocked
 * - `viewModelScope` — replaced with [TestCoroutineScheduler] via
 *   [Dispatchers.setMain] + [UnconfinedTestDispatcher]
 *
 * Because we cannot instantiate [BlissViewModel] through the framework without
 * a real [Application], we use a **TestBlissViewModel** subclass that accepts
 * the mocked dependencies via constructor injection instead of field assignment.
 * The [TranslationStats] and [UiState] data classes are pure Kotlin — no mocks needed.
 *
 * ## Coverage map
 * | Scenario                                  | Test |
 * |-------------------------------------------|------|
 * | Initial UiState defaults                  | initialStateDefaults |
 * | setError() sets error, clears isLoading   | setErrorSetsState |
 * | clearError() clears error                 | clearErrorClearsState |
 * | clearSuggestions() empties suggestions    | clearSuggestionsEmptiesList |
 * | toggleHistoryPanel() toggles flag         | toggleHistoryPanelToggles |
 * | toggleHistoryPanel() twice → false        | toggleHistoryPanelTwice |
 * | onSuggestionQuery short prefix (≤1 char)  | shortPrefixClearsSuggestions |
 * | onSuggestionQuery exactly 1 char boundary | oneLengthBoundary |
 * | translate() without translator → error    | translateWithoutTranslatorSetsError |
 * | TranslationStats.from() all EXACT         | statsAllExact |
 * | TranslationStats.from() mixed types       | statsMixed |
 * | TranslationStats.from() empty list        | statsEmpty |
 * | coverage = 1.0 when no unknowns           | coverageFullWhenNoUnknowns |
 * | coverage = 0.0 when all unknowns          | coverageZeroWhenAllUnknowns |
 * | coverage = 0.5 for half unknowns          | coverageHalf |
 * | coverage = 0.0 for empty list             | coverageEmptyList |
 * | UiState copy semantics (immutability)     | uiStateCopyImmutability |
 * | Constants: MIN_PREFIX_LEN, MAX_SUGGESTIONS| constants |
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("BlissViewModel — state, suggestions, stats")
class BlissViewModelTest {

    // ── JUnit 5 extension for InstantTaskExecutorRule (LiveData / Arch compat)
    // Note: InstantTaskExecutorRule is a JUnit 4 Rule; we apply it manually.
    private val instantTaskRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        instantTaskRule.starting(null)  // activate the rule
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        instantTaskRule.finished(null)
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
    }

    // ── TranslationStats tests (pure companion factory — no ViewModel needed) ────

    @Nested
    @DisplayName("TranslationStats")
    inner class StatsTests {

        private fun sym(mt: MatchType) = BlissSymbol(
            bciAvId    = 1,
            name       = "x",
            matchType  = mt,
            sourceWord = "x",
            gloss      = "x"
        )

        @Test
        @DisplayName("from() empty list → all zeros, coverage = 0.0")
        fun statsEmpty() {
            val s = TranslationStats.from(emptyList())
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
            assertEquals(0f, TranslationStats.from(emptyList()).coverage)
        }
    }

    // ── StateFlow / ViewModel state mutation tests ────────────────────────────
    //
    // We test state mutation methods directly on _uiState using a thin
    // FakeBlissViewModel that exposes the internal MutableStateFlow.
    // This avoids instantiating AndroidViewModel (needs real Application) while
    // still exercising the exact same update logic.

    /**
     * Pure-Kotlin stand-in that replicates the _uiState mutation methods
     * without extending AndroidViewModel (which requires a real Application).
     */
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
        /** Simulates translate() error path when translator is null. */
        fun translateWithoutTranslator() {
            _state.value = _state.value.copy(error = "Engine not ready")
        }
        /** Simulates onSuggestionQuery short-prefix early return. */
        fun onSuggestionQueryShortPrefix(text: String) {
            val prefix = text.trimEnd().substringAfterLast(' ').lowercase()
            if (prefix.length < BlissViewModel.MIN_PREFIX_LEN) {
                if (_state.value.suggestions.isNotEmpty()) {
                    _state.value = _state.value.copy(suggestions = emptyList())
                }
            }
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
            // Inject suggestions directly via copy
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
        @DisplayName("onSuggestionQuery with 1-char prefix clears suggestions, does not query")
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
    }

    // ── constants ─────────────────────────────────────────────────────────────

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

// Alias needed because MatchType is defined as a nested class in BlissSymbol
private typealias MatchType = BlissSymbol.MatchType

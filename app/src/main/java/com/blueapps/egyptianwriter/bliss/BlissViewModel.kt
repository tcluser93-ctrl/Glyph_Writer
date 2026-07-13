package com.blueapps.egyptianwriter.bliss

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import com.blueapps.egyptianwriter.R

/**
 * ViewModel for the Bliss translation screen.
 *
 * ## State
 * All UI state is consolidated in the immutable [UiState] data class and
 * exposed via a single [uiState] [StateFlow].  The Fragment collects this
 * flow and derives every visible element from it.
 *
 * ## Features
 * - **History persistence**: every successful translation is saved to
 *   [BlissHistoryRepository] (Room `bliss_history` table, max 100 rows).
 * - **Typeahead suggestions**: [onSuggestionQuery] triggers an FTS4 prefix
 *   search and emits BCI names as [UiState.suggestions].
 * - **History panel**: [toggleHistoryPanel] / [clearHistory].
 * - **History search (E-04)**: [setHistorySearch] drives [UiState.filteredHistory].
 *
 * ## Patch 8 — semantic render dispatch
 *
 * [BlissSemanticComposer] is now instantiated in [setLang] (alongside
 * [BlissTranslator]) so tier 3g is active at runtime, not just in tests.
 *
 * [UiState] carries two new fields:
 * - [UiState.composedWords] — the `List<ComposedBlissWord?>` produced by
 *   tier 3g for each input token that matched semantically.  Null entries
 *   correspond to tokens resolved by earlier tiers (EXACT / LEMMA / …).
 * - [UiState.renderMode] — [RenderMode.STRUCTURED] when at least one entry in
 *   [composedWords] is non-null; [RenderMode.CLASSIC] otherwise.
 *
 * The Fragment must check [UiState.renderMode] and call:
 * - [BlissRenderer.renderWithAttachments] for the first non-null `ComposedBlissWord`
 *   when `renderMode == STRUCTURED`.
 * - [BlissRenderer.render] with [UiState.glyphXDoc] otherwise (classic path).
 *
 * [TranslationStats] now includes a [TranslationStats.semantic] counter.
 *
 * ## Concurrency model
 * | Job | Dispatcher | Cancellation |
 * |---|---|---|
 * | [translateJob]  | Default (CPU)  | Cancelled on each new [translate] call |
 * | [suggestJob]    | IO (FTS4 DB)   | Cancelled on each [onSuggestionQuery] call |
 * | [historyJob]    | Main (Flow)    | Cancelled when lang changes |
 * | [searchJob]     | Main (Flow)    | Cancelled on each [setHistorySearch] call |
 *
 * @constructor Created by the framework via [AndroidViewModel].
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── render mode ────────────────────────────────────────────────────────────

    /**
     * Governs which [BlissRenderer] entry point the Fragment should invoke.
     *
     * - [CLASSIC]    — Use [BlissRenderer.render] with [UiState.glyphXDoc].
     *   All tokens were resolved via EXACT / LEMMA / NGRAM / UNKNOWN tiers.
     * - [STRUCTURED] — Use [BlissRenderer.renderWithAttachments] with the
     *   first non-null entry in [UiState.composedWords].
     *   At least one token was resolved by tier 3g ([BlissSemanticComposer]).
     */
    enum class RenderMode { CLASSIC, STRUCTURED }

    /**
     * Controls which output view the Fragment renders in the result area.
     * Orthogonal to [RenderMode] (which drives the SVG renderer path):
     * - [CHIPS]  — Horizontal chip-strip (E-01) — default.
     * - [CARDS]  — CAA mini-card grid ([BlissSymbolCardAdapter]).
     * - [MIXED]  — [MixedBlissRowView] composited preview.
     */
    enum class ViewMode { CHIPS, CARDS, MIXED }

    /**
     * One-shot UI events emitted via [events].
     * Collected by the Fragment in a `repeatOnLifecycle(STARTED)` block.
     */
    sealed class Event {
        /** Show a short [android.widget.Toast] with localised [message]. */
        data class ShowToast(val message: String) : Event()
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all UI state for the Bliss translation screen.
     *
     * @param symbols            Translated [BlissSymbol] list (all tiers).
     * @param glyphXDoc          GlyphX DOM document for [BlissRenderer.render];
     *                           null when [renderMode] is [RenderMode.STRUCTURED].
     * @param composedWords      Per-token [ComposedBlissWord] from tier 3g;
     *                           null entries = token resolved by an earlier tier.
     *                           Non-empty only when [renderMode] == [RenderMode.STRUCTURED].
     * @param renderMode         Dispatch hint for the Fragment renderer call.
     * @param stats              Coverage breakdown of the current translation.
     * @param langCode           Active ISO-639-1 language code.
     * @param isLoading          `true` while a translation is in progress.
     * @param error              Non-null when a terminal error has occurred.
     * @param suggestions        Typeahead suggestion labels.
     * @param history            Full (unfiltered) history list.
     * @param filteredHistory    Subset of [history] matching [historySearchQuery].
     * @param historySearchQuery Current text in the history search field.
     * @param recentInputs       Distinct recent input texts (autocomplete chips).
     * @param historyVisible     Whether the history panel is open.
     * @param viewMode           Which output view the Fragment is rendering.
     */
    data class UiState(
        val symbols:             List<BlissSymbol>          = emptyList(),
        val glyphXDoc:           Document?                  = null,
        val composedWords:       List<ComposedBlissWord?>   = emptyList(),
        val renderMode:          RenderMode                 = RenderMode.CLASSIC,
        val stats:               TranslationStats?          = null,
        val langCode:            String                     = DEFAULT_LANG,
        val isLoading:           Boolean                    = false,
        val error:               String?                    = null,
        val suggestions:         List<String>               = emptyList(),
        val history:             List<BlissHistoryEntry>    = emptyList(),
        val filteredHistory:     List<BlissHistoryEntry>    = emptyList(),
        val historySearchQuery:  String                     = "",
        val recentInputs:        List<String>               = emptyList(),
        val historyVisible:      Boolean                    = false,
        val viewMode:            ViewMode                   = ViewMode.CHIPS
    )

    /** Extension alias: [UiState.isLoading] → [UiState.loading] for Fragment convenience. */
    val UiState.loading: Boolean get() = isLoading

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    /** One-shot events for Fragment consumption. Never replayed. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _historySearchQuery = MutableStateFlow("")

    // ── engine components ───────────────────────────────────────────────────────

    private val lookup:     BlissLookup            = BlissLookup.getInstance(application)

    /**
     * Provides BCI-AV SVG drawables.  Shared with [BlissTranslateFragment] and
     * [BlissSymbolCardAdapter] to avoid duplicate asset loading.
     */
    val signProvider: BlissSignProvider = BlissSignProvider(application)

    private val morfologik: MorfologikLemmatizer   = MorfologikLemmatizer(application)
    private val repository: BlissHistoryRepository = BlissHistoryRepository(
        BlissDatabase.getInstance(application)
    )

    /**
     * [BlissSemanticComposer] instance shared across translations.
     * Initialised lazily in [setLang] once [lookup] is ready.
     * Patch 8: passed to [BlissTranslator] to activate tier 3g at runtime.
     */
    private var composer:     BlissSemanticComposer? = null
    private var translator:   BlissTranslator?       = null
    private var builder:      BlissGlyphXBuilder?    = null

    private var translateJob: Job? = null
    private var suggestJob:   Job? = null
    private var historyJob:   Job? = null
    private var inputsJob:    Job? = null
    private var searchJob:    Job? = null

    // ── language management ───────────────────────────────────────────────────

    /**
     * Loads the BCI-AV assets for [lang] and initialises the Room FTS4 DB.
     * Idempotent — no-op if [lang] equals the currently loaded language.
     *
     * ## Patch 8 change
     * On [onReady], [BlissSemanticComposer] is instantiated with the ready
     * [lookup] and passed to [BlissTranslator] as the `composer` parameter,
     * activating tier 3g in the async pipeline.
     */
    fun setLang(lang: String) {
        val normalised = lang.lowercase().take(2)
        if (lookup.isReady && lookup.currentLang == normalised) return
        _uiState.value = _uiState.value.copy(
            isLoading           = true,
            error               = null,
            langCode            = normalised,
            suggestions         = emptyList(),
            history             = emptyList(),
            filteredHistory     = emptyList(),
            historySearchQuery  = "",
            recentInputs        = emptyList()
        )
        _historySearchQuery.value = ""
        lookup.loadIfNeeded(
            lang    = normalised,
            scope   = viewModelScope,
            onReady = {
                // Patch 8: wire composer → translator so tier 3g is live
                composer   = BlissSemanticComposer(lookup)
                translator = BlissTranslator(lookup, morfologik, composer)
                viewModelScope.launch(Dispatchers.IO) { lookup.initDb() }
                _uiState.value = _uiState.value.copy(isLoading = false)
                startObservingHistory(normalised)
                startObservingRecentInputs(normalised)
                Log.i(TAG,
                    "Engine ready [lang=$normalised, " +
                    "morfologik=${morfologik.isAvailable(normalised)}, " +
                    "composer=active]"
                )
            },
            onError = { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                Log.e(TAG, "Engine load error", e)
            }
        )
    }

    /** Injects an adaptive [BlissGlyphXBuilder] built by the Fragment. */
    fun setBuilder(glyphXBuilder: BlissGlyphXBuilder) {
        builder = glyphXBuilder
    }

    /** Returns the current [BlissGlyphXBuilder], or null if not yet initialised. */
    fun getBuilder(): BlissGlyphXBuilder? = builder

    // ── translation ──────────────────────────────────────────────────────────────

    /**
     * Translates [text] using the full async pipeline (tiers 3a–3g active).
     * Cancels any in-flight translation before starting.
     * On success, saves to the history repository.
     *
     * ## Patch 8 change — render dispatch
     *
     * After [BlissTranslator.translateAsync] returns, each `BlissSymbol` whose
     * `matchType == SEMANTIC` indicates that tier 3g produced that symbol from
     * a `ComposedBlissWord`.  For those tokens, [BlissSemanticComposer.composeStructured]
     * is called again on the **original lemma** to re-obtain the full structured
     * result (with `renderAttachments`), since [BlissTranslator] only stores
     * the flat `BlissSymbol`.
     *
     * The resulting `List<ComposedBlissWord?>` is stored in [UiState.composedWords].
     * [UiState.renderMode] is set to:
     * - [RenderMode.STRUCTURED] if any entry is non-null.
     * - [RenderMode.CLASSIC]    if all entries are null (no semantic tokens).
     *
     * The Fragment is responsible for dispatching to the correct renderer path
     * based on [UiState.renderMode].
     */
    fun translate(text: String) {
        val t = translator ?: run {
            _uiState.value = _uiState.value.copy(
                error = getApplication<Application>().getString(R.string.bliss_error_engine_not_ready)
            )
            return
        }
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading    = true,
                error        = null,
                suggestions  = emptyList()
            )
            try {
                val lang = _uiState.value.langCode

                val symbols = withContext(Dispatchers.Default) {
                    t.translateAsync(text)
                }

                // Patch 8: resolve ComposedBlissWord for each SEMANTIC symbol
                val composedWords: List<ComposedBlissWord?> = withContext(Dispatchers.Default) {
                    symbols.map { sym ->
                        if (sym.matchType == BlissSymbol.MatchType.SEMANTIC) {
                            composer?.composeStructured(sym.lemma, lang)
                        } else null
                    }
                }

                val hasStructured = composedWords.any { it != null }
                val renderMode    = if (hasStructured) RenderMode.STRUCTURED else RenderMode.CLASSIC

                // Build GlyphX doc only for the classic path (avoids wasted work)
                val doc = if (renderMode == RenderMode.CLASSIC) {
                    withContext(Dispatchers.Default) { builder?.build(symbols) }
                } else null

                val stats = TranslationStats.from(symbols)

                _uiState.value = _uiState.value.copy(
                    symbols       = symbols,
                    glyphXDoc     = doc,
                    composedWords = composedWords,
                    renderMode    = renderMode,
                    stats         = stats,
                    isLoading     = false
                )

                viewModelScope.launch(Dispatchers.IO) {
                    repository.saveTranslation(
                        inputText = text.trim(),
                        langCode  = lang,
                        symbols   = symbols
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = e.message
                )
                Log.e(TAG, "Translation error", e)
            }
        }
    }

    // ── typeahead suggestions ───────────────────────────────────────────────────

    fun onSuggestionQuery(text: String) {
        val prefix = text.trimEnd().substringAfterLast(' ').lowercase()
        if (prefix.length < MIN_PREFIX_LEN) {
            if (_uiState.value.suggestions.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(suggestions = emptyList())
            }
            return
        }
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch(Dispatchers.IO) {
            val ids    = lookup.lookupPrefixDb(prefix, limit = MAX_SUGGESTIONS)
            val labels = ids.map { id -> lookup.nameOf(id) }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(suggestions = labels)
            }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(suggestions = emptyList())
    }

    // ── history panel ───────────────────────────────────────────────────────────

    fun toggleHistoryPanel() {
        _uiState.value = _uiState.value.copy(
            historyVisible = !_uiState.value.historyVisible
        )
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteEntry(id)
        }
    }

    /**
     * Restores [entry] into the active translation state.
     * Patch 8: resets [UiState.composedWords] to empty and [UiState.renderMode]
     * to [RenderMode.CLASSIC] since history entries do not store structured data.
     */
    fun restoreFromHistory(entry: BlissHistoryEntry) {
        val resolved = entry.symbolIds.map { id ->
            lookup.toSymbol(
                id     = id,
                source = lookup.nameOf(id),
                lemma  = lookup.nameOf(id),
                mt     = BlissSymbol.MatchType.EXACT
            )
        }
        val stats = TranslationStats.from(resolved)
        _uiState.value = _uiState.value.copy(
            symbols       = resolved,
            glyphXDoc     = null,
            composedWords = emptyList(),
            renderMode    = RenderMode.CLASSIC,
            stats         = stats,
            error         = null
        )
    }

    fun restoreHistoryEntry(entry: BlissHistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) { repository.insertEntry(entry) }
    }

    fun restoreHistoryEntries(entries: List<BlissHistoryEntry>) {
        viewModelScope.launch(Dispatchers.IO) { entries.forEach { repository.insertEntry(it) } }
    }

    fun clearHistory() {
        val lang = _uiState.value.langCode
        viewModelScope.launch(Dispatchers.IO) { repository.clearLang(lang) }
    }

    // ── history search (E-04) ────────────────────────────────────────────────

    /**
     * Updates the history search query.
     * The Fragment must debounce calls (≥ 300 ms) before invoking this.
     */
    fun setHistorySearch(query: String) {
        _historySearchQuery.value = query
        _uiState.value = _uiState.value.copy(historySearchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                filteredHistory = _uiState.value.history
            )
            return
        }
        val lang = _uiState.value.langCode
        searchJob = viewModelScope.launch {
            repository.searchHistory(query = query, langCode = lang)
                .collectLatest { results ->
                    _uiState.value = _uiState.value.copy(filteredHistory = results)
                }
        }
    }

    // ── view mode ────────────────────────────────────────────────────────────────

    /**
     * Changes the active output [ViewMode] (chip-strip / CAA cards / mixed preview).
     * Called by the Fragment's [MaterialButtonToggleGroup] listener.
     */
    fun setRenderMode(mode: ViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    /**
     * Resets the translation result to the empty state.
     * Cancels any in-flight [translateJob].
     */
    fun clearTranslation() {
        translateJob?.cancel()
        _uiState.value = _uiState.value.copy(
            symbols       = emptyList(),
            glyphXDoc     = null,
            composedWords = emptyList(),
            renderMode    = RenderMode.CLASSIC,
            stats         = null,
            error         = null,
            isLoading     = false
        )
    }

    // ── private: reactive observers ────────────────────────────────────────────

    private fun startObservingHistory(lang: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            repository.recentHistory(langCode = lang, limit = 50)
                .collectLatest { entries ->
                    val query = _historySearchQuery.value
                    _uiState.value = _uiState.value.copy(
                        history         = entries,
                        filteredHistory = if (query.isBlank()) entries
                                          else entries.filter {
                                              it.inputText.contains(query, ignoreCase = true)
                                          }
                    )
                }
        }
    }

    private fun startObservingRecentInputs(lang: String) {
        inputsJob?.cancel()
        inputsJob = viewModelScope.launch {
            repository.recentInputs(langCode = lang, limit = 20)
                .collectLatest { inputs ->
                    _uiState.value = _uiState.value.copy(recentInputs = inputs)
                }
        }
    }

    // ── misc helpers ──────────────────────────────────────────────────────────────

    fun setError(msg: String?) {
        _uiState.value = _uiState.value.copy(error = msg, isLoading = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        translateJob?.cancel()
        suggestJob?.cancel()
        historyJob?.cancel()
        inputsJob?.cancel()
        searchJob?.cancel()
    }

    companion object {
        private const val TAG          = "BlissViewModel"
        private const val DEFAULT_LANG = "it"
        const val MIN_PREFIX_LEN       = 2
        const val MAX_SUGGESTIONS      = 8
    }
}

// ── TranslationStats ──────────────────────────────────────────────────────────────

/**
 * Coverage breakdown for a [List<BlissSymbol>] produced by [BlissTranslator].
 *
 * ## Patch 8 change
 * [semantic] counter added: counts symbols with [BlissSymbol.MatchType.SEMANTIC],
 * i.e. tokens resolved by tier 3g ([BlissSemanticComposer]).  These symbols
 * were previously counted as part of an implicit COMPOUND bucket; they are now
 * tracked separately for observability.
 *
 * [coverage] denominator includes SEMANTIC tokens as covered (same as EXACT/LEMMA).
 *
 * @param total    Total symbol count (including UNKNOWN).
 * @param exact    Symbols resolved via surface / FTS4 exact match.
 * @param lemma    Symbols resolved via Morfologik FSA or CSV lemma lookup.
 * @param ngram    Symbols resolved via multi-word phrase lookup.
 * @param semantic Symbols resolved by [BlissSemanticComposer] tier 3g (Patch 8).
 * @param unknown  Symbols with no match in any tier.
 */
data class TranslationStats(
    val total:    Int,
    val exact:    Int,
    val lemma:    Int,
    val ngram:    Int,
    val semantic: Int,
    val unknown:  Int
) {
    /**
     * Fraction of tokens covered (non-UNKNOWN) over the total.
     * Range [0.0, 1.0].  Returns 0 for empty symbol lists.
     */
    val coverage: Float
        get() = if (total == 0) 0f else (total - unknown).toFloat() / total.toFloat()

    companion object {
        fun from(symbols: List<BlissSymbol>) = TranslationStats(
            total    = symbols.size,
            exact    = symbols.count { it.matchType == BlissSymbol.MatchType.EXACT },
            lemma    = symbols.count { it.matchType == BlissSymbol.MatchType.LEMMA },
            ngram    = symbols.count { it.matchType == BlissSymbol.MatchType.NGRAM },
            semantic = symbols.count { it.matchType == BlissSymbol.MatchType.SEMANTIC },
            unknown  = symbols.count { it.matchType == BlissSymbol.MatchType.UNKNOWN }
        )
    }
}

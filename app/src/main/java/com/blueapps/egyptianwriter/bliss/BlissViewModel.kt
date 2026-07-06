package com.blueapps.egyptianwriter.bliss

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document

/**
 * ViewModel for the Bliss translation screen.
 *
 * ## State
 * All UI state is consolidated in the immutable [UiState] data class and
 * exposed via a single [uiState] [StateFlow].  The Fragment should collect
 * this flow and derive every visible element from it, avoiding any local
 * mutable state.
 *
 * ## Features
 * - **History persistence**: every successful translation is saved to
 *   [BlissHistoryRepository] (Room `bliss_history` table, max 100 rows).
 * - **Typeahead suggestions**: [onSuggestionQuery] triggers an FTS4 prefix
 *   search and emits BCI names as [UiState.suggestions].  Each call
 *   cancels the previous in-flight job so the UI always shows results for
 *   the latest keystroke without race conditions.
 * - **History panel**: [toggleHistoryPanel] / [clearHistory] manage
 *   [UiState.historyVisible] and the history list observable.
 * - **History search (E-04)**: [setHistorySearch] updates [_historySearchQuery];
 *   [filteredHistory] is a derived [StateFlow] that combines the raw history
 *   list with the query string.  An empty query returns the full list without
 *   an extra DB round-trip; a non-empty query delegates to
 *   [BlissHistoryRepository.searchHistory] and replaces [UiState.history]
 *   with the filtered results.
 *
 * ## Concurrency model
 * | Job | Dispatcher | Cancellation |
 * |---|---|---|
 * | [translateJob]  | Default (CPU)  | Cancelled on each new [translate] call |
 * | [suggestJob]    | IO (FTS4 DB)   | Cancelled on each new [onSuggestionQuery] call |
 * | [historyJob]    | Main (Flow)    | Cancelled when lang changes |
 * | [searchJob]     | Main (Flow)    | Cancelled on each new [setHistorySearch] call |
 *
 * @constructor Created by the framework via [AndroidViewModel]; receives
 *   [Application] for [BlissLookup] context.
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI state ──────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all UI state for the Bliss translation screen.
     *
     * @param symbols          Translated [BlissSymbol] list from the most recent run.
     * @param glyphXDoc        GlyphX DOM document, ready for [BlissRenderer] (nullable
     *                         until a translation has been performed).
     * @param stats            Coverage breakdown of the current translation.
     * @param langCode         Active ISO-639-1 language code.
     * @param isLoading        `true` while the engine or a translation is in progress.
     * @param error            Non-null when a terminal error has occurred.
     * @param suggestions      Typeahead suggestion labels for the current input prefix.
     * @param history          Full (unfiltered) history list for the current [langCode].
     * @param filteredHistory  Subset of [history] matching [historySearchQuery];
     *                         equals [history] when the query is blank.
     * @param historySearchQuery Current text in the history search field.
     * @param recentInputs     Distinct recent input texts for the current [langCode]
     *                         (used for inline autocomplete chips).
     * @param historyVisible   Whether the history panel is open in the UI.
     */
    data class UiState(
        val symbols:             List<BlissSymbol>       = emptyList(),
        val glyphXDoc:           Document?               = null,
        val stats:               TranslationStats?       = null,
        val langCode:            String                  = DEFAULT_LANG,
        val isLoading:           Boolean                 = false,
        val error:               String?                 = null,
        val suggestions:         List<String>            = emptyList(),
        val history:             List<BlissHistoryEntry> = emptyList(),
        val filteredHistory:     List<BlissHistoryEntry> = emptyList(),
        val historySearchQuery:  String                  = "",
        val recentInputs:        List<String>            = emptyList(),
        val historyVisible:      Boolean                 = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Standalone [StateFlow] for the history search query string.
     * Kept separate from [_uiState] so [combine] can react to changes in
     * either the raw history list or the query without triggering a full
     * [UiState] recomposition.
     */
    private val _historySearchQuery = MutableStateFlow("")

    // ── engine components ─────────────────────────────────────────────────────

    private val lookup:     BlissLookup            = BlissLookup.getInstance(application)
    private val morfologik: MorfologikLemmatizer   = MorfologikLemmatizer(application)
    private val repository: BlissHistoryRepository = BlissHistoryRepository(
        BlissDatabase.getInstance(application)
    )

    private var translator:   BlissTranslator?      = null
    private var builder:      BlissGlyphXBuilder?   = null

    private var translateJob: Job? = null
    private var suggestJob:   Job? = null
    private var historyJob:   Job? = null
    private var inputsJob:    Job? = null
    private var searchJob:    Job? = null

    // ── language management ───────────────────────────────────────────────────

    /**
     * Loads the BCI-AV assets for [lang] and initialises the Room FTS4 DB.
     * Idempotent — no-op if [lang] equals the currently loaded language.
     * Cancels in-flight history observers and re-subscribes for the new language.
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
                translator = BlissTranslator(lookup, morfologik)
                viewModelScope.launch(Dispatchers.IO) { lookup.initDb() }
                _uiState.value = _uiState.value.copy(isLoading = false)
                startObservingHistory(normalised)
                startObservingRecentInputs(normalised)
                Log.i(TAG, "Engine ready [lang=$normalised, morfologik=${morfologik.isAvailable(normalised)}]")
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

    // ── translation ───────────────────────────────────────────────────────────

    /**
     * Translates [text] using the full pipeline (Morfologik FSA tier active).
     * Cancels any in-flight translation before starting.
     * On success, auto-saves to the history repository.
     */
    fun translate(text: String) {
        val t = translator ?: run {
            _uiState.value = _uiState.value.copy(error = "Engine not ready")
            return
        }
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading   = true,
                error       = null,
                suggestions = emptyList()
            )
            try {
                val symbols = withContext(Dispatchers.Default) {
                    t.translateAsync(text)
                }
                val doc = withContext(Dispatchers.Default) {
                    builder?.build(symbols)
                }
                val stats = TranslationStats.from(symbols)
                _uiState.value = _uiState.value.copy(
                    symbols   = symbols,
                    glyphXDoc = doc,
                    stats     = stats,
                    isLoading = false
                )
                val lang = _uiState.value.langCode
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

    // ── typeahead suggestions ──────────────────────────────────────────────────

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

    /** Clears the current suggestion list. */
    fun clearSuggestions() {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(suggestions = emptyList())
    }

    // ── history panel ──────────────────────────────────────────────────────────

    /** Toggles the visibility of the history panel. */
    fun toggleHistoryPanel() {
        _uiState.value = _uiState.value.copy(
            historyVisible = !_uiState.value.historyVisible
        )
    }

    /** Deletes a single history entry by its [id]. */
    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteEntry(id)
        }
    }

    /**
     * Restores [entry] into the active translation state.
     *
     * Symbols are reconstructed from the persisted BCI-AV IDs using
     * [BlissLookup.toSymbol] — the canonical BCI name is used as both
     * [BlissSymbol.sourceWord] and [BlissSymbol.lemma] because the original
     * surface form is not stored in the history table.
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
            symbols   = resolved,
            glyphXDoc = null,
            stats     = stats,
            error     = null
        )
    }

    /** Re-inserisce un entry precedentemente cancellato (undo swipe-to-delete). */
    fun restoreHistoryEntry(entry: BlissHistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertEntry(entry)
        }
    }

    /** Re-inserisce una lista di entry precedentemente cancellati (undo clear-all). */
    fun restoreHistoryEntries(entries: List<BlissHistoryEntry>) {
        viewModelScope.launch(Dispatchers.IO) {
            entries.forEach { repository.insertEntry(it) }
        }
    }

    /** Cancella tutta la cronologia per la lingua corrente. */
    fun clearHistory() {
        val lang = _uiState.value.langCode
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLang(lang)
        }
    }

    // ── history search (E-04) ────────────────────────────────────────────────

    /**
     * Updates the history search query and re-subscribes the [searchJob]
     * to [BlissHistoryRepository.searchHistory] with the new [query].
     *
     * When [query] is blank the search job is cancelled and [filteredHistory]
     * is reset to the full [UiState.history] list without an extra DB call.
     *
     * The Fragment is responsible for applying a debounce (≥ 300 ms) before
     * calling this function to avoid per-keystroke DB queries.
     *
     * @param query  Substring to look for in `input_text`.  Pass an empty
     *               string to clear the filter.
     */
    fun setHistorySearch(query: String) {
        _historySearchQuery.value = query
        _uiState.value = _uiState.value.copy(historySearchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            // No DB call needed — reset filteredHistory to the full list.
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

    // ── private: reactive observers ───────────────────────────────────────────

    private fun startObservingHistory(lang: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            repository.recentHistory(langCode = lang, limit = 50)
                .collectLatest { entries ->
                    val query = _historySearchQuery.value
                    _uiState.value = _uiState.value.copy(
                        history         = entries,
                        // Keep filteredHistory consistent with the latest query.
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

    // ── misc helpers ──────────────────────────────────────────────────────────

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

// ── TranslationStats ──────────────────────────────────────────────────────────

data class TranslationStats(
    val total:   Int,
    val exact:   Int,
    val lemma:   Int,
    val ngram:   Int,
    val unknown: Int
) {
    val coverage: Float
        get() = if (total == 0) 0f else (total - unknown).toFloat() / total.toFloat()

    companion object {
        fun from(symbols: List<BlissSymbol>) = TranslationStats(
            total   = symbols.size,
            exact   = symbols.count { it.matchType == BlissSymbol.MatchType.EXACT },
            lemma   = symbols.count { it.matchType == BlissSymbol.MatchType.LEMMA },
            ngram   = symbols.count { it.matchType == BlissSymbol.MatchType.NGRAM },
            unknown = symbols.count { it.matchType == BlissSymbol.MatchType.UNKNOWN }
        )
    }
}

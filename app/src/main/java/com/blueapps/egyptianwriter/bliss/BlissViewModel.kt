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
 * ## Features (Fase 4)
 * - **History persistence**: every successful translation is saved to
 *   [BlissHistoryRepository] (Room `bliss_history` table, max 100 rows).
 * - **Typeahead suggestions**: [onSuggestionQuery] triggers an FTS4 prefix
 *   search and emits BCI names as [UiState.suggestions].  Each call
 *   cancels the previous in-flight job so the UI always shows results for
 *   the latest keystroke without race conditions.
 * - **History panel**: [toggleHistoryPanel] / [clearHistory] manage
 *   [UiState.historyVisible] and the history list observable.
 *
 * ## Concurrency model
 * | Job | Dispatcher | Cancellation |
 * |---|---|---|
 * | [translateJob] | Default (CPU) | Cancelled on each new [translate] call |
 * | [suggestJob]   | IO (FTS4 DB)  | Cancelled on each new [onSuggestionQuery] call |
 * | [historyJob]   | Main (Flow collector) | Cancelled when lang changes |
 *
 * @constructor Created by the framework via [AndroidViewModel]; receives
 *   [Application] for [BlissLookup] context.
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI state ──────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all UI state for the Bliss translation screen.
     *
     * @param symbols        Translated [BlissSymbol] list from the most recent run.
     * @param glyphXDoc      GlyphX DOM document, ready for [BlissRenderer] (nullable
     *                       until a translation has been performed).
     * @param stats          Coverage breakdown of the current translation.
     * @param langCode       Active ISO-639-1 language code.
     * @param isLoading      `true` while the engine or a translation is in progress.
     * @param error          Non-null when a terminal error has occurred.
     * @param suggestions    Typeahead suggestion labels for the current input prefix.
     * @param history        Paginated history list for the current [langCode].
     * @param recentInputs   Distinct recent input texts for the current [langCode]
     *                       (used for inline autocomplete chips).
     * @param historyVisible Whether the history panel is open in the UI.
     */
    data class UiState(
        val symbols:        List<BlissSymbol>       = emptyList(),
        val glyphXDoc:      Document?               = null,
        val stats:          TranslationStats?       = null,
        val langCode:       String                  = DEFAULT_LANG,
        val isLoading:      Boolean                 = false,
        val error:          String?                 = null,
        val suggestions:    List<String>            = emptyList(),
        val history:        List<BlissHistoryEntry> = emptyList(),
        val recentInputs:   List<String>            = emptyList(),
        val historyVisible: Boolean                 = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── engine components ─────────────────────────────────────────────────────────

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
            isLoading      = true,
            error          = null,
            langCode       = normalised,
            suggestions    = emptyList(),
            history        = emptyList(),
            recentInputs   = emptyList()
        )
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
     *
     * @param text  Raw user input; trimmed by the translator internally.
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
                    builder?.let { b ->
                        b.clear()
                        symbols.forEach { sym -> b.append(sym) }
                        b.build()
                    }
                }
                val stats = TranslationStats.from(symbols)
                _uiState.value = _uiState.value.copy(
                    symbols   = symbols,
                    glyphXDoc = doc,
                    stats     = stats,
                    isLoading = false
                )
                // Persist to history on IO — fire-and-forget from the UI’s perspective
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

    // ── typeahead suggestions ───────────────────────────────────────────────────

    /**
     * Called by the Fragment’s [android.text.TextWatcher] on each keystroke.
     * Queries the FTS4 prefix index for the **last word** in [text] and emits
     * up to [MAX_SUGGESTIONS] BCI canonical names into [UiState.suggestions].
     *
     * The previous suggestion job is cancelled before a new one is launched,
     * acting as a lightweight debounce: only the result for the latest input
     * ever reaches the UI.
     *
     * @param text  Full current content of the input field.
     */
    fun onSuggestionQuery(text: String) {
        // Extract the last whitespace-delimited token as the live prefix
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

    /** Clears the current suggestion list (e.g., after translation runs). */
    fun clearSuggestions() {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(suggestions = emptyList())
    }

    // ── history panel ──────────────────────────────────────────────────────────────

    /**
     * Toggles the visibility of the history panel.
     * The Fragment observes [UiState.historyVisible] to show/hide the panel.
     */
    fun toggleHistoryPanel() {
        _uiState.value = _uiState.value.copy(
            historyVisible = !_uiState.value.historyVisible
        )
    }

    /**
     * Deletes a single history entry by [id] on [Dispatchers.IO].
     * The history [Flow] will automatically emit an updated list.
     */
    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteEntry(id)
        }
    }

    /**
     * Clears the entire history for the current language.
     * Called from the Fragment’s ‘Clear history’ menu item.
     */
    fun clearHistory() {
        val lang = _uiState.value.langCode
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLang(lang)
        }
    }

    // ── private: reactive observers ─────────────────────────────────────────────────

    /** Starts (or restarts) collecting the history Flow for [lang]. */
    private fun startObservingHistory(lang: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            repository.recentHistory(langCode = lang, limit = 50)
                .collectLatest { entries ->
                    _uiState.value = _uiState.value.copy(history = entries)
                }
        }
    }

    /** Starts (or restarts) collecting the recent-inputs Flow for [lang]. */
    private fun startObservingRecentInputs(lang: String) {
        inputsJob?.cancel()
        inputsJob = viewModelScope.launch {
            repository.recentInputs(langCode = lang, limit = 20)
                .collectLatest { inputs ->
                    _uiState.value = _uiState.value.copy(recentInputs = inputs)
                }
        }
    }

    // ── misc helpers ──────────────────────────────────────────────────────────────────

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
    }

    // ── companion ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG             = "BlissViewModel"
        private const val DEFAULT_LANG    = "it"
        /** Minimum prefix length to trigger an FTS4 typeahead query. */
        const val MIN_PREFIX_LEN          = 2
        /** Maximum number of typeahead suggestion labels to surface in the UI. */
        const val MAX_SUGGESTIONS         = 8
    }
}

// ── TranslationStats ────────────────────────────────────────────────────────────────

/**
 * Coverage breakdown for a translated symbol list.
 * Exposed on [BlissViewModel.UiState.stats].
 */
data class TranslationStats(
    val total:   Int,
    val exact:   Int,
    val lemma:   Int,
    val ngram:   Int,
    val unknown: Int
) {
    /** Coverage ratio 0..1 (fraction of non-UNKNOWN symbols). */
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

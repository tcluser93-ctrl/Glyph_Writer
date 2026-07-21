package com.blueapps.egyptianwriter.bliss

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
 * ## Patch 9 — TranslationTriggerMode (final spec)
 *
 * Two orthogonal axes are now explicitly modelled:
 * - [TranslationTriggerMode] — *when* to translate:
 *   [TranslationTriggerMode.MANUAL_SENTENCE] (default): translation starts only
 *   on explicit [submitManualTranslation] call (btnTranslate).
 *   [TranslationTriggerMode.AUTO_PROGRESSIVE]: debounce-on-typing behaviour
 *   (legacy default, still fully supported).
 * - [ViewMode] — *how* to render the result: [ViewMode.CARDS] (default),
 *   [ViewMode.CHIPS] (compact), [ViewMode.MIXED] (advanced / contextual).
 *
 * The Fragment must gate the TextWatcher debounce on [UiState.translationTriggerMode]
 * and must only call [submitManualTranslation] from btnTranslate.
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
     * - [CHIPS]  — Horizontal chip-strip (compact, secondary).
     * - [CARDS]  — CAA mini-card grid ([BlissSymbolCardAdapter]) — **default**.
     * - [MIXED]  — [MixedBlissRowView] composited preview (advanced/contextual).
     */
    enum class ViewMode { CHIPS, CARDS, MIXED }

    /**
     * Controls *when* the translation pipeline is triggered.
     *
     * - [MANUAL_SENTENCE]  — Translation starts only via [submitManualTranslation]
     *   (btnTranslate CTA). No automatic debounce during typing. **Default.**
     * - [AUTO_PROGRESSIVE] — Legacy debounce-on-typing behaviour:
     *   the Fragment's TextWatcher fires [translate] after 350 ms.
     *   Useful for lexical debugging and incremental review.
     */
    enum class TranslationTriggerMode { MANUAL_SENTENCE, AUTO_PROGRESSIVE }

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
     * @param symbols                Translated [BlissSymbol] list (all tiers).
     * @param glyphXDoc              GlyphX DOM document for [BlissRenderer.render];
     *                               null when [renderMode] is [RenderMode.STRUCTURED].
     * @param composedWords          Per-token [ComposedBlissWord] from tier 3g;
     *                               null entries = token resolved by an earlier tier.
     *                               Non-empty only when [renderMode] == [RenderMode.STRUCTURED].
     * @param renderMode             Dispatch hint for the Fragment renderer call.
     * @param stats                  Coverage breakdown of the current translation.
     * @param langCode               Active ISO-639-1 language code.
     * @param isLoading              `true` while a translation is in progress.
     * @param error                  Non-null when a terminal error has occurred.
     * @param suggestions            Typeahead suggestion labels.
     * @param history                Full (unfiltered) history list.
     * @param filteredHistory        Subset of [history] matching [historySearchQuery].
     * @param historySearchQuery     Current text in the history search field.
     * @param recentInputs           Distinct recent input texts (autocomplete chips).
     * @param historyVisible         Whether the history panel is open.
     * @param viewMode               Which output view the Fragment is rendering.
     * @param translationTriggerMode When to translate: manual (default) or auto.
     * @param currentInputText       Text currently in the input field (not yet submitted).
     * @param lastSubmittedText      Text of the last translation actually submitted.
     * @param isDirtyInput           True when [currentInputText] differs from [lastSubmittedText].
     */
    data class UiState(
        val symbols:                 List<BlissSymbol>          = emptyList(),
        val glyphXDoc:               Document?                  = null,
        val composedWords:           List<ComposedBlissWord?>   = emptyList(),
        val renderMode:              RenderMode                 = RenderMode.CLASSIC,
        val stats:                   TranslationStats?          = null,
        val langCode:                String                     = DEFAULT_LANG,
        val isLoading:               Boolean                    = false,
        val error:                   String?                    = null,
        val suggestions:             List<String>               = emptyList(),
        val history:                 List<BlissHistoryEntry>    = emptyList(),
        val filteredHistory:         List<BlissHistoryEntry>    = emptyList(),
        val historySearchQuery:      String                     = "",
        val recentInputs:            List<String>               = emptyList(),
        val historyVisible:          Boolean                    = false,
        val viewMode:                ViewMode                   = ViewMode.CARDS,
        val translationTriggerMode:  TranslationTriggerMode     = TranslationTriggerMode.MANUAL_SENTENCE,
        val currentInputText:        String                     = "",
        val lastSubmittedText:       String                     = "",
        val isDirtyInput:            Boolean                    = false
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

    /**
     * GlyphX document builder, used by [translate] (CLASSIC render mode) and
     * by the export flow ([getBuilder]) to turn a [List] of [BlissSymbol]
     * into a [org.w3c.dom.Document].
     *
     * ## Fix (enterprise-grade audit, 2026-07-20)
     * Previously nullable and populated only via [setBuilder], which no
     * caller in the app ever actually invoked — so `builder` was always
     * `null` in production, meaning `UiState.glyphXDoc` was always `null`
     * regardless of render mode, and the export flow had nothing to render.
     * [BlissGlyphXBuilder] needs no external dependency (no Context), so it
     * is now owned directly by the ViewModel with sensible defaults
     * ([BlissGlyphXBuilder.AUTO_SYMBOLS_PER_LINE], auto-computed per call
     * from the `screenWidthPx` argument to [BlissGlyphXBuilder.build]).
     * [setBuilder] is kept for callers that want to inject a differently
     * configured instance (e.g. a fixed `symbolsPerLine`), but is no longer
     * required for the builder to exist.
     */
    private var builder: BlissGlyphXBuilder = BlissGlyphXBuilder()

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

    /** Injects an adaptive [BlissGlyphXBuilder], overriding the default instance. */
    fun setBuilder(glyphXBuilder: BlissGlyphXBuilder) {
        builder = glyphXBuilder
    }

    /** Returns the current [BlissGlyphXBuilder] — always available, never null. */
    fun getBuilder(): BlissGlyphXBuilder = builder

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
        Log.d(TAG, "[VM] translate called input='$text'")
        val t = translator ?: run {
            Log.e(TAG, "[VM] translator is null — engine not ready")
            _uiState.value = _uiState.value.copy(
                error = getApplication<Application>().getString(R.string.bliss_error_engine_not_ready)
            )
            viewModelScope.launch { _events.emit(Event.ShowToast("Motore non pronto. Attendi e riprova.")) }
            return
        }
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            Log.d(TAG, "[VM] set loading=true")
            _uiState.value = _uiState.value.copy(
                isLoading    = true,
                error        = null,
                suggestions  = emptyList()
            )
            try {
                val lang = _uiState.value.langCode
                Log.d(TAG, "[VM] lang='$lang' before translateAsync")

                val symbols = withContext(Dispatchers.Default) {
                    t.translateAsync(text)
                }
                Log.d(TAG, "[VM] translateAsync returned symbols=${symbols.size}")
                if (symbols.isEmpty()) {
                    Log.w(TAG, "[VM] WARNING: symbols is empty — lookup may not be ready or text unmatched")
                }

                val composedWords: List<ComposedBlissWord?> = withContext(Dispatchers.Default) {
                    symbols.map { sym ->
                        if (sym.matchType == BlissSymbol.MatchType.SEMANTIC) {
                            composer?.composeStructured(sym.lemma, lang)
                        } else null
                    }
                }

                val hasStructured = composedWords.any { it != null }
                val renderMode    = if (hasStructured) RenderMode.STRUCTURED else RenderMode.CLASSIC
                Log.d(TAG, "[VM] renderMode=$renderMode hasStructured=$hasStructured")

                val doc = if (renderMode == RenderMode.CLASSIC) {
                    withContext(Dispatchers.Default) { builder.build(symbols) }
                } else null

                val stats = TranslationStats.from(symbols)
                Log.d(TAG, "[VM] stats exact=${stats.exact} lemma=${stats.lemma} ngram=${stats.ngram} semantic=${stats.semantic} unknown=${stats.unknown}")

                _uiState.value = _uiState.value.copy(
                    symbols       = symbols,
                    glyphXDoc     = doc,
                    composedWords = composedWords,
                    renderMode    = renderMode,
                    stats         = stats,
                    isLoading     = false
                )
                Log.d(TAG, "[VM] state published — translation complete")

                viewModelScope.launch(Dispatchers.IO) {
                    repository.saveTranslation(
                        inputText = text.trim(),
                        langCode  = lang,
                        symbols   = symbols
                    )
                }
            } catch (e: CancellationException) {
                // translate() viene rilanciato ad ogni chiamata via translateJob?.cancel()
                // (doppio tap rapido su "Traduci" o cambio lingua durante una traduzione
                // in corso). La cancellazione della coroutine emerge qui come
                // CancellationException dai punti di sospensione in translateAsync/withContext.
                // Se veniva inghiottita dal catch (e: Exception) sotto, la coroutine
                // *cancellata* pubblicava comunque un UiState con error/isLoading=false:
                // uno stato fantasma che poteva sovrascrivere quello della nuova
                // traduzione ancora in corso. Il rethrow lascia che la cancellazione
                // sia gestita normalmente dalla struttura delle coroutine (viewModelScope
                // vede il job come cancellato, non come fallito): nessuna mutazione di
                // UiState, nessun toast d'errore fantasma.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[VM] translate failed: ${e.javaClass.simpleName} — ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = e.message
                )
                _events.emit(Event.ShowToast("Errore traduzione: ${e.message ?: "sconosciuto"}"))
            }
        }
    }

    // ── Patch 9: trigger mode management ─────────────────────────────────────

    /**
     * Updates [UiState.currentInputText] and [UiState.isDirtyInput] when the
     * user types in the input field.  Does NOT trigger a translation.
     * The Fragment must call this from `afterTextChanged` regardless of trigger mode.
     */
    fun onInputChanged(text: String) {
        val current = _uiState.value
        _uiState.value = current.copy(
            currentInputText = text,
            isDirtyInput     = text != current.lastSubmittedText
        )
    }

    /**
     * Submits [text] as a manual full-sentence translation request.
     * Updates [UiState.lastSubmittedText] and clears [UiState.isDirtyInput],
     * then delegates to [translate].
     * Should be called **only** by btnTranslate in [TranslationTriggerMode.MANUAL_SENTENCE].
     */
    fun submitManualTranslation(text: String) {
        _uiState.value = _uiState.value.copy(
            currentInputText  = text,
            lastSubmittedText = text,
            isDirtyInput      = false
        )
        translate(text)
    }

    /**
     * Changes the active [TranslationTriggerMode].
     * When switching to [TranslationTriggerMode.AUTO_PROGRESSIVE], marks
     * [UiState.isDirtyInput] if the current input differs from the last
     * submitted text, so the Fragment can decide whether to re-translate.
     */
    fun setTranslationTriggerMode(mode: TranslationTriggerMode) {
        val current = _uiState.value
        _uiState.value = current.copy(
            translationTriggerMode = mode,
            isDirtyInput           = current.currentInputText != current.lastSubmittedText
        )
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
     * Does NOT re-trigger translation.
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
            symbols           = emptyList(),
            glyphXDoc         = null,
            composedWords     = emptyList(),
            renderMode        = RenderMode.CLASSIC,
            stats             = null,
            error             = null,
            isLoading         = false,
            lastSubmittedText = "",
            isDirtyInput      = _uiState.value.currentInputText.isNotEmpty()
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

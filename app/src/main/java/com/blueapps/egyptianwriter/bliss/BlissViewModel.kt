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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document

/**
 * ViewModel scoped to the host Activity (e.g. DocumentEditorActivity).
 *
 * It is the **single source of truth** for the Bliss translation session:
 * - [uiState]          — full UI state snapshot (StateFlow, always current)
 * - [glyphXDocument]   — latest GlyphX [Document] for ThothView (StateFlow)
 * - [events]           — one-shot events (SharedFlow) e.g. load errors
 *
 * Both [BlissTranslateFragment] (producer) and [DocumentEditorActivity]
 * (consumer → ThothView) observe [glyphXDocument].
 *
 * ## Java interop
 * ```java
 * // Collect StateFlow from Java via FlowKt / LiveData bridge
 * LiveData<BlissUiState> liveState =
 *     FlowLiveDataConversions.asLiveData(blissVm.getUiState(), lifecycle);
 * ```
 *
 * ## Kotlin / Compose
 * ```kotlin
 * val state by vm.uiState.collectAsState()
 * ```
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(BlissUiState())
    /** Full snapshot of the translation session. Collect in Fragment/Activity. */
    val uiState: StateFlow<BlissUiState> = _uiState.asStateFlow()

    /** Convenience: last translated GlyphX Document (null until first translation). */
    private val _glyphXDocument = MutableStateFlow<Document?>(null)
    val glyphXDocument: StateFlow<Document?> = _glyphXDocument.asStateFlow()

    // ── one-shot events ───────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<BlissEvent>(replay = 0, extraBufferCapacity = 8)
    /** One-shot events: errors, ready signals. Collect with repeatOnLifecycle. */
    val events: SharedFlow<BlissEvent> = _events.asSharedFlow()

    // ── active language ───────────────────────────────────────────────────────

    private var currentLang: String = DEFAULT_LANG
    private var loadJob: Job? = null

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Called by [BlissTranslateFragment] after each successful translation.
     * Safe to call from any thread.
     */
    fun postTranslation(symbolList: List<BlissSymbol>, doc: Document) {
        val stats = TranslationStats.from(symbolList)
        _uiState.update { it.copy(symbols = symbolList, stats = stats, isTranslating = false) }
        _glyphXDocument.value = doc
    }

    /**
     * Kick off async loading of the BCI-AV lookup tables for [langCode].
     * Cancels any in-flight load for a different language.
     * Posts [BlissEvent.LookupReady] on completion, [BlissEvent.LoadError] on failure.
     */
    fun initLookup(langCode: String) {
        val lang = normaliseLang(langCode)
        if (lang == currentLang && _uiState.value.isLookupReady) return
        currentLang = lang

        loadJob?.cancel()
        _uiState.update { it.copy(isLookupLoading = true, isLookupReady = false, lookupLang = lang) }

        loadJob = viewModelScope.launch {
            try {
                val lookup = BlissLookup.getInstance(getApplication())
                withContext(Dispatchers.IO) { lookup.load(lang) }
                _uiState.update { it.copy(isLookupLoading = false, isLookupReady = true,
                    lexiconSize = lookup.lexicon.size) }
                _events.emit(BlissEvent.LookupReady(lang, lookup.lexicon.size))
            } catch (t: Throwable) {
                Log.e(TAG, "Lookup load failed", t)
                _uiState.update { it.copy(isLookupLoading = false, isLookupReady = false) }
                _events.emit(BlissEvent.LoadError(t.localizedMessage ?: "Unknown error"))
            }
        }
    }

    /** Trigger a translation on the IO dispatcher; posts result via [postTranslation]. */
    fun translate(text: String, lookup: BlissLookup, glyphXBuilder: BlissGlyphXBuilder) {
        if (text.isBlank()) { clear(); return }
        _uiState.update { it.copy(isTranslating = true) }
        viewModelScope.launch {
            try {
                val (symbols, doc) = withContext(Dispatchers.Default) {
                    val syms = BlissTranslator(lookup).translate(text)
                    syms to glyphXBuilder.build(syms)
                }
                postTranslation(symbols, doc)
            } catch (t: Throwable) {
                Log.e(TAG, "Translation failed", t)
                _uiState.update { it.copy(isTranslating = false) }
                _events.emit(BlissEvent.TranslationError(t.localizedMessage ?: "Error"))
            }
        }
    }

    /** Update the active language; triggers a lookup reload if changed. */
    fun setLang(code: String) {
        val lang = normaliseLang(code)
        if (lang != currentLang) initLookup(lang)
    }

    /** Clear translation state (e.g. when user clears the input). */
    fun clear() {
        _uiState.update { it.copy(symbols = emptyList(), stats = TranslationStats(), isTranslating = false) }
        // glyphXDocument intentionally kept: ThothView retains last render
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun normaliseLang(code: String): String {
        val base = code.lowercase().take(2)
        return if (base in BlissLookup.SUPPORTED_LANGS) base else DEFAULT_LANG
    }

    // ── data classes ──────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of the full translation UI state.
     * StateFlow emits a new copy on every change.
     */
    data class BlissUiState(
        val symbols:        List<BlissSymbol> = emptyList(),
        val stats:          TranslationStats  = TranslationStats(),
        val isTranslating:  Boolean           = false,
        val isLookupLoading:Boolean           = false,
        val isLookupReady:  Boolean           = false,
        val lookupLang:     String            = DEFAULT_LANG,
        val lexiconSize:    Int               = 0
    )

    data class TranslationStats(
        val total:    Int = 0,
        val exact:    Int = 0,
        val lemma:    Int = 0,
        val ngram:    Int = 0,
        val fallback: Int = 0,
        val unknown:  Int = 0
    ) {
        /** Coverage ratio [0.0, 1.0]. Returns 0 for empty input. */
        val coverage: Float
            get() = if (total == 0) 0f else (total - unknown).toFloat() / total

        /** Coverage as a 0-100 Int percentage for display. */
        val coveragePct: Int get() = (coverage * 100).toInt()

        companion object {
            fun from(list: List<BlissSymbol>) = TranslationStats(
                total    = list.size,
                exact    = list.count { it.matchType == BlissSymbol.MatchType.EXACT },
                lemma    = list.count { it.matchType == BlissSymbol.MatchType.LEMMA },
                ngram    = list.count { it.matchType == BlissSymbol.MatchType.NGRAM },
                fallback = list.count { it.matchType == BlissSymbol.MatchType.FALLBACK_CATEGORY },
                unknown  = list.count { it.matchType == BlissSymbol.MatchType.UNKNOWN }
            )
        }
    }

    /** One-shot events emitted by the ViewModel. */
    sealed class BlissEvent {
        data class LookupReady(val lang: String, val lexiconSize: Int) : BlissEvent()
        data class LoadError(val message: String) : BlissEvent()
        data class TranslationError(val message: String) : BlissEvent()
    }

    companion object {
        private const val TAG          = "BlissViewModel"
        private const val DEFAULT_LANG = "it"
    }
}

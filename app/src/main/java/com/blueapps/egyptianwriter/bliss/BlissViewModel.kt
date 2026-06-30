package com.blueapps.egyptianwriter.bliss

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document

/**
 * ViewModel scoped to the host Activity (e.g. `DocumentEditorActivity`).
 *
 * Single source of truth for the Bliss translation session:
 * - active ISO-639-1 language
 * - [UiState] lifecycle (Idle → Loading → Ready | Error)
 * - last translated [BlissSymbol] list
 * - derived GlyphX [Document] ready for ThothView
 * - translation statistics
 *
 * The [BlissLookup] is owned here and loaded via [viewModelScope], so the
 * coroutine is automatically cancelled when the ViewModel is cleared (no leaks).
 *
 * ## Java interop (Activity)
 * ```java
 * BlissViewModel vm = new ViewModelProvider(this).get(BlissViewModel.class);
 * vm.getGlyphXDocument().observe(this, doc -> thothView.setGlyphXText(doc));
 * vm.getUiStateLiveData().observe(this, state -> {
 *     if (state instanceof BlissViewModel.UiState.Error) {
 *         showError(((BlissViewModel.UiState.Error) state).message);
 *     }
 * });
 * ```
 *
 * ## Kotlin Fragment
 * ```kotlin
 * val vm: BlissViewModel by activityViewModels()
 * vm.setLang("it")          // triggers load if not already ready
 * vm.postTranslation(symbols, doc)
 * ```
 */
class BlissViewModel(app: Application) : AndroidViewModel(app) {

    // ── UI state ───────────────────────────────────────────────────────────

    sealed class UiState {
        /** No load started or session cleared. */
        object Idle : UiState()
        /** Assets are being loaded from disk. */
        object Loading : UiState()
        /** Assets loaded; translator is ready. */
        data class Ready(val langCode: String, val lexiconSize: Int) : UiState()
        /** Load failed. */
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    /** Collect in Kotlin; observe via [uiStateLiveData] in Java. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    /** LiveData bridge for Java Activity consumers. */
    val uiStateLiveData: LiveData<UiState> = _uiState.asLiveData()

    // ── translation data ──────────────────────────────────────────────────

    private val _glyphXDocument = MutableStateFlow<Document?>(null)
    /** GlyphX Document built from the last successful translation. */
    val glyphXDocument: LiveData<Document?> = _glyphXDocument.asLiveData()

    private val _symbols = MutableStateFlow<List<BlissSymbol>>(emptyList())
    val symbols: LiveData<List<BlissSymbol>> = _symbols.asLiveData()

    private val _stats = MutableStateFlow(TranslationStats())
    val stats: LiveData<TranslationStats> = _stats.asLiveData()

    private val _langCode = MutableStateFlow(DEFAULT_LANG)
    val langCode: LiveData<String> = _langCode.asLiveData()

    // ── lookup (owned by ViewModel, load job tied to viewModelScope) ────────

    val lookup: BlissLookup = BlissLookup.getInstance(app)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Initiates an asset load for [lang] if not already ready for that language.
     * Idempotent: calling twice with the same lang while loading is a no-op.
     *
     * Transitions:
     * - [UiState.Idle] or [UiState.Error] → [UiState.Loading] → [UiState.Ready]
     * - On error: [UiState.Loading] → [UiState.Error]
     */
    fun setLang(lang: String) {
        val code = lang.take(2).lowercase()
        if (_langCode.value != code) {
            _langCode.value = code
            lookup.reset()
        }
        if (_uiState.value is UiState.Ready && lookup.currentLang == code) return
        _uiState.value = UiState.Loading
        lookup.loadIfNeeded(
            lang  = code,
            scope = viewModelScope,
            onReady = {
                _uiState.value = UiState.Ready(
                    langCode    = code,
                    lexiconSize = lookup.lexicon.size
                )
            },
            onError = { t ->
                _uiState.value = UiState.Error(t.message ?: "Unknown error")
            }
        )
    }

    /**
     * Called by [BlissTranslateFragment] after each successful translation.
     * All updates are safe from background threads (StateFlow.value is thread-safe;
     * LiveData bridge switches to Main automatically).
     */
    fun postTranslation(symbolList: List<BlissSymbol>, doc: Document) {
        _symbols.value        = symbolList
        _glyphXDocument.value = doc
        _stats.value          = TranslationStats.from(symbolList)
    }

    /**
     * Runs translation on [Dispatchers.IO] inside [viewModelScope].
     * Cancellation-safe: if the ViewModel is cleared mid-translation the job
     * is cancelled automatically (no runaway background threads).
     *
     * @param text       Raw user input.
     * @param translator A ready [BlissTranslator] instance.
     * @param builder    [BlissGlyphXBuilder] to convert symbols to DOM.
     * @param onResult   Called on the **main** thread with the result.
     */
    fun translateAsync(
        text:       String,
        translator: BlissTranslator,
        builder:    BlissGlyphXBuilder,
        onResult:   (List<BlissSymbol>, Document) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val symbols = translator.translate(text)
            val doc     = builder.build(symbols)
            withContext(Dispatchers.Main) {
                postTranslation(symbols, doc)
                onResult(symbols, doc)
            }
        }
    }

    /** Clear translation result (e.g. when the user clears the input). */
    fun clear() {
        _symbols.value = emptyList()
        _stats.value   = TranslationStats()
        // Keep _glyphXDocument — ThothView retains its last render.
        _uiState.value = if (lookup.isReady)
            UiState.Ready(lookup.currentLang ?: DEFAULT_LANG, lookup.lexicon.size)
        else UiState.Idle
    }

    // ── data class ────────────────────────────────────────────────────────────

    data class TranslationStats(
        val total:    Int = 0,
        val exact:    Int = 0,
        val lemma:    Int = 0,
        val ngram:    Int = 0,
        val fallback: Int = 0,
        val unknown:  Int = 0
    ) {
        /** Fraction of tokens that resolved to a known Bliss symbol. */
        val coverage: Float
            get() = if (total == 0) 0f else (total - unknown).toFloat() / total

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

    companion object {
        private const val DEFAULT_LANG = "it"
    }
}

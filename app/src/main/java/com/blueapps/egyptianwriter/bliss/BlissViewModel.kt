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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel scoped to the host Activity (e.g. [DocumentEditorActivity]).
 *
 * Single source of truth for the Bliss translation session:
 *  - last translated [BlissSymbol] list
 *  - derived GlyphX [org.w3c.dom.Document] for ThothView
 *  - translation statistics
 *  - current language + lookup readiness
 *
 * ## Usage (Activity — Java)
 * ```java
 * BlissViewModel vm = new ViewModelProvider(this).get(BlissViewModel.class);
 * vm.getGlyphXDocument().observe(this, doc -> thothView.setGlyphXText(doc));
 * ```
 *
 * ## Usage (Fragment — Kotlin)
 * ```kotlin
 * val vm: BlissViewModel by activityViewModels()
 * vm.postTranslation(symbols, glyphXDoc)
 * ```
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── StateFlows (Kotlin-idiomatic; also convertible to LiveData with .asLiveData()) ──

    private val _glyphXDocument = MutableStateFlow<org.w3c.dom.Document?>(null)
    val glyphXDocument: StateFlow<org.w3c.dom.Document?> = _glyphXDocument.asStateFlow()

    private val _symbols = MutableStateFlow<List<BlissSymbol>>(emptyList())
    val symbols: StateFlow<List<BlissSymbol>> = _symbols.asStateFlow()

    private val _stats = MutableStateFlow(TranslationStats())
    val stats: StateFlow<TranslationStats> = _stats.asStateFlow()

    private val _langCode = MutableStateFlow("it")
    val langCode: StateFlow<String> = _langCode.asStateFlow()

    /** True once the [BlissLookup] for the active language is fully loaded. */
    private val _lookupReady = MutableStateFlow(false)
    val lookupReady: StateFlow<Boolean> = _lookupReady.asStateFlow()

    /** Non-null after [loadLookup] completes successfully. */
    private var _lookup: BlissLookup? = null
    val lookup: BlissLookup? get() = _lookup

    private var loadJob: Job? = null

    // ── Lookup lifecycle ─────────────────────────────────────────────────────

    /**
     * Launches a coroutine on [Dispatchers.IO] to load [BlissLookup] assets
     * for [langCode].  Cancels any in-flight load before starting a new one.
     * Safe to call from the main thread.
     */
    fun loadLookup(langCode: String = _langCode.value) {
        loadJob?.cancel()
        _lookupReady.value = false
        loadJob = viewModelScope.launch {
            try {
                val lk = withContext(Dispatchers.IO) {
                    BlissLookup.getInstance(getApplication()).also { it.load(langCode) }
                }
                _lookup = lk
                _lookupReady.value = true
                Log.i(TAG, "Lookup ready: lang=$langCode lexicon=${lk.lexicon.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Lookup load failed", e)
                _lookupReady.value = false
            }
        }
    }

    // ── Translation ──────────────────────────────────────────────────────────

    /**
     * Called by [BlissTranslateFragment] after each successful translation.
     * Already on the main thread — direct assignment (no postValue needed).
     */
    fun postTranslation(symbolList: List<BlissSymbol>, doc: org.w3c.dom.Document) {
        _symbols.value = symbolList
        _glyphXDocument.value = doc
        _stats.value = TranslationStats.from(symbolList)
    }

    /** Update the active language and trigger a lookup reload. */
    fun setLang(code: String) {
        if (_langCode.value != code) {
            _langCode.value = code
            loadLookup(code)
        }
    }

    /** Clear translation state (e.g. when the user clears the input). */
    fun clear() {
        _symbols.value = emptyList()
        _stats.value = TranslationStats()
        // Do NOT clear glyphXDocument — ThothView keeps showing last render
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class TranslationStats(
        val total:    Int = 0,
        val exact:    Int = 0,
        val lemma:    Int = 0,
        val ngram:    Int = 0,
        val fallback: Int = 0,
        val unknown:  Int = 0
    ) {
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
        private const val TAG = "BlissViewModel"
    }
}

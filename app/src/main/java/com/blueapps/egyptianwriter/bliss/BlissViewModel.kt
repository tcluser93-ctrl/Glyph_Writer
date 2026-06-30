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
import org.w3c.dom.Document

/**
 * ViewModel for the Bliss translation screen.
 *
 * Owns the lifecycle of [BlissLookup], [BlissTranslator], [BlissGlyphXBuilder],
 * and the new [MorfologikLemmatizer].  Translation uses [translateAsync] which
 * activates the Morfologik FSA tier (tier 3c2) for higher coverage on IT/EN/DE.
 *
 * State is exposed as [StateFlow]<[UiState]> — the Fragment simply collects.
 */
class BlissViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI state ──────────────────────────────────────────────────────────────

    data class UiState(
        val symbols:     List<BlissSymbol>  = emptyList(),
        val glyphXDoc:   Document?          = null,
        val stats:       TranslationStats?  = null,
        val langCode:    String             = "it",
        val isLoading:   Boolean            = false,
        val error:       String?            = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── engine components ────────────────────────────────────────────────────

    private val lookup:     BlissLookup          = BlissLookup.getInstance(application)
    private val morfologik: MorfologikLemmatizer = MorfologikLemmatizer(application)
    private var translator: BlissTranslator?     = null
    private var builder:    BlissGlyphXBuilder?  = null
    private var translateJob: Job? = null

    // ── language management ───────────────────────────────────────────────────

    /**
     * Loads the BCI-AV assets for [lang] and initialises the Room FTS4 DB.
     * Idempotent — no-op if same language is already loaded.
     */
    fun setLang(lang: String) {
        val normalised = lang.lowercase().take(2)
        if (lookup.isReady && lookup.currentLang == normalised) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, langCode = normalised)
        lookup.loadIfNeeded(
            lang    = normalised,
            scope   = viewModelScope,
            onReady = {
                translator = BlissTranslator(lookup, morfologik)
                // Initialise Room FTS4 DB in background after assets are loaded
                viewModelScope.launch(Dispatchers.IO) { lookup.initDb() }
                _uiState.value = _uiState.value.copy(isLoading = false)
                Log.i(TAG, "Engine ready [lang=$normalised, morfologik=${morfologik.isAvailable(normalised)}]")
            },
            onError = { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                Log.e(TAG, "Engine load error", e)
            }
        )
    }

    fun setBuilder(glyphXBuilder: BlissGlyphXBuilder) {
        builder = glyphXBuilder
    }

    // ── translation ───────────────────────────────────────────────────────────

    /**
     * Translates [text] using the **async** pipeline (Morfologik tier active).
     * Cancels any in-flight translation before starting a new one.
     */
    fun translate(text: String) {
        val t = translator ?: run {
            _uiState.value = _uiState.value.copy(error = "Engine not ready")
            return
        }
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val symbols = withContext(Dispatchers.Default) {
                    // translateAsync is suspend and handles Morfologik IO internally
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = e.message
                )
                Log.e(TAG, "Translation error", e)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    fun setError(msg: String?) {
        _uiState.value = _uiState.value.copy(error = msg, isLoading = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        translateJob?.cancel()
    }

    companion object {
        private const val TAG = "BlissViewModel"
    }
}

// ── TranslationStats ──────────────────────────────────────────────────────────

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

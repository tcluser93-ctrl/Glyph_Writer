package com.blueapps.egyptianwriter.bliss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document

/**
 * ViewModel scoped to the host Activity (e.g. DocumentEditorActivity).
 *
 * Single source of truth for the Bliss translation session, exposed as
 * [StateFlow] so both Kotlin (collect) and Java (observe via asLiveData)
 * callers can subscribe.
 *
 * Usage from a Fragment:
 * ```kotlin
 * val vm: BlissViewModel by activityViewModels()
 * lifecycleScope.launch {
 *     vm.uiState.collect { state -> renderChips(state.symbols) }
 * }
 * ```
 *
 * Usage from Java Activity:
 * ```java
 * BlissViewModel vm = new ViewModelProvider(this).get(BlissViewModel.class);
 * // wrap uiState.asLiveData() or use a FlowCollector helper
 * ```
 */
class BlissViewModel : ViewModel() {

    // ── UiState ──────────────────────────────────────────────────────────────

    data class UiState(
        val symbols:        List<BlissSymbol> = emptyList(),
        val glyphXDocument: Document?         = null,
        val stats:          TranslationStats  = TranslationStats(),
        val langCode:       String            = "it",
        val isLoading:      Boolean           = false,
        val error:          String?           = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Called by BlissTranslateFragment after each successful translation.
     */
    fun postTranslation(symbolList: List<BlissSymbol>, doc: Document) {
        _uiState.value = _uiState.value.copy(
            symbols        = symbolList,
            glyphXDocument = doc,
            stats          = TranslationStats.from(symbolList),
            isLoading      = false,
            error          = null
        )
    }

    fun setLang(code: String) {
        if (_uiState.value.langCode != code)
            _uiState.value = _uiState.value.copy(langCode = code)
    }

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    fun setError(msg: String?) {
        _uiState.value = _uiState.value.copy(error = msg, isLoading = false)
    }

    /** Clear translation state (keep current lang). */
    fun clear() {
        _uiState.value = UiState(langCode = _uiState.value.langCode)
    }

    /**
     * Launch a translation job on [Dispatchers.IO], posting results back to
     * [uiState]. Automatically cancelled when the ViewModel is cleared.
     */
    fun translate(text: String, translator: BlissTranslator, builder: BlissGlyphXBuilder) {
        if (text.isBlank()) { clear(); return }
        setLoading(true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val syms = translator.translate(text)
                    val doc  = builder.build(syms)
                    syms to doc
                }
            }.onSuccess { (syms, doc) ->
                postTranslation(syms, doc)
            }.onFailure { t ->
                setError(t.message ?: "Errore sconosciuto")
            }
        }
    }

    // ── stats ─────────────────────────────────────────────────────────────────

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
}

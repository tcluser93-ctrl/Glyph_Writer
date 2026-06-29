package com.blueapps.egyptianwriter.bliss

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.w3c.dom.Document

/**
 * ViewModel scoped to the host Activity (e.g. [DocumentEditorActivity]).
 *
 * It is the single source of truth for the Bliss translation session:
 * - the last translated [BlissSymbol] list
 * - the derived GlyphX [Document] ready for ThothView
 * - translation statistics
 *
 * Both [BlissTranslateFragment] (producer) and [DocumentEditorActivity]
 * (consumer → ThothView) observe [glyphXDocument].
 *
 * Usage from the Activity:
 * ```java
 * BlissViewModel blissVm = new ViewModelProvider(this).get(BlissViewModel.class);
 * blissVm.getGlyphXDocument().observe(this, doc -> thothView.setGlyphXText(doc));
 * ```
 *
 * Usage from the Fragment:
 * ```kotlin
 * val vm: BlissViewModel by activityViewModels()
 * vm.postTranslation(symbols, glyphXDoc)
 * ```
 */
class BlissViewModel : ViewModel() {

    // ── LiveData ─────────────────────────────────────────────────────────────

    /** GlyphX Document built from the last successful translation. */
    private val _glyphXDocument = MutableLiveData<Document>()
    val glyphXDocument: LiveData<Document> get() = _glyphXDocument

    /** The raw symbol list from the last translation. */
    private val _symbols = MutableLiveData<List<BlissSymbol>>(emptyList())
    val symbols: LiveData<List<BlissSymbol>> get() = _symbols

    /** Statistics for the last translation run. */
    private val _stats = MutableLiveData(TranslationStats())
    val stats: LiveData<TranslationStats> get() = _stats

    /** Currently selected BCI language code (ISO-639-1). */
    private val _langCode = MutableLiveData("it")
    val langCode: LiveData<String> get() = _langCode

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Called by [BlissTranslateFragment] after each successful translation.
     * Posts new values to all LiveData fields on the main thread.
     */
    fun postTranslation(symbolList: List<BlissSymbol>, doc: Document) {
        _symbols.postValue(symbolList)
        _glyphXDocument.postValue(doc)
        _stats.postValue(TranslationStats.from(symbolList))
    }

    /** Update the active language; triggers a reload in the Fragment. */
    fun setLang(code: String) {
        if (_langCode.value != code) _langCode.postValue(code)
    }

    /** Clear translation state (e.g. when the user clears the input). */
    fun clear() {
        _symbols.postValue(emptyList())
        _stats.postValue(TranslationStats())
        // Do NOT clear glyphXDocument — ThothView keeps showing last render
    }

    // ── data classes ─────────────────────────────────────────────────────────

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

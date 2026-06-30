package com.blueapps.egyptianwriter.bliss

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Self-contained Fragment: accepts free text → translates to [BlissSymbol]s →
 * renders chip preview → pushes result to [BlissViewModel] → host Activity
 * forwards [Document] to ThothView.
 *
 * All heavy work (I/O + translation) runs inside [BlissViewModel.translate],
 * which uses [viewModelScope] + [Dispatchers.IO]. The Fragment only observes
 * [BlissViewModel.uiState] via a lifecycle-aware [repeatOnLifecycle] collector,
 * so there are zero raw Thread / Handler usages here.
 *
 * ## Integration in DocumentEditorActivity (Java)
 * ```java
 * BlissViewModel vm = new ViewModelProvider(this).get(BlissViewModel.class);
 * // Observe via LiveData adapter:
 * LiveDataKt.asLiveData(vm.getUiState(), ...)
 *     .observe(this, state -> {
 *         if (state.getGlyphXDocument() != null)
 *             thothView.setGlyphXText(state.getGlyphXDocument());
 *     });
 * ```
 */
class BlissTranslateFragment : Fragment() {

    private val vm: BlissViewModel by activityViewModels()
    private val glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = 8)
    private var translator: BlissTranslator? = null

    private lateinit var langSpinner:     Spinner
    private lateinit var translateButton: Button
    private lateinit var inputEditText:   EditText
    private lateinit var symbolContainer: LinearLayout
    private lateinit var statusText:      TextView
    private lateinit var progressBar:     ProgressBar
    private lateinit var errorText:       TextView

    // ── lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val initialLang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2)
                .let { if (it in BlissLookup.SUPPORTED_LANGS) it else DEFAULT_LANG }
        vm.setLang(initialLang)
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeUiState()
        initEngine(vm.uiState.value.langCode)
    }

    // ── state observer ────────────────────────────────────────────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state -> applyState(state) }
            }
        }
    }

    private fun applyState(state: BlissViewModel.UiState) {
        progressBar.visibility    = if (state.isLoading) View.VISIBLE else View.GONE
        translateButton.isEnabled = !state.isLoading

        if (state.error != null) {
            errorText.text       = "⚠ ${state.error}"
            errorText.visibility = View.VISIBLE
        } else {
            errorText.visibility = View.GONE
        }

        if (state.symbols.isNotEmpty()) renderChips(state.symbols)

        if (!state.isLoading && state.error == null) {
            val s = state.stats
            statusText.text = when {
                s.total == 0 -> "Pronto • ${state.langCode}"
                else -> "${s.total} simboli  •  ${s.unknown} sconosciuti  •  " +
                        "copertura ${(s.coverage * 100).toInt()}%  " +
                        "[E:${s.exact} L:${s.lemma} N:${s.ngram} F:${s.fallback}]"
            }
        }
    }

    // ── layout builder ─────────────────────────────────────────────────────────────

    private fun buildLayout(ctx: android.content.Context): View {
        val d = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
        }

        val sortedLangs = BlissLookup.SUPPORTED_LANGS.sorted()
        langSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, sortedLangs).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(sortedLangs.indexOf(vm.uiState.value.langCode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val lang = sortedLangs[pos]
                    if (lang != vm.uiState.value.langCode) { vm.setLang(lang); initEngine(lang) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            contentDescription = "Lingua sorgente"
        }
        translateButton = Button(ctx).apply {
            text = "▶ Traduci"
            contentDescription = "Avvia traduzione Bliss"
            setOnClickListener { onTranslateClicked() }
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            addView(langSpinner, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(translateButton, LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginStart = 8.dp() })
        })

        inputEditText = EditText(ctx).apply {
            hint = "Testo da tradurre in Bliss…"
            contentDescription = "Testo sorgente da tradurre in Bliss"
            minLines = 3; maxLines = 6
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = 8.dp() }
        }
        root.addView(inputEditText)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1.dp()).also {
                it.topMargin = 8.dp(); it.bottomMargin = 4.dp()
            }
            setBackgroundColor(0x22888888)
        })

        progressBar = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(progressBar)

        errorText = TextView(ctx).apply {
            visibility = View.GONE
            setTextColor(0xFFCC0000.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        root.addView(errorText)

        val scrollView = android.widget.ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).also { it.topMargin = 4.dp() }
        }
        symbolContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        scrollView.addView(symbolContainer)
        root.addView(scrollView)

        statusText = TextView(ctx).apply {
            text = ""
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = 4.dp() }
        }
        root.addView(statusText)
        return root
    }

    // ── engine ───────────────────────────────────────────────────────────────────

    private fun initEngine(lang: String) {
        translator = null
        vm.setLoading(true)
        val appCtx = requireContext().applicationContext
        val lk = BlissLookup.getInstance(appCtx)
        if (lk.isReady) { onLookupReady(lk); return }
        lk.loadAsync(
            langCode = lang,
            onReady  = { if (isAdded) onLookupReady(lk) },
            onError  = { t -> if (isAdded) vm.setError(t.message) }
        )
    }

    private fun onLookupReady(lk: BlissLookup) {
        translator = BlissTranslator(lk)
        vm.setLoading(false)
        statusText.text = "Pronto • ${lk.lexicon.size} voci • ${vm.uiState.value.langCode}"
    }

    // ── translation ─────────────────────────────────────────────────────────────

    private fun onTranslateClicked() {
        val text = inputEditText.text?.toString()?.trim() ?: return
        val tr   = translator ?: run { statusText.text = "Dizionario non ancora pronto, attendi…"; return }
        vm.translate(text, tr, glyphXBuilder)
    }

    // ── chip renderer ────────────────────────────────────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) {
        symbolContainer.removeAllViews()
        val ctx     = requireContext()
        val density = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        var row = newChipRow(ctx)
        symbolContainer.addView(row)
        var inRow = 0

        for (sym in symbols) {
            if (inRow == ROW_CHIPS) { row = newChipRow(ctx); symbolContainer.addView(row); inRow = 0 }
            row.addView(buildChip(ctx, sym, 4.dp()))
            inRow++
        }
    }

    private fun newChipRow(ctx: android.content.Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                it.bottomMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

    private fun buildChip(ctx: android.content.Context, sym: BlissSymbol, margin: Int): TextView =
        TextView(ctx).apply {
            text    = sym.displayLabel()
            textSize = 10f
            gravity  = android.view.Gravity.CENTER
            setPadding(margin + 2, margin + 2, margin + 2, margin + 2)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = margin }
            setBackgroundColor(chipColor(sym.matchType))
            contentDescription = "${sym.name} corrispondenza ${sym.matchType.name}"
            setOnClickListener {
                Toast.makeText(
                    ctx,
                    "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}\n" +
                    "Parola: '${sym.sourceWord}'\nMatch: ${sym.matchType}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
    }

    companion object {
        private const val ARG_LANG     = "arg_lang"
        private const val DEFAULT_LANG = "it"
        private const val ROW_CHIPS    = 6
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

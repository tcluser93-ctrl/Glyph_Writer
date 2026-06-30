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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Self-contained Fragment that:
 *  1. Accepts free-text input from the user
 *  2. Requests translation via [BlissViewModel.translateAsync]
 *  3. Renders result as colour-coded chips
 *  4. Pushes the GlyphX [org.w3c.dom.Document] to [BlissViewModel] so the
 *     host Activity can forward it to ThothView
 *
 * All background work (asset loading, translation) runs inside [viewModelScope]
 * (owned by [BlissViewModel]) or [viewLifecycleOwner.lifecycleScope].  No raw
 * [Thread] or [android.os.Handler] usage.
 *
 * ## Layout (built programmatically – no extra XML required)
 * ```
 * ┌────────────────────────────────────────┐
 * │ [Spinner: lang]          [▶ Traduci] │
 * │ EditText (source text)              │
 * │ ────────────────────────────────────── │
 * │ ProgressBar (hidden when idle)      │
 * │ ScrollView → FlowLayout chips       │
 * │ ────────────────────────────────────── │
 * │ Status: "12 simboli  •  copertura 83%" │
 * └────────────────────────────────────────┘
 * ```
 *
 * ## Integration in DocumentEditorActivity (Java)
 * ```java
 * BlissViewModel blissVm =
 *     new ViewModelProvider(this).get(BlissViewModel.class);
 * blissVm.getGlyphXDocument().observe(this, doc -> {
 *     try { thothView.setGlyphXText(doc); } catch (Exception e) { e.printStackTrace(); }
 * });
 * // Add BlissTranslateFragment.newInstance("it") to the desired tab.
 * ```
 */
class BlissTranslateFragment : Fragment() {

    // ── shared ViewModel (Activity-scoped) ────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── translator (created once lookup is Ready) ────────────────────────
    private var translator: BlissTranslator? = null
    private val glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = 8)

    // ── views ────────────────────────────────────────────────────────────
    private lateinit var langSpinner:     Spinner
    private lateinit var translateButton: Button
    private lateinit var inputEditText:   EditText
    private lateinit var chipFlow:        LinearLayout   // vertical container of chip rows
    private lateinit var statusText:      TextView
    private lateinit var progressBar:     ProgressBar

    // ── lifecycle ──────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val lang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2).let {
                if (it in BlissLookup.SUPPORTED_LANGS) it else DEFAULT_LANG
            }
        return buildLayout(requireContext(), lang)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeUiState()
        // Restore chips from a previous translation (e.g. config change)
        vm.symbols.value?.takeIf { it.isNotEmpty() }?.let { renderChips(it) }
    }

    // ── UiState observer ──────────────────────────────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collectLatest { state ->
                    when (state) {
                        is BlissViewModel.UiState.Idle -> {
                            progressBar.visibility = View.GONE
                            translateButton.isEnabled = false
                        }
                        is BlissViewModel.UiState.Loading -> {
                            progressBar.visibility = View.VISIBLE
                            translateButton.isEnabled = false
                            statusText.text = getString(R.string.bliss_loading_dict,
                                vm.lookup.currentLang ?: "…")
                        }
                        is BlissViewModel.UiState.Ready -> {
                            progressBar.visibility = View.GONE
                            translateButton.isEnabled = true
                            translator = BlissTranslator(vm.lookup)
                            statusText.text = getString(
                                R.string.bliss_ready,
                                state.lexiconSize, state.langCode
                            )
                        }
                        is BlissViewModel.UiState.Error -> {
                            progressBar.visibility = View.GONE
                            translateButton.isEnabled = false
                            statusText.text = getString(R.string.bliss_load_error, state.message)
                        }
                    }
                }
            }
        }
    }

    // ── layout builder ───────────────────────────────────────────────

    private fun buildLayout(ctx: android.content.Context, initialLang: String): View {
        val d = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
        }

        // ─ Row 1: spinner + translate button ────────────────────────────
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP)
        }
        val sortedLangs = BlissLookup.SUPPORTED_LANGS.sorted()
        langSpinner = Spinner(ctx).apply {
            contentDescription = "Lingua"
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            val adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, sortedLangs)
                .also { it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            setSelection(sortedLangs.indexOf(initialLang).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    vm.setLang(sortedLangs[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        translateButton = Button(ctx).apply {
            text = "▶"
            contentDescription = "Traduci"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .also { it.marginStart = 8.dp() }
            setOnClickListener { runTranslation() }
        }
        row1.addView(langSpinner)
        row1.addView(translateButton)
        root.addView(row1)

        // ─ Row 2: input EditText ───────────────────────────────────────
        inputEditText = EditText(ctx).apply {
            hint = ctx.getString(R.string.bliss_input_hint)
            contentDescription = hint
            minLines = 3; maxLines = 6
            layoutParams = lp(MATCH, WRAP).also { it.topMargin = 8.dp() }
        }
        root.addView(inputEditText)

        // ─ Divider ───────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = lp(MATCH, 1.dp()).also {
                it.topMargin = 8.dp(); it.bottomMargin = 4.dp()
            }
            setBackgroundColor(0x22888888)
        })

        // ─ ProgressBar ─────────────────────────────────────────────
        progressBar = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = lp(WRAP, WRAP).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(progressBar)

        // ─ Chip scroll area (vertical LinearLayout → horizontal rows = flow) ─
        val scroll = ScrollView(ctx).apply {
            layoutParams = lp(MATCH, 0).let {
                LinearLayout.LayoutParams(MATCH, 0, 1f).also { lp ->
                    lp.topMargin = 4.dp()
                }
            }
        }
        chipFlow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        scroll.addView(chipFlow)
        root.addView(scroll)

        // ─ Status text ──────────────────────────────────────────────
        statusText = TextView(ctx).apply {
            textSize = 12f
            layoutParams = lp(MATCH, WRAP).also { it.topMargin = 4.dp() }
        }
        root.addView(statusText)

        // Trigger initial load
        vm.setLang(initialLang)
        return root
    }

    // ── translation ───────────────────────────────────────────────────

    private fun runTranslation() {
        val text = inputEditText.text?.toString()?.trim() ?: return
        if (text.isEmpty()) {
            vm.clear()
            chipFlow.removeAllViews()
            statusText.text = context?.getString(R.string.bliss_empty_input)
            return
        }
        val tr = translator ?: run {
            statusText.text = context?.getString(R.string.bliss_not_ready)
            return
        }
        translateButton.isEnabled = false
        vm.translateAsync(text, tr, glyphXBuilder) { symbols, _ ->
            renderChips(symbols)
            translateButton.isEnabled = true
        }
    }

    // ── chip renderer ────────────────────────────────────────────────

    /**
     * Renders [symbols] as colour-coded chips arranged in wrapping rows.
     * Each row is a horizontal [LinearLayout] inside the vertical [chipFlow].
     * Row breaks every [CHIPS_PER_ROW] chips so chips never clip off-screen.
     */
    private fun renderChips(symbols: List<BlissSymbol>) {
        chipFlow.removeAllViews()
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        var row = newChipRow(ctx)
        chipFlow.addView(row)
        var rowCount = 0

        for (sym in symbols) {
            if (rowCount == CHIPS_PER_ROW) {
                row = newChipRow(ctx)
                chipFlow.addView(row)
                rowCount = 0
            }
            row.addView(buildChip(ctx, sym, d))
            rowCount++
        }

        vm.stats.value?.let { s ->
            val pct = (s.coverage * 100).toInt()
            statusText.text = "${s.total} simboli  •  ${s.unknown} sconosciuti  •  copertura $pct%"
        }
    }

    private fun newChipRow(ctx: android.content.Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP)
        }

    private fun buildChip(
        ctx: android.content.Context,
        sym: BlissSymbol,
        density: Float
    ): TextView {
        fun Int.dp() = (this * density).toInt()
        return TextView(ctx).apply {
            text             = sym.displayLabel(nameMax = 14)
            textSize         = 10f
            gravity          = android.view.Gravity.CENTER
            contentDescription = "${sym.bciAvId}: ${sym.name} (${sym.matchType})"
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also {
                it.marginEnd    = 4.dp()
                it.bottomMargin = 4.dp()
            }
            setBackgroundColor(chipColor(sym.matchType))
            setOnClickListener {
                Toast.makeText(
                    ctx,
                    "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}\n" +
                    "Parola: ‘${sym.sourceWord}’\nMatch: ${sym.matchType}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()  // verde
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()  // azzurro
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()  // giallo
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()  // arancio
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()  // rosso
    }

    // ── companion ───────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG      = "arg_lang"
        private const val DEFAULT_LANG  = "it"
        private const val CHIPS_PER_ROW = 6
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * @param lang ISO-639-1 code ("it", "en", "de", "fr", "es", "nl", "pl", "pt")
         */
        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

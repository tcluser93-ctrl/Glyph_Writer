package com.blueapps.egyptianwriter.bliss

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Self-contained Fragment that:
 *  1. Accepts free-text input from the user
 *  2. Translates it to [BlissSymbol]s via [BlissTranslator]
 *  3. Builds a GlyphX DOM [Document] via [BlissGlyphXBuilder]
 *  4. Renders symbol chips in the UI and pushes the result to [BlissViewModel]
 *
 * ## GlyphXBuilder initialization
 * The builder is initialised **after** the View is ready (in [onViewCreated]),
 * using the real screen width to compute [BlissGlyphXBuilder.computeSymbolsPerLine].
 * On configuration change (rotation) it is re-created via [reinitGlyphXBuilder].
 *
 * ## BlissRenderer integration
 * [BlissRenderer] is used for the ThothView-bound rendering path (GlyphX DOM).
 * All drawable fetches go through [BlissSignProvider.getDrawableAsync], which is
 * suspend and runs on IO. The UI path (chips) is kept lightweight.
 */
class BlissTranslateFragment : Fragment() {

    // ── shared ViewModel (Activity-scoped) ───────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── engine ───────────────────────────────────────────────────────────────
    /**
     * Lazily initialised in [onViewCreated] once the screen metrics are known.
     * Re-created in [onConfigurationChanged] to reflect the new orientation.
     */
    private var glyphXBuilder: BlissGlyphXBuilder? = null
    private var translateJob: Job? = null

    // ── views ────────────────────────────────────────────────────────────────
    private lateinit var langSpinner:     Spinner
    private lateinit var translateButton: Button
    private lateinit var inputEditText:   EditText
    private lateinit var chipContainer:   LinearLayout
    private lateinit var statusText:      TextView
    private lateinit var progressBar:     ProgressBar

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val initLang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2).let {
                if (it in BlissLookup.SUPPORTED_LANGS) it else DEFAULT_LANG
            }
        if (!vm.lookupReady.value || vm.langCode.value != initLang) {
            vm.setLang(initLang)
        }
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reinitGlyphXBuilder()          // ← adaptive init with real screen width
        observeViewModel()
        vm.symbols.value.takeIf { it.isNotEmpty() }?.let { renderChips(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reinitGlyphXBuilder()          // ← re-init on rotation / window resize
    }

    // ── adaptive GlyphXBuilder initialisation ────────────────────────────────

    /**
     * (Re-)creates [glyphXBuilder] using the real screen width available at
     * this moment.  Safe to call from [onViewCreated] and [onConfigurationChanged].
     *
     * Cell size is fixed at [CELL_SIZE_DP] dp; [BlissGlyphXBuilder.computeSymbolsPerLine]
     * derives the number of symbols that fit horizontally.
     */
    private fun reinitGlyphXBuilder() {
        val ctx = context ?: return
        val dm  = ctx.resources.displayMetrics

        // Use WindowMetrics (API 30+) when available; fall back to displayMetrics
        val screenWidthPx: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = requireActivity().windowManager
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            dm.widthPixels
        }

        val cellSizePx = (CELL_SIZE_DP * dm.density).toInt()
        val symbolsPerLine = BlissGlyphXBuilder.computeSymbolsPerLine(
            screenWidthPx = screenWidthPx,
            cellSizePx    = cellSizePx
        )
        glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = symbolsPerLine)
    }

    // ── layout builder ───────────────────────────────────────────────────────

    private fun buildLayout(ctx: android.content.Context): View {
        val dp = ctx.resources.displayMetrics.density
        fun Int.px() = (this * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.px(), 12.px(), 16.px(), 12.px())
            contentDescription = "Traduttore Bliss"
        }

        // row 1 — language spinner + translate button
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val sortedLangs = BlissLookup.SUPPORTED_LANGS.sorted()
        langSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            contentDescription = "Lingua sorgente"
            val adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, sortedLangs)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            setSelection(sortedLangs.indexOf(vm.langCode.value).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val lang = sortedLangs[pos]
                    if (lang != vm.langCode.value) vm.setLang(lang)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        translateButton = Button(ctx).apply {
            text = "▶"
            contentDescription = "Traduci"
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .also { it.marginStart = 8.px() }
            setOnClickListener { runTranslation() }
        }
        row1.addView(langSpinner)
        row1.addView(translateButton)
        root.addView(row1)

        // row 2 — input
        inputEditText = EditText(ctx).apply {
            hint = "Inserisci testo da tradurre…"
            minLines = 3; maxLines = 6
            contentDescription = "Testo sorgente"
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .also { it.topMargin = 8.px() }
        }
        root.addView(inputEditText)

        // divider
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1.px())
                .also { it.topMargin = 8.px(); it.bottomMargin = 4.px() }
            setBackgroundColor(0x22888888)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

        // progress bar
        progressBar = ProgressBar(ctx).apply {
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL }
            contentDescription = "Caricamento in corso"
        }
        root.addView(progressBar)

        // chip scroll area
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                .also { it.topMargin = 4.px() }
        }
        chipContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.px(), 4.px(), 4.px(), 4.px())
        }
        scrollView.addView(chipContainer)
        root.addView(scrollView)

        // status
        statusText = TextView(ctx).apply {
            text = ""
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .also { it.topMargin = 4.px() }
        }
        root.addView(statusText)

        return root
    }

    // ── ViewModel observers ──────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.lookupReady.collectLatest { ready ->
                        progressBar.isVisible = !ready
                        if (ready) {
                            val lex = vm.lookup?.lexicon?.size ?: 0
                            statusText.text = "Pronto • $lex voci • ${vm.langCode.value}"
                            translateButton.isEnabled = true
                        } else {
                            statusText.text = "Caricamento dizionario [${vm.langCode.value}]…"
                            translateButton.isEnabled = false
                        }
                    }
                }
                launch {
                    vm.stats.collectLatest { s ->
                        if (s.total > 0) {
                            val pct = (s.coverage * 100).toInt()
                            statusText.text = "${s.total} simboli  •  ${s.unknown} sconosciuti  •  $pct%"
                        }
                    }
                }
            }
        }
    }

    // ── translation (lifecycle-safe coroutine) ───────────────────────────────

    private fun runTranslation() {
        val text = inputEditText.text?.toString()?.trim() ?: return
        if (text.isEmpty()) {
            vm.clear()
            chipContainer.removeAllViews()
            statusText.text = "Input vuoto."
            return
        }
        val lk = vm.lookup ?: run {
            statusText.text = "Dizionario non ancora pronto, attendi…"
            return
        }
        val builder = glyphXBuilder ?: run {
            // Fallback: builder not yet initialised (very early call)
            reinitGlyphXBuilder()
            glyphXBuilder
        } ?: return

        translateJob?.cancel()
        translateButton.isEnabled = false
        progressBar.isVisible = true

        translateJob = viewLifecycleOwner.lifecycleScope.launch {
            val (symbols, doc) = withContext(Dispatchers.Default) {
                val syms = BlissTranslator(lk).translate(text)   // includes indicator pass
                val doc  = builder.build(syms)
                syms to doc
            }
            vm.postTranslation(symbols, doc)
            renderChips(symbols)
            translateButton.isEnabled = true
            progressBar.isVisible = false
        }
    }

    // ── chip renderer ────────────────────────────────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) {
        chipContainer.removeAllViews()
        if (symbols.isEmpty()) return

        val ctx     = requireContext()
        val dp      = ctx.resources.displayMetrics.density
        fun Int.px() = (this * dp).toInt()
        val chipsPerRow = 4

        var row: LinearLayout? = null
        symbols.forEachIndexed { idx, sym ->
            if (idx % chipsPerRow == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .also { it.bottomMargin = 4.px() }
                }
                chipContainer.addView(row)
            }
            // Build indicator badge string (e.g. "[P+]") appended to chip label
            val indicatorBadge = buildString {
                val inds = sym.indicators
                if (inds.isNotEmpty()) {
                    append(" [")
                    if (BlissTranslator.INDICATOR_PLURAL in inds) append("×")
                    if (BlissTranslator.INDICATOR_PAST   in inds) append("↩")
                    if (BlissTranslator.INDICATOR_FUTURE in inds) append("→")
                    append("]")
                }
            }
            val chip = TextView(ctx).apply {
                text = "${sym.displayLabel()}$indicatorBadge"
                textSize = 10f
                gravity  = android.view.Gravity.CENTER
                setPadding(6.px(), 6.px(), 6.px(), 6.px())
                contentDescription = buildString {
                    append("BCI ${sym.bciAvId}: ${sym.name}, match ${sym.matchType.name}")
                    if (sym.indicators.isNotEmpty())
                        append(", indicatori: ${sym.indicators.joinToString()}")
                }
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    .also { it.marginEnd = 4.px() }
                setBackgroundColor(chipColor(sym.matchType))
                setOnClickListener {
                    val indStr = if (sym.indicators.isEmpty()) "nessuno"
                                 else sym.indicators.joinToString()
                    Toast.makeText(
                        ctx,
                        "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}" +
                        "\nParola: '${sym.sourceWord}'\nMatch: ${sym.matchType}" +
                        "\nIndicatori: $indStr",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            row?.addView(chip)
        }
    }

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
    }

    // ── companion ────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG     = "arg_lang"
        private const val DEFAULT_LANG = "it"
        /** Cell size in dp used to compute symbols-per-line. Match BlissRenderer.DEFAULT_CELL_DP. */
        private const val CELL_SIZE_DP = 72
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

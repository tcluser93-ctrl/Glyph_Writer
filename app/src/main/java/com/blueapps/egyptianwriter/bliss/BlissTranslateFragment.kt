package com.blueapps.egyptianwriter.bliss

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
 *  4. Pushes the result to [BlissViewModel] → host Activity → ThothView
 *
 * ## Integration in DocumentEditorActivity (Java)
 * ```java
 * // In onCreate — observe ViewModel after binding ThothView:
 * BlissViewModel vm = new ViewModelProvider(this).get(BlissViewModel.class);
 * vm.getGlyphXDocument()  // or use StateFlow via lifecycleScope if Kotlin
 *     .observe(this, doc -> { try { thothView.setGlyphXText(doc); } catch(Exception e){} });
 *
 * // Add Bliss tab to ImageButtonGroup, then:
 * //   case 2: replace fragment with BlissTranslateFragment.newInstance("it")
 * ```
 *
 * ## Layout (built programmatically — no extra XML required)
 * ```
 * ┌───────────────────────────────────────────────┐
 * │ [Spinner: lang]              [▶ Traduci]       │
 * │ EditText (testo sorgente, 3–6 righe)           │
 * │ ─────────────────────────────────────────────  │
 * │  ProgressBar (nascosta quando inattiva)        │
 * │ ScrollView                                     │
 * │   FlexboxLayout: [chip][chip][chip]…           │
 * │ ─────────────────────────────────────────────  │
 * │ Status: "12 simboli  •  2 sconosciuti  83%"    │
 * └───────────────────────────────────────────────┘
 * ```
 */
class BlissTranslateFragment : Fragment() {

    // ── shared ViewModel (Activity-scoped) ───────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── engine ───────────────────────────────────────────────────────────────
    private val glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = 8)
    private var translateJob: Job? = null

    // ── views ────────────────────────────────────────────────────────────────
    private lateinit var langSpinner:     Spinner
    private lateinit var translateButton: Button
    private lateinit var inputEditText:   EditText
    private lateinit var chipContainer:   LinearLayout   // wrapping via ScrollView
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
        // Kick off lookup load if not already loaded for this language
        if (!vm.lookupReady.value || vm.langCode.value != initLang) {
            vm.setLang(initLang)
        }
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()
        // Restore last chips if ViewModel already has results
        vm.symbols.value.takeIf { it.isNotEmpty() }?.let { renderChips(it) }
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
            hint = ctx.getString(android.R.string.untitled)  // overridden below via tag
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
            orientation = LinearLayout.VERTICAL   // rows stacked vertically
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
                // lookup readiness → update progress/status
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
                // stats → status bar update
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

        translateJob?.cancel()
        translateButton.isEnabled = false
        progressBar.isVisible = true

        translateJob = viewLifecycleOwner.lifecycleScope.launch {
            val (symbols, doc) = withContext(Dispatchers.Default) {
                val syms = BlissTranslator(lk).translate(text)
                val doc  = glyphXBuilder.build(syms)
                syms to doc
            }
            // Back on main thread
            vm.postTranslation(symbols, doc)
            renderChips(symbols)
            translateButton.isEnabled = true
            progressBar.isVisible = false
        }
    }

    // ── chip renderer ────────────────────────────────────────────────────────

    /**
     * Renders symbols as rows of text chips inside [chipContainer].
     * Each chip shows the BCI-AV ID + truncated English name and is
     * colour-coded by [BlissSymbol.MatchType].
     */
    private fun renderChips(symbols: List<BlissSymbol>) {
        chipContainer.removeAllViews()
        if (symbols.isEmpty()) return

        val ctx     = requireContext()
        val dp      = ctx.resources.displayMetrics.density
        fun Int.px() = (this * dp).toInt()
        val rowsPerLine = 4  // chips per horizontal row

        var row: LinearLayout? = null
        symbols.forEachIndexed { idx, sym ->
            if (idx % rowsPerLine == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .also { it.bottomMargin = 4.px() }
                }
                chipContainer.addView(row)
            }
            val chip = TextView(ctx).apply {
                text = sym.displayLabel()
                textSize = 10f
                gravity  = android.view.Gravity.CENTER
                setPadding(6.px(), 6.px(), 6.px(), 6.px())
                contentDescription = "BCI ${sym.bciAvId}: ${sym.name}, match ${sym.matchType.name}"
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    .also { it.marginEnd = 4.px() }
                setBackgroundColor(chipColor(sym.matchType))
                setOnClickListener {
                    Toast.makeText(
                        ctx,
                        "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}\nParola: '${sym.sourceWord}'\nMatch: ${sym.matchType}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            row?.addView(chip)
        }
    }

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()  // verde
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()  // azzurro
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()  // giallo
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()  // arancio
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()  // rosso
    }

    // ── companion ────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG    = "arg_lang"
        private const val DEFAULT_LANG = "it"
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

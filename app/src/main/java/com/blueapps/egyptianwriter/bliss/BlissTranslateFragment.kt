package com.blueapps.egyptianwriter.bliss

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale

/**
 * Self-contained Fragment that:
 *  1. Accepts free-text input from the user
 *  2. Translates it to [BlissSymbol]s via [BlissTranslator]
 *  3. Builds a GlyphX [org.w3c.dom.Document] via [BlissGlyphXBuilder]
 *  4. Pushes the result to [BlissViewModel] so the host Activity can
 *     forward it to ThothView
 *
 * ## Integration in DocumentEditorActivity (Java)
 * ```java
 * // 1. Observe the ViewModel (add in onCreate, after thothView is bound)
 * BlissViewModel blissVm =
 *     new ViewModelProvider(this).get(BlissViewModel.class);
 * blissVm.getGlyphXDocument().observe(this, doc -> {
 *     try { thothView.setGlyphXText(doc); } catch (Exception e) { e.printStackTrace(); }
 * });
 *
 * // 2. Add a new tab button (e.g. buttonBliss) to ImageButtonGroup, then:
 * //    case 2: replace fragment with BlissTranslateFragment.newInstance("it")
 * ```
 *
 * ## Layout (built programmatically – no extra XML required)
 * ```
 * ┌────────────────────────────────────────┐
 * │ [Spinner: lang]          [▶ Translate] │
 * │ EditText (source text, 3–6 lines)      │
 * │ ────────────────────────────────────── │
 * │  ProgressBar (hidden when idle)        │
 * │ ScrollView                             │
 * │   FlowLayout: [chip][chip][chip]…      │
 * │ ────────────────────────────────────── │
 * │ Status: "12 simboli  •  2 sconosciuti  │
 * │         copertura 83%"                 │
 * └────────────────────────────────────────┘
 * ```
 */
class BlissTranslateFragment : Fragment() {

    // ── shared ViewModel (Activity-scoped) ───────────────────────────────────
    private val blissViewModel: BlissViewModel by activityViewModels()

    // ── engine ───────────────────────────────────────────────────────────────
    private var selectedLang: String = "it"
    private var lookup: BlissLookup? = null
    private var translator: BlissTranslator? = null
    private val glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = 8)

    // ── views ────────────────────────────────────────────────────────────────
    private lateinit var langSpinner:      Spinner
    private lateinit var translateButton:  Button
    private lateinit var inputEditText:    EditText
    private lateinit var symbolContainer:  LinearLayout
    private lateinit var statusText:       TextView
    private lateinit var progressBar:      ProgressBar

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        selectedLang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2).let {
                if (it in BlissLookup.SUPPORTED_LANGS) it else "it"
            }
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore last symbols if ViewModel already has a result
        blissViewModel.symbols.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { renderChips(it) }

        initEngine(selectedLang)
    }

    // ── layout builder ───────────────────────────────────────────────────────

    private fun buildLayout(ctx: android.content.Context): View {
        val density = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
        }

        // ── row 1 : language spinner + translate button ────────────────────
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val sortedLangs = BlissLookup.SUPPORTED_LANGS.sorted()
        langSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            val adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, sortedLangs)
                .also { it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            setSelection(sortedLangs.indexOf(selectedLang).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val lang = sortedLangs[pos]
                    if (lang != selectedLang) {
                        selectedLang = lang
                        blissViewModel.setLang(lang)
                        initEngine(lang)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        translateButton = Button(ctx).apply {
            text = "▶"
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .also { it.marginStart = 8.dp() }
            setOnClickListener { runTranslation() }
        }

        row1.addView(langSpinner)
        row1.addView(translateButton)
        root.addView(row1)

        // ── row 2 : input EditText ─────────────────────────────────────────
        inputEditText = EditText(ctx).apply {
            hint = "Testo da tradurre in Bliss…"
            minLines = 3; maxLines = 6
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .also { it.topMargin = 8.dp() }
        }
        root.addView(inputEditText)

        // ── divider ───────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1.dp())
                .also { it.topMargin = 8.dp(); it.bottomMargin = 4.dp() }
            setBackgroundColor(0x22888888)
        })

        // ── progress bar ──────────────────────────────────────────────────
        progressBar = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL }
        }
        root.addView(progressBar)

        // ── row 3 : symbol scroll area ────────────────────────────────────
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                .also { it.topMargin = 4.dp() }
        }
        symbolContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Horizontal scroll; a future iteration can use a FlowLayout library
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        scrollView.addView(symbolContainer)
        root.addView(scrollView)

        // ── row 4 : status text ───────────────────────────────────────────
        statusText = TextView(ctx).apply {
            text = ""
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .also { it.topMargin = 4.dp() }
        }
        root.addView(statusText)

        return root
    }

    // ── engine init ──────────────────────────────────────────────────────────

    private fun initEngine(lang: String) {
        progressBar.visibility = View.VISIBLE
        statusText.text = "Caricamento dizionario [$lang]…"
        symbolContainer.removeAllViews()

        val appCtx = requireContext().applicationContext
        val lk = BlissLookup.getInstance(appCtx)

        if (lk.isReady) {
            onLookupReady(lk, lang)
            return
        }

        lk.loadAsync(
            langCode = lang,
            onReady  = { if (isAdded) onLookupReady(lk, lang) },
            onError  = { t ->
                if (!isAdded) return@loadAsync
                progressBar.visibility = View.GONE
                statusText.text = "⚠ Errore caricamento: ${t.message}"
            }
        )
    }

    private fun onLookupReady(lk: BlissLookup, lang: String) {
        lookup     = lk
        translator = BlissTranslator(lk)
        progressBar.visibility = View.GONE
        statusText.text =
            "Pronto • ${lk.lexicon.size} voci • lingua: $lang"
    }

    // ── translation ──────────────────────────────────────────────────────────

    private fun runTranslation() {
        val text = inputEditText.text?.toString()?.trim() ?: return
        if (text.isEmpty()) {
            blissViewModel.clear()
            symbolContainer.removeAllViews()
            statusText.text = "Input vuoto."
            return
        }

        val tr = translator ?: run {
            statusText.text = "Dizionario non ancora pronto, attendi…"
            return
        }

        // Run translation on IO thread, post results on main thread
        Thread {
            val symbols = tr.translate(text)
            val doc     = glyphXBuilder.build(symbols)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!isAdded) return@post
                // ① push to ViewModel → Activity observer → ThothView
                blissViewModel.postTranslation(symbols, doc)
                // ② update chip preview in this Fragment
                renderChips(symbols)
            }
        }.apply { name = "BlissTranslate"; isDaemon = true; start() }
    }

    // ── chip renderer ────────────────────────────────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) {
        symbolContainer.removeAllViews()
        val ctx     = requireContext()
        val density = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        for (sym in symbols) {
            val chip = TextView(ctx).apply {
                text    = "#${sym.bciAvId}\n${sym.name.take(14)}"
                textSize = 10f
                gravity  = android.view.Gravity.CENTER
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also {
                    it.marginEnd    = 4.dp()
                    it.bottomMargin = 4.dp()
                }
                setBackgroundColor(chipColor(sym.matchType))
                setOnClickListener {
                    Toast.makeText(
                        ctx,
                        "BCI-AV: ${sym.bciAvId}\n" +
                        "Nome: ${sym.name}\n" +
                        "Parola: '${sym.sourceWord}'\n" +
                        "Match: ${sym.matchType}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            symbolContainer.addView(chip)
        }

        // Update status from ViewModel stats
        blissViewModel.stats.value?.let { s ->
            val pct = (s.coverage * 100).toInt()
            statusText.text =
                "${s.total} simboli  •  ${s.unknown} sconosciuti  •  copertura $pct%"
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()  // verde
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()  // azzurro
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()  // giallo
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()  // arancio
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()  // rosso
    }

    // ── companion ────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG = "arg_lang"
        private val MATCH  = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP   = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * @param lang ISO-639-1 code ("it", "en", "de", "fr", "es", "nl", "pl", "pt")
         */
        fun newInstance(lang: String = "it"): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

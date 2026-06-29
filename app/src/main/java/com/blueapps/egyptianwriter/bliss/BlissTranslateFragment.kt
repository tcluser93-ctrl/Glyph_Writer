package com.blueapps.egyptianwriter.bliss

import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.blueapps.egyptianwriter.R
import java.util.Locale

/**
 * Self-contained Fragment that provides a Bliss translation UI.
 *
 * Layout (built programmatically — no extra XML file required):
 *
 *  ┌─────────────────────────────────────────┐
 *  │  [Spinner: language]    [Translate ▶]  │
 *  │  EditText  (source text)               │
 *  │  ──────────────────────────────────── │
 *  │  ScrollView                            │
 *  │    LinearLayout  (symbol chips)        │
 *  │      [chip]  [chip]  [chip] …          │
 *  │  ──────────────────────────────────── │
 *  │  Status bar ("X symbols  •  Y unknown") │
 *  └─────────────────────────────────────────┘
 *
 * Integration:
 *   val ft = supportFragmentManager.beginTransaction()
 *   ft.replace(R.id.your_container, BlissTranslateFragment.newInstance("it"))
 *   ft.commit()
 */
class BlissTranslateFragment : Fragment() {

    // ── state ─────────────────────────────────────────────────────────────────
    private var selectedLang: String = "en"
    private var lookup: BlissLookup? = null
    private var translator: BlissTranslator? = null
    private var lastSymbols: List<BlissSymbol> = emptyList()

    // ── views (created programmatically) ─────────────────────────────────────
    private lateinit var langSpinner: Spinner
    private lateinit var translateButton: Button
    private lateinit var inputEditText: EditText
    private lateinit var symbolContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        selectedLang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2).let {
                if (it in BlissLookup.SUPPORTED_LANGS) it else "en"
            }
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initEngine(selectedLang)
    }

    // ── layout builder ────────────────────────────────────────────────────────

    private fun buildLayout(ctx: android.content.Context): View {
        val dp = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        // row 1 : spinner + button
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        langSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val langs  = BlissLookup.SUPPORTED_LANGS.sorted()
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, langs)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            setSelection(langs.indexOf(selectedLang).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val newLang = langs[pos]
                    if (newLang != selectedLang) {
                        selectedLang = newLang
                        initEngine(newLang)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        translateButton = Button(ctx).apply {
            text = ctx.getString(android.R.string.ok).let { "▶" }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = 8.dp() }
            setOnClickListener { runTranslation() }
        }

        row1.addView(langSpinner)
        row1.addView(translateButton)
        root.addView(row1)

        // row 2 : input
        inputEditText = EditText(ctx).apply {
            hint = "Scrivi qui il testo da tradurre…"
            minLines = 3
            maxLines = 6
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8.dp() }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { /* auto-translate opt-in future */ }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(inputEditText)

        // divider
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()
            ).also { it.topMargin = 8.dp() }
            setBackgroundColor(0x22888888)
        })

        // row 3 : progress bar (hidden by default)
        progressBar = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL }
        }
        root.addView(progressBar)

        // row 4 : symbol scroll area
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).also { it.topMargin = 8.dp() }
        }
        symbolContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        // Wrap chips — use FlowLayout-like wrapping via simple horizontal scroll for now
        scrollView.addView(symbolContainer)
        root.addView(scrollView)

        // row 5 : status
        statusText = TextView(ctx).apply {
            text = ""
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dp() }
        }
        root.addView(statusText)

        return root
    }

    // ── engine init ───────────────────────────────────────────────────────────

    private fun initEngine(lang: String) {
        progressBar.visibility = View.VISIBLE
        statusText.text = "Caricamento dizionario $lang…"
        symbolContainer.removeAllViews()

        val ctx = requireContext().applicationContext
        val lk  = BlissLookup.getInstance(ctx)

        // If already loaded for this language, reuse immediately
        if (lk.isReady) {
            lookup     = lk
            translator = BlissTranslator(lk)
            progressBar.visibility = View.GONE
            statusText.text = "Pronto (${lk.lexicon.size} voci, lingua: $lang)"
            return
        }

        lk.loadAsync(
            langCode = lang,
            onReady  = {
                if (!isAdded) return@loadAsync
                lookup     = lk
                translator = BlissTranslator(lk)
                progressBar.visibility = View.GONE
                statusText.text = "Pronto (${lk.lexicon.size} voci, lingua: $lang)"
            },
            onError  = { t ->
                if (!isAdded) return@loadAsync
                progressBar.visibility = View.GONE
                statusText.text = "Errore caricamento: ${t.message}"
            }
        )
    }

    // ── translation ───────────────────────────────────────────────────────────

    private fun runTranslation() {
        val text = inputEditText.text?.toString() ?: return
        val tr   = translator ?: run {
            statusText.text = "Dizionario non ancora pronto, attendi…"
            return
        }

        lastSymbols = tr.translate(text)
        renderSymbols(lastSymbols)
    }

    private fun renderSymbols(symbols: List<BlissSymbol>) {
        symbolContainer.removeAllViews()
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        symbols.forEach { sym ->
            val chip = TextView(ctx).apply {
                text = "#${sym.bciAvId}\n${sym.name.take(14)}"
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    it.marginEnd = 4.dp()
                    it.bottomMargin = 4.dp()
                }
                // colour by match type
                val bgColor = when (sym.matchType) {
                    BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
                    BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
                    BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
                    BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
                    BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
                }
                setBackgroundColor(bgColor)
                setOnClickListener {
                    Toast.makeText(
                        ctx,
                        "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}\nOrigine: '${sym.sourceWord}'\nCorrispondenza: ${sym.matchType}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            symbolContainer.addView(chip)
        }

        val unknownCount = symbols.count { it.matchType == BlissSymbol.MatchType.UNKNOWN }
        statusText.text = "${symbols.size} simboli  •  $unknownCount senza corrispondenza"
    }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG = "arg_lang"

        /**
         * @param lang  ISO-639-1 language code ("it", "en", "de", …)
         */
        fun newInstance(lang: String = "it"): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

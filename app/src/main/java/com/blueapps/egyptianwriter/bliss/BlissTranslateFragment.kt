package com.blueapps.egyptianwriter.bliss

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * BlissTranslateFragment — Fase 4 UI + Fase 5 Accessibilità
 *
 * Layout: fragment_translate.xml (dichiarativo)
 * Binding: manual view-lookup (no ViewBinding necessario per questo fragment)
 *
 * Flusso:
 *  1. Spinner lingua (8 lingue, da strings.xml bliss_language_codes)
 *  2. TextInputEditText testo sorgente
 *  3. RecyclerView suggerimenti predittivi orizzontale
 *  4. Button "Traduci" → coroutine viewLifecycleOwner.lifecycleScope
 *  5. FlexboxLayout chip simboli (wrap automatico, nessun chip fuori schermo)
 *  6. FAB “Condividi SVG”: scrive SVG in cache, Intent.ACTION_SEND via FileProvider
 *
 * Accessibilità:
 *  - accessibilityLiveRegion POLITE su symbolContainer e text_output
 *  - announceForAccessibility dopo traduzione completata
 *  - contentDescription su ogni controllo interattivo (dalla XML + da codice)
 */
class BlissTranslateFragment : Fragment() {

    // ── ViewModel (Activity-scoped) ───────────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── Engine ────────────────────────────────────────────────────
    private var glyphXBuilder: BlissGlyphXBuilder? = null
    private var translateJob: Job? = null

    // ── Views ─────────────────────────────────────────────────────
    private lateinit var spinnerLang:       Spinner
    private lateinit var inputLayout:       TextInputLayout
    private lateinit var editInput:         TextInputEditText
    private lateinit var labelSuggestions:  TextView
    private lateinit var rvSuggestions:     RecyclerView
    private lateinit var btnTranslate:      com.google.android.material.button.MaterialButton
    private lateinit var progressBar:       ProgressBar
    private lateinit var textOutputLabel:   TextView
    private lateinit var textOutput:        TextView
    private lateinit var labelSymbols:      TextView
    private lateinit var symbolContainer:   FlexboxLayout
    private lateinit var fabShare:          ExtendedFloatingActionButton

    // ── Adapter ─────────────────────────────────────────────────
    private val suggestionAdapter = SuggestionAdapter { word ->
        val current = editInput.text?.toString() ?: ""
        val lastSpace = current.lastIndexOf(' ')
        val newText = if (lastSpace < 0) word else current.substring(0, lastSpace + 1) + word
        editInput.setText(newText)
        editInput.setSelection(newText.length)
        runTranslation()
    }

    // ── Lifecycle ───────────────────────────────────────────────

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
        return inflater.inflate(R.layout.fragment_translate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSpinner()
        setupSuggestions()
        reinitGlyphXBuilder()
        observeViewModel()
        setupFabShare()
        vm.symbols.value.takeIf { it.isNotEmpty() }?.let { renderChips(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reinitGlyphXBuilder()
    }

    // ── View binding ─────────────────────────────────────────────

    private fun bindViews(v: View) {
        spinnerLang      = v.findViewById(R.id.spinner_language)
        inputLayout      = v.findViewById(R.id.input_layout_text)
        editInput        = v.findViewById(R.id.edit_input_text)
        labelSuggestions = v.findViewById(R.id.label_suggestions)
        rvSuggestions    = v.findViewById(R.id.rv_suggestions)
        btnTranslate     = v.findViewById(R.id.btn_translate)
        progressBar      = v.findViewById(R.id.progress_translate)
        textOutputLabel  = v.findViewById(R.id.text_output_label)
        textOutput       = v.findViewById(R.id.text_output)
        labelSymbols     = v.findViewById(R.id.label_symbols)
        symbolContainer  = v.findViewById(R.id.symbol_container)
        fabShare         = v.findViewById(R.id.fab_share)

        btnTranslate.setOnClickListener { runTranslation() }

        // Live region per TalkBack (complementa l'XML android:accessibilityLiveRegion="polite")
        ViewCompat.setAccessibilityLiveRegion(
            textOutput,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
        )
        ViewCompat.setAccessibilityLiveRegion(
            symbolContainer,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
        )
    }

    // ── Spinner lingua ────────────────────────────────────────────

    private fun setupSpinner() {
        val ctx   = requireContext()
        val names = ctx.resources.getStringArray(R.array.bliss_language_names)
        val codes = ctx.resources.getStringArray(R.array.bliss_language_codes)

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, names)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerLang.adapter = adapter

        // pre-select current lang
        val currentIdx = codes.indexOf(vm.langCode.value).coerceAtLeast(0)
        spinnerLang.setSelection(currentIdx)

        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val lang = codes[pos]
                if (lang != vm.langCode.value) vm.setLang(lang)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── RecyclerView suggerimenti ─────────────────────────────────

    private fun setupSuggestions() {
        rvSuggestions.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvSuggestions.adapter = suggestionAdapter
    }

    private fun updateSuggestions(symbols: List<BlissSymbol>) {
        if (symbols.isEmpty()) {
            labelSuggestions.isVisible = false
            rvSuggestions.isVisible = false
            suggestionAdapter.submitList(emptyList())
            return
        }
        // Build suggestion list: for each UNKNOWN token offer up to 3 lexicon near-matches
        val lk = vm.lookup ?: return
        val suggestions = symbols
            .filter { it.matchType == BlissSymbol.MatchType.UNKNOWN }
            .flatMap { sym ->
                lk.lexicon.keys
                    .filter { key -> key.startsWith(sym.sourceWord.take(3), ignoreCase = true) }
                    .take(3)
            }
            .distinct()
            .take(8)

        labelSuggestions.isVisible = suggestions.isNotEmpty()
        rvSuggestions.isVisible = suggestions.isNotEmpty()
        suggestionAdapter.submitList(suggestions)
    }

    // ── GlyphXBuilder init ───────────────────────────────────────────

    private fun reinitGlyphXBuilder() {
        val ctx = context ?: return
        val dm  = ctx.resources.displayMetrics
        val screenWidthPx: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION") dm.widthPixels
        }
        val cellSizePx = (CELL_SIZE_DP * dm.density).toInt()
        val symbolsPerLine = BlissGlyphXBuilder.computeSymbolsPerLine(screenWidthPx, cellSizePx)
        glyphXBuilder = BlissGlyphXBuilder(symbolsPerLine = symbolsPerLine)
    }

    // ── ViewModel observers ───────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.lookupReady.collectLatest { ready ->
                        progressBar.isVisible = !ready
                        btnTranslate.isEnabled = ready
                        if (ready) {
                            val lex = vm.lookup?.lexicon?.size ?: 0
                            textOutput.text = getString(R.string.bliss_msg_no_result)
                                .takeIf { lex == 0 } ?: ""
                        } else {
                            val lang = vm.langCode.value
                            textOutput.text = ""
                        }
                    }
                }
                launch {
                    vm.stats.collectLatest { s ->
                        if (s.total > 0) {
                            val pct = (s.coverage * 100).toInt()
                            // Update status in output label (non-invasive, muted text)
                            textOutputLabel.contentDescription =
                                "${s.total} simboli  •  ${s.unknown} sconosciuti  •  $pct%"
                        }
                    }
                }
            }
        }
    }

    // ── Translation ──────────────────────────────────────────────

    private fun runTranslation() {
        val text = editInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            vm.clear()
            symbolContainer.removeAllViews()
            textOutput.text = getString(R.string.bliss_msg_empty_input)
            fabShare.isVisible = false
            return
        }
        val lk = vm.lookup ?: run {
            textOutput.text = getString(R.string.bliss_msg_error)
            return
        }
        val builder = glyphXBuilder ?: run {
            reinitGlyphXBuilder(); glyphXBuilder
        } ?: return

        translateJob?.cancel()
        btnTranslate.isEnabled = false
        progressBar.isVisible = true
        fabShare.isVisible = false

        translateJob = viewLifecycleOwner.lifecycleScope.launch {
            val (symbols, doc) = withContext(Dispatchers.Default) {
                val syms = BlissTranslator(lk).translate(text)
                val d    = builder.build(syms)
                syms to d
            }
            vm.postTranslation(symbols, doc)
            renderChips(symbols)
            updateSuggestions(symbols)

            val tokenLine = symbols.joinToString(" ") { it.displayLabel() }
            textOutput.text = tokenLine.ifEmpty { getString(R.string.bliss_msg_no_result) }

            // TalkBack announcement
            val announcement = getString(R.string.bliss_a11y_translation_ready, symbols.size)
            symbolContainer.announceForAccessibility(announcement)

            btnTranslate.isEnabled = true
            progressBar.isVisible = false
            fabShare.isVisible = symbols.isNotEmpty()
        }
    }

    // ── Chip renderer (FlexboxLayout) ───────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) {
        symbolContainer.removeAllViews()
        if (symbols.isEmpty()) return

        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        fun Int.px() = (this * dp).toInt()

        symbols.forEach { sym ->
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
            val chip = Chip(ctx).apply {
                text = "${sym.displayLabel()}$indicatorBadge"
                textSize = 11f
                contentDescription = getString(
                    R.string.bliss_a11y_symbol_desc,
                    "${sym.name} (BCI ${sym.bciAvId}, ${sym.matchType.name})"
                )
                isCheckable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    chipColor(sym.matchType)
                )
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    it.marginEnd   = 4.px()
                    it.bottomMargin = 4.px()
                }
                setOnClickListener {
                    val indStr = if (sym.indicators.isEmpty()) "nessuno"
                                 else sym.indicators.joinToString()
                    Toast.makeText(
                        ctx,
                        "BCI-AV: ${sym.bciAvId}\nNome: ${sym.name}" +
                        "\nParola: '’${sym.sourceWord}'\nMatch: ${sym.matchType}" +
                        "\nIndicatori: $indStr",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            symbolContainer.addView(chip)
        }
    }

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
    }

    // ── FAB share SVG ─────────────────────────────────────────────

    private fun setupFabShare() {
        fabShare.setOnClickListener { shareSvg() }
    }

    private fun shareSvg() {
        val doc = vm.currentDoc.value ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val svgBytes: ByteArray = withContext(Dispatchers.IO) {
                // BlissGlyphXBuilder.toSvgBytes serialises the DOM to SVG XML
                val builder = glyphXBuilder ?: return@withContext ByteArray(0)
                builder.toSvgBytes(doc)
            }
            if (svgBytes.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.bliss_msg_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // Write to cache dir exposed via FileProvider
            val shareDir = File(requireContext().cacheDir, "bliss_share").also { it.mkdirs() }
            val svgFile  = File(shareDir, "bliss_translation.svg")
            withContext(Dispatchers.IO) { svgFile.writeBytes(svgBytes) }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                FILE_PROVIDER_AUTHORITY,
                svgFile
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/svg+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bliss_msg_share_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(sendIntent, getString(R.string.bliss_msg_share_chooser))
            )
        }
    }

    // ── Companion ───────────────────────────────────────────────

    companion object {
        private const val ARG_LANG              = "arg_lang"
        private const val DEFAULT_LANG          = "it"
        private const val CELL_SIZE_DP          = 72
        private const val FILE_PROVIDER_AUTHORITY = "com.blueapps.fileprovider"

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

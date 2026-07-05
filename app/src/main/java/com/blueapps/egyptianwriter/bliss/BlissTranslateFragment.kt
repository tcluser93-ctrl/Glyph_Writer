package com.blueapps.egyptianwriter.bliss

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * BlissTranslateFragment — Fase 4 UI + Fase 5 Accessibilità + Fase 6 Blocco B
 *
 * ## Blocco B — Anteprima real-time
 * Il [TextWatcher.afterTextChanged] lancia un Job con debounce di [DEBOUNCE_MS]
 * ms (400 ms). Ogni nuova digitazione cancella il Job precedente, quindi la
 * traduzione parte solo quando l'utente smette di scrivere.
 *
 * Il bottone "Traduci" esplicito diventa **opzionale**: di default è nascosto
 * ([btnTranslate].isVisible = false); un'icona di tastiera nella TextInputLayout
 * lo mostra/nasconde toggling [manualModeEnabled]. In modalità manuale il
 * debounce è disattivato e la traduzione parte solo al click.
 *
 * Flusso:
 *  1. Spinner lingua  → vm.setLang(lang)
 *  2. TextInputEditText testo sorgente
 *  3. RecyclerView suggerimenti predittivi orizzontale (uiState.suggestions)
 *  4. [auto] afterTextChanged → debounce 400ms → vm.translate(text)
 *     [manual] Button "Traduci" → vm.translate(text)
 *  5. FlexboxLayout chip simboli
 *  6. FAB "Condividi SVG"
 */
class BlissTranslateFragment : Fragment() {

    // ── ViewModel ───────────────────────────────────────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── Engine helper ────────────────────────────────────────────────────────────
    private var glyphXBuilder: BlissGlyphXBuilder? = null

    // ── Debounce ───────────────────────────────────────────────────────────────────
    /**
     * Pending debounce job.  Cancelled and replaced on every keystroke.
     * When [manualModeEnabled] is true this is never started.
     */
    private var debounceJob: Job? = null

    /**
     * When `true` the real-time debounce is **disabled** and translation
     * fires only via the explicit "Traduci" button.
     * Toggled by the [inputLayout] end-icon (keyboard icon).
     */
    private var manualModeEnabled: Boolean = false

    // ── Views ─────────────────────────────────────────────────────────────────────
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

    // ── Adapter ───────────────────────────────────────────────────────────────────
    private val suggestionAdapter = SuggestionAdapter { word ->
        val current   = editInput.text?.toString() ?: ""
        val lastSpace = current.lastIndexOf(' ')
        val newText   = if (lastSpace < 0) word else current.substring(0, lastSpace + 1) + word
        editInput.setText(newText)
        editInput.setSelection(newText.length)
        // Suggestion tap → traduzione immediata, ignora manualMode
        runTranslation()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val initLang = arguments?.getString(ARG_LANG)
            ?: Locale.getDefault().language.take(2).let {
                if (it in BlissLookup.SUPPORTED_LANGS) it else DEFAULT_LANG
            }
        val state = vm.uiState.value
        if (state.langCode != initLang || state.isLoading.not() && !isEngineReady(state)) {
            vm.setLang(initLang)
        }
        return inflater.inflate(R.layout.fragment_translate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSpinner()
        setupSuggestions()
        setupTextWatcher()
        reinitGlyphXBuilder()
        observeViewModel()
        setupFabShare()
        vm.uiState.value.symbols.takeIf { it.isNotEmpty() }?.let { renderChips(it) }
    }

    override fun onDestroyView() {
        // Cancel any pending debounce when the Fragment's view is torn down.
        debounceJob?.cancel()
        debounceJob = null
        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reinitGlyphXBuilder()
        glyphXBuilder?.let { vm.setBuilder(it) }
    }

    // ── View binding ──────────────────────────────────────────────────────────

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

        // Bottone esplicito — nascosto di default (modalità auto-preview attiva).
        // Rimane accessibile via end-icon toggle.
        btnTranslate.isVisible = false
        btnTranslate.setOnClickListener { runTranslation() }

        // End-icon nella TextInputLayout: icona tastiera che togla la modalità
        // manuale. setEndIconOnClickListener richiede app:endIconMode="custom"
        // e app:endIconDrawable impostati in fragment_translate.xml.
        inputLayout.setEndIconOnClickListener {
            manualModeEnabled = !manualModeEnabled
            btnTranslate.isVisible = manualModeEnabled
            // Se si rientra in auto-mode e c'è testo, ri-schedula subito
            if (!manualModeEnabled) scheduleDebounce(editInput.text?.toString() ?: "")
            val descRes = if (manualModeEnabled)
                R.string.bliss_cd_mode_manual
            else
                R.string.bliss_cd_mode_auto
            inputLayout.endIconContentDescription = getString(descRes)
        }

        textOutput.accessibilityLiveRegion      = View.ACCESSIBILITY_LIVE_REGION_POLITE
        symbolContainer.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    // ── Spinner lingua ───────────────────────────────────────────────────────────

    private fun setupSpinner() {
        val ctx   = requireContext()
        val names = ctx.resources.getStringArray(R.array.bliss_language_names)
        val codes = ctx.resources.getStringArray(R.array.bliss_language_codes)

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, names)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerLang.adapter = adapter

        val currentIdx = codes.indexOf(vm.uiState.value.langCode).coerceAtLeast(0)
        spinnerLang.setSelection(currentIdx)

        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val lang = codes[pos]
                if (lang != vm.uiState.value.langCode) vm.setLang(lang)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ── TextWatcher → debounce ────────────────────────────────────────────────

    private fun setupTextWatcher() {
        editInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: ""
                // Typeahead — sempre attivo indipendentemente dalla modalità
                vm.onSuggestionQuery(text)
                // Real-time preview — disattivato in modalità manuale
                if (!manualModeEnabled) scheduleDebounce(text)
            }
        })
    }

    /**
     * Cancels any in-flight debounce Job and schedules a new one that fires
     * [runTranslation] after [DEBOUNCE_MS] milliseconds of inactivity.
     *
     * If [text] is blank the pending job is cancelled and the output is
     * cleared immediately, without waiting for the debounce window.
     */
    private fun scheduleDebounce(text: String) {
        debounceJob?.cancel()
        if (text.isBlank()) {
            // Clear output immediately on empty input
            symbolContainer.removeAllViews()
            textOutput.text    = ""
            fabShare.isVisible = false
            vm.clearSuggestions()
            return
        }
        debounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            runTranslation()
        }
    }

    // ── RecyclerView suggerimenti ────────────────────────────────────────────────

    private fun setupSuggestions() {
        rvSuggestions.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvSuggestions.adapter = suggestionAdapter
    }

    private fun bindSuggestions(suggestions: List<String>) {
        val visible = suggestions.isNotEmpty()
        labelSuggestions.isVisible = visible
        rvSuggestions.isVisible    = visible
        suggestionAdapter.submitList(suggestions)
    }

    // ── GlyphXBuilder init ─────────────────────────────────────────────────────────

    private fun reinitGlyphXBuilder() {
        val ctx = context ?: return
        val dm  = ctx.resources.displayMetrics
        val screenWidthPx: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION") dm.widthPixels
        }
        val cellSizePx     = (CELL_SIZE_DP * dm.density).toInt()
        val symbolsPerLine = BlissGlyphXBuilder.computeSymbolsPerLine(screenWidthPx, cellSizePx)
        glyphXBuilder      = BlissGlyphXBuilder(symbolsPerLine = symbolsPerLine)
        glyphXBuilder?.let { vm.setBuilder(it) }
    }

    // ── ViewModel observers ─────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collectLatest { state ->

                    progressBar.isVisible  = state.isLoading
                    // Il bottone manuale segue isLoading solo quando visibile
                    if (btnTranslate.isVisible) btnTranslate.isEnabled = !state.isLoading

                    if (state.error != null) {
                        textOutput.text   = state.error
                        inputLayout.error = state.error
                    } else {
                        inputLayout.error = null
                    }

                    state.stats?.let { s ->
                        if (s.total > 0) {
                            val pct = (s.coverage * 100).toInt()
                            textOutputLabel.contentDescription = getString(
                                R.string.bliss_stats_desc, s.total, s.unknown, pct
                            )
                        }
                    }

                    if (state.symbols.isNotEmpty() && state.error == null) {
                        renderChips(state.symbols)
                        textOutput.text    = state.symbols.joinToString(" ") { it.displayLabel() }
                        fabShare.isVisible = true
                        announceForA11y(
                            getString(R.string.bliss_a11y_translation_ready, state.symbols.size)
                        )
                    } else if (!state.isLoading && state.error == null) {
                        fabShare.isVisible = false
                    }

                    bindSuggestions(state.suggestions)
                }
            }
        }
    }

    private fun announceForA11y(message: CharSequence) {
        val am = ContextCompat.getSystemService(requireContext(), AccessibilityManager::class.java)
            ?: return
        if (!am.isEnabled) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            text.add(message)
            className   = javaClass.name
            packageName = requireContext().packageName
        }
        am.sendAccessibilityEvent(event)
    }

    // ── Translation ────────────────────────────────────────────────────────────────

    /**
     * Fires an immediate translation request, cancelling any pending debounce.
     * Called both by the debounce Job (auto-mode) and by button/suggestion tap.
     */
    private fun runTranslation() {
        debounceJob?.cancel()          // no double-fire if called manually
        val text = editInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            vm.clearSuggestions()
            symbolContainer.removeAllViews()
            textOutput.text    = getString(R.string.bliss_msg_empty_input)
            fabShare.isVisible = false
            return
        }
        vm.translate(text)
    }

    // ── Chip renderer ───────────────────────────────────────────────────────────────

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
                    it.marginEnd    = 4.px()
                    it.bottomMargin = 4.px()
                }
                setOnClickListener {
                    val indStr = if (sym.indicators.isEmpty())
                        getString(R.string.bliss_chip_no_indicators)
                    else
                        sym.indicators.joinToString()
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.bliss_chip_tooltip,
                            sym.bciAvId, sym.name, sym.sourceWord,
                            sym.matchType.name, indStr
                        ),
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

    // ── FAB share SVG ────────────────────────────────────────────────────────────

    private fun setupFabShare() {
        fabShare.setOnClickListener { shareSvg() }
    }

    private fun shareSvg() {
        val doc = vm.uiState.value.glyphXDoc ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val svgBytes: ByteArray = withContext(Dispatchers.IO) {
                val b = glyphXBuilder ?: return@withContext ByteArray(0)
                b.toSvgBytes(doc)
            }
            if (svgBytes.isEmpty()) {
                Toast.makeText(
                    requireContext(), getString(R.string.bliss_msg_error), Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val shareDir = File(requireContext().cacheDir, "bliss_share").also { it.mkdirs() }
            val svgFile  = File(shareDir, "bliss_translation.svg")
            withContext(Dispatchers.IO) { svgFile.writeBytes(svgBytes) }
            val uri = FileProvider.getUriForFile(
                requireContext(), FILE_PROVIDER_AUTHORITY, svgFile
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

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun isEngineReady(state: BlissViewModel.UiState): Boolean =
        !state.isLoading && state.error == null && state.langCode.isNotEmpty()

    // ── Companion ─────────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG                = "arg_lang"
        private const val DEFAULT_LANG            = "it"
        private const val CELL_SIZE_DP            = 72
        private const val FILE_PROVIDER_AUTHORITY = "com.blueapps.fileprovider"

        /** Debounce window in milliseconds for real-time translation preview. */
        const val DEBOUNCE_MS = 400L

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

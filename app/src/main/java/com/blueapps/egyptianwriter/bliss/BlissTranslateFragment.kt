package com.blueapps.egyptianwriter.bliss

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.blueapps.egyptianwriter.AppPreferences
import com.blueapps.egyptianwriter.R
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
 * BlissTranslateFragment — Fase 4 UI + Fase 5 Accessibilità + Fase 6 Blocchi B e C
 *
 * ## Blocco B — Anteprima real-time
 * Il [TextWatcher.afterTextChanged] lancia un Job con debounce di [DEBOUNCE_MS] ms.
 * Un’icona tastiera nel TextInputLayout abilita la modalità manuale (bottone esplicito).
 *
 * ## Blocco C — Modalità CAA simbolo per simbolo
 * Un [SwitchMaterial] (`switch_caa_mode`) alterna tra due viste:
 *  - **Standard**: FlexboxLayout di chip compatti (comportamento precedente)
 *  - **CAA**: RecyclerView verticale ([rvCaaCards]) con card grandi
 *    ([BlissSymbolCardAdapter]) + pulsanti Indietro/Avanti ([btnPrev]/[btnNext])
 *    che scorrono il contenuto e aggiornano [caaCurrentIndex].
 *
 * ## E-01 — Rendering SVG reale nelle card CAA
 * [signProvider] è lazy e vive per l’intero ciclo di vita del Fragment.
 * [cardAdapter] viene inizializzato in [setupCaaRecycler] con [signProvider]
 * e [viewLifecycleOwner.lifecycleScope] così ogni card carica il suo
 * drawable BCI-AV in modo asincrono senza bloccare il Main Thread.
 *
 * ## E-02 — Persistenza stato CAA
 * [onSaveInstanceState] serializza [caaModeEnabled] e [caaCurrentIndex].
 * [onViewStateRestored] li ripristina allineando switch, visibilità pannelli
 * e stato dei pulsanti di navigazione.
 * [onLowMemory] svuota la cache LRU di [BlissSignProvider] (fino a 8 MB).
 *
 * ## E-06 — Haptic feedback pulsanti CAA
 * [hapticTick] viene chiamato in [btnPrev] e [btnNext] setOnClickListener.
 * Il feedback viene erogato solo se [AppPreferences.isHapticCaa] è true,
 * usando [VibrationEffect.createOneShot] su API 26+ oppure il metodo
 * legacy [Vibrator.vibrate] su API più vecchie.
 *
 * La cronologia auto-salvataggio è già gestita nel ViewModel: ogni chiamata
 * [BlissViewModel.translate] salva automaticamente in [BlissHistoryRepository].
 */
class BlissTranslateFragment : Fragment() {

    // ── ViewModel ────────────────────────────────────────────────────────────
    private val vm: BlissViewModel by activityViewModels()

    // ── E-01: BlissSignProvider — lazy, un’istanza per tutto il Fragment ─────
    private val signProvider: BlissSignProvider by lazy(LazyThreadSafetyMode.NONE) {
        BlissSignProvider(requireContext().applicationContext)
    }

    // ── Engine helper ─────────────────────────────────────────────────────
    private var glyphXBuilder: BlissGlyphXBuilder? = null

    // ── Debounce (Blocco B) ──────────────────────────────────────────────
    private var debounceJob:       Job?     = null
    private var manualModeEnabled: Boolean  = false

    // ── CAA state (Blocco C + E-02) ─────────────────────────────────────
    private var caaModeEnabled:  Boolean = false
    private var caaCurrentIndex: Int     = 0

    // ── Views ──────────────────────────────────────────────────────────
    private lateinit var spinnerLang:      Spinner
    private lateinit var inputLayout:      TextInputLayout
    private lateinit var editInput:        TextInputEditText
    private lateinit var labelSuggestions: TextView
    private lateinit var rvSuggestions:    RecyclerView
    private lateinit var btnTranslate:     MaterialButton
    private lateinit var progressBar:      ProgressBar
    private lateinit var textOutputLabel:  TextView
    private lateinit var textOutput:       TextView
    private lateinit var labelSymbols:     TextView
    private lateinit var symbolContainer:  FlexboxLayout
    private lateinit var fabShare:         ExtendedFloatingActionButton
    // Blocco C
    private lateinit var switchCaaMode:   SwitchMaterial
    private lateinit var caaContainer:    LinearLayout
    private lateinit var rvCaaCards:      RecyclerView
    private lateinit var btnPrev:         MaterialButton
    private lateinit var btnNext:         MaterialButton

    // ── Adapters ─────────────────────────────────────────────────────────
    private val suggestionAdapter = SuggestionAdapter { word ->
        val current   = editInput.text?.toString() ?: ""
        val lastSpace = current.lastIndexOf(' ')
        val newText   = if (lastSpace < 0) word else current.substring(0, lastSpace + 1) + word
        editInput.setText(newText)
        editInput.setSelection(newText.length)
        runTranslation()
    }

    private lateinit var cardAdapter: BlissSymbolCardAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────

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
        setupCaaRecycler()
        setupTextWatcher()
        reinitGlyphXBuilder()
        observeViewModel()
        setupFabShare()
        vm.uiState.value.symbols.takeIf { it.isNotEmpty() }?.let { applySymbols(it) }
    }

    // ── E-02: Persistenza stato CAA ────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CAA_MODE,  caaModeEnabled)
        outState.putInt(KEY_CAA_INDEX, caaCurrentIndex)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState ?: return

        val restoredMode  = savedInstanceState.getBoolean(KEY_CAA_MODE,  false)
        val restoredIndex = savedInstanceState.getInt(KEY_CAA_INDEX, 0)

        if (restoredMode != caaModeEnabled || restoredIndex != 0) {
            caaModeEnabled  = restoredMode
            caaCurrentIndex = restoredIndex

            switchCaaMode.setOnCheckedChangeListener(null)
            switchCaaMode.isChecked = caaModeEnabled
            switchCaaMode.setOnCheckedChangeListener { _, checked ->
                caaModeEnabled  = checked
                caaCurrentIndex = 0
                applyCaaVisibility()
                vm.uiState.value.symbols.takeIf { it.isNotEmpty() }?.let { applySymbols(it) }
            }

            applyCaaVisibility()
            vm.uiState.value.symbols.takeIf { it.isNotEmpty() }?.let { symbols ->
                cardAdapter.submitList(symbols) {
                    scrollToCard(caaCurrentIndex)
                    updateCaaNavButtons()
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        signProvider.clearCache()
    }

    override fun onDestroyView() {
        debounceJob?.cancel()
        debounceJob = null
        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reinitGlyphXBuilder()
        glyphXBuilder?.let { vm.setBuilder(it) }
    }

    // ── View binding ───────────────────────────────────────────────────

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
        switchCaaMode    = v.findViewById(R.id.switch_caa_mode)
        caaContainer     = v.findViewById(R.id.caa_container)
        rvCaaCards       = v.findViewById(R.id.rv_caa_cards)
        btnPrev          = v.findViewById(R.id.btn_prev)
        btnNext          = v.findViewById(R.id.btn_next)

        btnTranslate.isVisible = false
        btnTranslate.setOnClickListener { runTranslation() }

        inputLayout.setEndIconOnClickListener {
            manualModeEnabled = !manualModeEnabled
            btnTranslate.isVisible = manualModeEnabled
            if (!manualModeEnabled) scheduleDebounce(editInput.text?.toString() ?: "")
            inputLayout.endIconContentDescription = getString(
                if (manualModeEnabled) R.string.bliss_cd_mode_manual else R.string.bliss_cd_mode_auto
            )
        }

        switchCaaMode.setOnCheckedChangeListener { _, checked ->
            caaModeEnabled  = checked
            caaCurrentIndex = 0
            applyCaaVisibility()
            vm.uiState.value.symbols.takeIf { it.isNotEmpty() }?.let { applySymbols(it) }
        }

        // ── E-06: Nav buttons CAA con haptic feedback ──────────────────
        btnPrev.setOnClickListener {
            if (caaCurrentIndex > 0) {
                hapticTick()
                caaCurrentIndex--
                updateCaaNavButtons()
                scrollToCard(caaCurrentIndex)
            }
        }
        btnNext.setOnClickListener {
            val max = (cardAdapter.itemCount - 1).coerceAtLeast(0)
            if (caaCurrentIndex < max) {
                hapticTick()
                caaCurrentIndex++
                updateCaaNavButtons()
                scrollToCard(caaCurrentIndex)
            }
        }

        textOutput.accessibilityLiveRegion      = View.ACCESSIBILITY_LIVE_REGION_POLITE
        symbolContainer.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    // ── E-06: haptic tick ─────────────────────────────────────────────

    /**
     * Eroga una vibrazione breve (30 ms) se [AppPreferences.isHapticCaa] è attivo.
     * Usa [VibrationEffect.createOneShot] su API 26+, legacy [Vibrator.vibrate] sotto.
     * Non causa crash su dispositivi senza vibrazione: i metodi sono no-op in assenza
     * di hardware (il sistema ignora silenziosamente la chiamata).
     */
    @Suppress("DEPRECATION")
    private fun hapticTick() {
        val ctx = context ?: return
        if (!AppPreferences.isHapticCaa(ctx)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: VibratorManager
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26-30: Vibrator con VibrationEffect
            @Suppress("DEPRECATION")
            val vib = ctx.getSystemService(Vibrator::class.java)
            vib?.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            // API < 26: legacy
            @Suppress("DEPRECATION")
            val vib = ctx.getSystemService(Vibrator::class.java)
            @Suppress("DEPRECATION")
            vib?.vibrate(30L)
        }
    }

    // ── Spinner lingua ────────────────────────────────────────────────

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

    // ── TextWatcher → debounce (Blocco B) ─────────────────────────────

    private fun setupTextWatcher() {
        editInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: ""
                vm.onSuggestionQuery(text)
                if (!manualModeEnabled) scheduleDebounce(text)
            }
        })
    }

    private fun scheduleDebounce(text: String) {
        debounceJob?.cancel()
        if (text.isBlank()) {
            symbolContainer.removeAllViews()
            cardAdapter.submitList(emptyList())
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

    // ── RecyclerView suggerimenti ──────────────────────────────────────

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

    // ── RecyclerView CAA cards (Blocco C + E-01) ───────────────────────

    private fun setupCaaRecycler() {
        cardAdapter = BlissSymbolCardAdapter(
            signProvider  = signProvider,
            adapterScope  = viewLifecycleOwner.lifecycleScope,
            onCardFocused = { position, _ ->
                caaCurrentIndex = position
                updateCaaNavButtons()
                scrollToCard(position)
            }
        )
        rvCaaCards.layoutManager = LinearLayoutManager(requireContext())
        rvCaaCards.adapter       = cardAdapter
        rvCaaCards.isNestedScrollingEnabled = false
    }

    private fun applyCaaVisibility() {
        val isCAA = caaModeEnabled
        labelSymbols.isVisible    = !isCAA
        symbolContainer.isVisible = !isCAA
        caaContainer.isVisible    = isCAA
    }

    private fun updateCaaNavButtons() {
        val total = cardAdapter.itemCount
        btnPrev.isEnabled = caaCurrentIndex > 0
        btnNext.isEnabled = caaCurrentIndex < total - 1
        if (total > 0) {
            announceForA11y(getString(
                R.string.bliss_caa_position_announce,
                caaCurrentIndex + 1, total
            ))
        }
    }

    private fun scrollToCard(index: Int) {
        (rvCaaCards.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 0)
    }

    // ── GlyphXBuilder init ─────────────────────────────────────────────

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

    // ── ViewModel observers ───────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collectLatest { state ->
                    progressBar.isVisible = state.isLoading
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
                        applySymbols(state.symbols)
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

    // ── applySymbols ───────────────────────────────────────────────────

    private fun applySymbols(symbols: List<BlissSymbol>) {
        applyCaaVisibility()
        if (caaModeEnabled) renderCaaCards(symbols) else renderChips(symbols)
    }

    private fun renderCaaCards(symbols: List<BlissSymbol>) {
        caaCurrentIndex = 0
        cardAdapter.submitList(symbols)
        updateCaaNavButtons()
    }

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

    // ── FAB share SVG ─────────────────────────────────────────────────

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

    // ── Accessibility ────────────────────────────────────────────────

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

    // ── Translation ──────────────────────────────────────────────────

    private fun runTranslation() {
        debounceJob?.cancel()
        val text = editInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            vm.clearSuggestions()
            symbolContainer.removeAllViews()
            cardAdapter.submitList(emptyList())
            textOutput.text    = getString(R.string.bliss_msg_empty_input)
            fabShare.isVisible = false
            return
        }
        vm.translate(text)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun isEngineReady(state: BlissViewModel.UiState): Boolean =
        !state.isLoading && state.error == null && state.langCode.isNotEmpty()

    // ── Companion ────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG                = "arg_lang"
        private const val DEFAULT_LANG            = "it"
        private const val CELL_SIZE_DP            = 72
        private const val FILE_PROVIDER_AUTHORITY = "com.blueapps.fileprovider"
        const val DEBOUNCE_MS                     = 400L

        private const val KEY_CAA_MODE  = "caa_mode_enabled"
        private const val KEY_CAA_INDEX = "caa_current_index"

        fun newInstance(lang: String = DEFAULT_LANG): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().also { it.putString(ARG_LANG, lang) }
            }
    }
}

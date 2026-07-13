package com.blueapps.egyptianwriter.bliss

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.databinding.FragmentBlissTranslateBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ## BlissTranslateFragment — Blocco B, C, D, E, H, N, O
 *
 * Fragment principale della modalità Bliss translator. Gestisce:
 *
 * - **B-01/B-02** — input libero con traduzione debounce/manuale;
 * - **C-01…C-04** — doppia vista chip ↔ mini-card CAA;
 * - **D-01…D-05** — share bottom sheet e copia contenuto;
 * - **E-01…E-04** — rendering SVG inline e accessibilità TalkBack;
 * - **H-01/H-02** — export PNG / PDF (delegato a [BlissExportHelper]);
 * - **N-01/N-02/N-03** — MixedBlissRowView + fallback semantico;
 * - **O-01/O-02** — storico traduzioni e pin recenti.
 *
 * ## Debug log panel
 * Il campo `etDebugLog` mostra a schermo i passaggi chiave del flusso
 * di traduzione (click → ViewModel → stato → rendering).
 * Rimuovere o nascondere in produzione.
 */
class BlissTranslateFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentBlissTranslateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlissViewModel by activityViewModels()

    private lateinit var cardAdapter: BlissSymbolCardAdapter
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var debounceJob: Job? = null
    private val svgJobs = mutableListOf<Job>()

    // ── Debug log ────────────────────────────────────────────────────────────

    /** Aggiunge [message] al pannello di log visibile a schermo e a Logcat. */
    private fun appendDebugLog(message: String) {
        val b = _binding ?: return
        val old = b.etDebugLog.text?.toString().orEmpty()
        val next = if (old.isBlank()) message else "$old\n$message"
        b.etDebugLog.setText(next)
        b.etDebugLog.setSelection(next.length)
        Log.d(TAG, message)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlissTranslateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext(), this)
        setupToolbarBackHandling()
        setupInput()
        setupToggleButtons()
        setupRecyclerView()
        setupFabShare()
        observeState()
        observeOneShotEvents()
        appendDebugLog("[INIT] onViewCreated — fragment pronto")

        // ── Bootstrap motore ────────────────────────────────────────────────
        val lang = arguments?.getString(ARG_LANG) ?: "it"
        appendDebugLog("[INIT] setLang('$lang') — avvio bootstrap motore")
        viewModel.setLang(lang)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        debounceJob?.cancel()
        clearSvgJobs()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        appendDebugLog("[TTS] init status=$status ttsReady=$ttsReady")
    }

    // ── Blocco B ─────────────────────────────────────────────────────────────

    private fun setupInput() = with(binding) {
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                debounceJob?.cancel()
                debounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    if (text.isNotBlank()) {
                        appendDebugLog("[DEBOUNCE] translate='$text'")
                        viewModel.translate(text)
                    } else {
                        appendDebugLog("[DEBOUNCE] testo vuoto → clearTranslation")
                        viewModel.clearTranslation()
                    }
                }
            }
        })

        btnTranslate.setOnClickListener {
            val text = etInput.text?.toString().orEmpty().trim()
            appendDebugLog("[BTN] translate clicked; input='$text'")
            if (text.isBlank()) {
                appendDebugLog("[BTN] input vuoto — nessuna traduzione")
                Toast.makeText(requireContext(), R.string.bliss_input_empty, Toast.LENGTH_SHORT).show()
            } else {
                appendDebugLog("[BTN] invio a ViewModel.translate")
                viewModel.translate(text)
            }
        }

        btnClear.setOnClickListener {
            appendDebugLog("[BTN] clear clicked")
            debounceJob?.cancel()
            etInput.setText("")
            viewModel.clearTranslation()
        }
    }

    // ── Toggle chip/card/mixed ───────────────────────────────────────────────

    private fun setupToggleButtons() = with(binding) {
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnViewChips -> BlissViewModel.ViewMode.CHIPS
                R.id.btnViewCards -> BlissViewModel.ViewMode.CARDS
                R.id.btnViewMixed -> BlissViewModel.ViewMode.MIXED
                else -> return@addOnButtonCheckedListener
            }
            appendDebugLog("[TOGGLE] setRenderMode=$mode")
            viewModel.setRenderMode(mode)
        }
    }

    // ── Blocco C: RecyclerView card CAA ──────────────────────────────────────

    private fun setupRecyclerView() = with(binding.rvCards) {
        layoutManager = GridLayoutManager(requireContext(), 2, RecyclerView.VERTICAL, false)
        cardAdapter = BlissSymbolCardAdapter(
            signProvider = viewModel.signProvider,
            adapterScope = viewLifecycleOwner.lifecycleScope,
            onCardFocused = { _, sym -> speak(sym.gloss) }
        )
        adapter = cardAdapter
        itemAnimator = null
    }

    // ── Observe state ────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    appendDebugLog(
                        "[STATE] loading=${state.isLoading} " +
                        "symbols=${state.symbols.size} " +
                        "mode=${state.viewMode}"
                    )
                    renderState(state)
                }
            }
        }
    }

    private fun observeOneShotEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is BlissViewModel.Event.ShowToast -> {
                            appendDebugLog("[EVENT] ShowToast: ${event.message}")
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // ── Render root ──────────────────────────────────────────────────────────

    private fun renderState(state: BlissViewModel.UiState) = with(binding) {
        progressBar.isVisible = state.isLoading
        tvEmpty.isVisible = !state.isLoading && state.symbols.isEmpty()

        when (state.viewMode) {
            BlissViewModel.ViewMode.CHIPS -> {
                chipScroll.isVisible = true
                rvCards.isVisible = false
                mixedPreviewContainer.isVisible = false
                renderChips(state.symbols)
            }
            BlissViewModel.ViewMode.CARDS -> {
                chipScroll.isVisible = false
                rvCards.isVisible = true
                mixedPreviewContainer.isVisible = false
                appendDebugLog("[RENDER] CARDS submitList count=${state.symbols.size}")
                cardAdapter.submitList(state.symbols)
            }
            BlissViewModel.ViewMode.MIXED -> {
                chipScroll.isVisible = false
                rvCards.isVisible = false
                mixedPreviewContainer.isVisible = true
                renderMixedPreview(state)
            }
        }

        toggleGroup.check(
            when (state.viewMode) {
                BlissViewModel.ViewMode.CHIPS -> R.id.btnViewChips
                BlissViewModel.ViewMode.CARDS -> R.id.btnViewCards
                BlissViewModel.ViewMode.MIXED -> R.id.btnViewMixed
            }
        )
    }

    // ── Blocco E: chip SVG inline ────────────────────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) = with(binding.chipGroup) {
        clearSvgJobs()
        removeAllViews()
        appendDebugLog("[RENDER] CHIPS count=${symbols.size}")

        symbols.forEachIndexed { index, sym ->
            val chip = layoutInflater.inflate(R.layout.item_bliss_chip, this, false) as Chip
            chip.text = sym.gloss
            chip.chipBackgroundColor =
                android.content.res.ColorStateList.valueOf(chipColor(sym.matchType))
            chip.isCloseIconVisible = false
            chip.isClickable = true
            chip.isCheckable = false
            chip.contentDescription = buildChipContentDescription(sym, index, symbols.size)
            chip.setOnClickListener { speak(sym.gloss) }

            val indicatorBadge = buildString {
                val inds = sym.indicators
                if (BlissTranslator.INDICATOR_PLURAL in inds) append("× ")
                if (BlissTranslator.INDICATOR_PAST   in inds) append("↩ ")
                if (BlissTranslator.INDICATOR_FUTURE in inds) append("→ ")
            }.trim()
            if (indicatorBadge.isNotEmpty()) chip.text = "${chip.text}  $indicatorBadge"

            val job = viewLifecycleOwner.lifecycleScope.launch {
                val drawable = withTimeoutOrNull(2500) {
                    viewModel.signProvider.getDrawableAsync(
                        "B${sym.bciAvId}",
                        96f * resources.displayMetrics.density
                    )
                }
                if (!isAdded) return@launch
                if (drawable != null && drawable !is BlissSignProvider.PlaceholderDrawable) {
                    chip.chipIcon = drawable
                    chip.isChipIconVisible = true
                } else {
                    chip.isChipIconVisible = false
                    appendDebugLog("[SVG] placeholder o null per B${sym.bciAvId} (${sym.gloss})")
                }
            }
            svgJobs += job
            addView(chip)
        }
    }

    private fun buildChipContentDescription(sym: BlissSymbol, index: Int, total: Int): String {
        val match = when (sym.matchType) {
            BlissSymbol.MatchType.EXACT            -> "corrispondenza esatta"
            BlissSymbol.MatchType.LEMMA            -> "lemma"
            BlissSymbol.MatchType.NGRAM            -> "frase"
            BlissSymbol.MatchType.FALLBACK_CATEGORY-> "categoria"
            BlissSymbol.MatchType.COMPOUND         -> "composto"
            BlissSymbol.MatchType.SEMANTIC         -> "semantico"
            BlissSymbol.MatchType.UNKNOWN          -> "sconosciuto"
            BlissSymbol.MatchType.FUNCTION_WORD    -> "parola funzione"
        }
        return "${sym.gloss}, parola ${index + 1} di $total, $match"
    }

    // ── Blocco N: MixedBlissRowView ──────────────────────────────────────────

    private fun renderMixedPreview(state: BlissViewModel.UiState) {
        val slots = buildMixedSlots(state.symbols, state.composedWords)
        appendDebugLog("[RENDER] MIXED slots=${slots.size}")
        binding.mixedPreview.bind(slots)
    }

    /**
     * Costruisce la lista di [MixedTokenSlot] da symbols + composedWords.
     * - Se composedWords[i] != null → SvgSlot (token structured)
     * - Altrimenti → ChipSlot (token classic)
     */
    private fun buildMixedSlots(
        symbols: List<BlissSymbol>,
        composedWords: List<ComposedBlissWord?>
    ): List<MixedTokenSlot> = symbols.mapIndexed { i, sym ->
        val composed = composedWords.getOrNull(i)
        if (composed != null) MixedTokenSlot.SvgSlot(i, composed)
        else MixedTokenSlot.ChipSlot(i, sym)
    }

    private fun clearSvgJobs() {
        svgJobs.forEach { it.cancel() }
        svgJobs.clear()
    }

    private fun chipColor(mt: BlissSymbol.MatchType): Int = when (mt) {
        BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
        BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
        BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
        BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
        BlissSymbol.MatchType.COMPOUND          -> 0xFFE8D5FF.toInt()
        BlissSymbol.MatchType.SEMANTIC          -> 0xFFD5EAFF.toInt()
        BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
        BlissSymbol.MatchType.FUNCTION_WORD     -> 0xFFB0F0F0.toInt()
    }

    // ── Blocco D: FAB share ───────────────────────────────────────────────────

    private fun setupFabShare() {
        binding.fabShare.setOnClickListener {
            val text = binding.etInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), R.string.bliss_input_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExportBottomSheetFragment.newInstance(text)
                .show(parentFragmentManager, "bliss_export_sheet")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), R.string.bliss_copied, Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank())
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bliss-$text")
    }

    private fun setupToolbarBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BlissTranslateFragment"
        private const val ARG_LANG = "arg_lang"

        fun newInstance(lang: String = "it"): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().apply { putString(ARG_LANG, lang) }
            }
    }
}

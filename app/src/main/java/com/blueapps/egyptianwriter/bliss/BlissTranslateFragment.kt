package com.blueapps.egyptianwriter.bliss

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.databinding.FragmentBlissTranslateBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

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
 * ### Responsabilità principali
 *
 * 1. Osserva [BlissViewModel.uiState] e aggiorna il rendering.
 * 2. Mantiene la coerenza tra testo di input, risultato chip, vista card CAA e preview mista.
 * 3. Gestisce i job SVG asincroni per evitare late-binding su Chip riciclati.
 * 4. Coordina TTS, copy/share/export senza duplicare logica del ViewModel.
 *
 * ### Convenzioni visive
 *
 * I chip sono colorati in base a [BlissSymbol.matchType] via [chipColor].
 * I mini-card CAA usano [BlissSymbolCardAdapter] con lo stesso badge semantico.
 *
 * ### Accessibilità
 *
 * - I chip hanno `contentDescription` parlante tramite [buildChipContentDescription].
 * - Le card CAA hanno ordine di lettura immagine → gloss → parola.
 * - La preview mista è solo decorativa: TalkBack usa il testo descrittivo separato.
 */
class BlissTranslateFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentBlissTranslateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlissViewModel by activityViewModels()

    private lateinit var cardAdapter: BlissSymbolCardAdapter
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    /** Job debounce per B-01 */
    private var debounceJob: Job? = null

    /** Job SVG inline dei chip (E-01) — cancellati a ogni re-render */
    private val svgJobs = mutableListOf<Job>()

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        debounceJob?.cancel()
        clearSvgJobs()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    // ── Blocco B: input + trigger traduzione ─────────────────────────────────

    private fun setupInput() = with(binding) {
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                debounceJob?.cancel()
                debounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    if (text.isNotBlank()) viewModel.translate(text)
                    else viewModel.clearTranslation()
                }
            }
        })

        btnTranslate.setOnClickListener {
            val text = etInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), R.string.bliss_input_empty, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.translate(text)
            }
        }

        btnClear.setOnClickListener {
            debounceJob?.cancel()
            etInput.setText("")
            viewModel.clearTranslation()
        }
    }

    // ── Toggle chip/card/mixed ───────────────────────────────────────────────

    private fun setupToggleButtons() = with(binding) {
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnViewChips -> viewModel.setRenderMode(BlissViewModel.RenderMode.CHIPS)
                R.id.btnViewCards -> viewModel.setRenderMode(BlissViewModel.RenderMode.CARDS)
                R.id.btnViewMixed -> viewModel.setRenderMode(BlissViewModel.RenderMode.MIXED)
            }
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
                        is BlissViewModel.Event.ShowToast ->
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── Render root ──────────────────────────────────────────────────────────

    private fun renderState(state: BlissViewModel.UiState) = with(binding) {
        progressBar.isVisible = state.loading
        tvEmpty.isVisible = !state.loading && state.symbols.isEmpty()

        when (state.renderMode) {
            BlissViewModel.RenderMode.CHIPS -> {
                chipScroll.isVisible = true
                rvCards.isVisible = false
                mixedPreviewContainer.isVisible = false
                renderChips(state.symbols)
            }
            BlissViewModel.RenderMode.CARDS -> {
                chipScroll.isVisible = false
                rvCards.isVisible = true
                mixedPreviewContainer.isVisible = false
                cardAdapter.submitList(state.symbols)
            }
            BlissViewModel.RenderMode.MIXED -> {
                chipScroll.isVisible = false
                rvCards.isVisible = false
                mixedPreviewContainer.isVisible = true
                renderMixedPreview(state.symbols)
            }
        }

        toggleGroup.check(
            when (state.renderMode) {
                BlissViewModel.RenderMode.CHIPS -> R.id.btnViewChips
                BlissViewModel.RenderMode.CARDS -> R.id.btnViewCards
                BlissViewModel.RenderMode.MIXED -> R.id.btnViewMixed
            }
        )
    }

    // ── Blocco E: chip SVG inline ────────────────────────────────────────────

    private fun renderChips(symbols: List<BlissSymbol>) = with(binding.chipGroup) {
        clearSvgJobs()
        removeAllViews()

        symbols.forEachIndexed { index, sym ->
            val chip = layoutInflater.inflate(R.layout.item_bliss_chip, this, false) as Chip
            chip.text = sym.gloss
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(chipColor(sym.matchType))
            chip.isCloseIconVisible = false
            chip.isClickable = true
            chip.isCheckable = false
            chip.contentDescription = buildChipContentDescription(sym, index, symbols.size)
            chip.setOnClickListener { speak(sym.gloss) }

            // Badge indicatori
            val indicatorBadge = buildString {
                val inds = sym.indicators
                if (BlissTranslator.INDICATOR_PLURAL in inds) append("× ")
                if (BlissTranslator.INDICATOR_PAST   in inds) append("↩ ")
                if (BlissTranslator.INDICATOR_FUTURE in inds) append("→ ")
            }.trim()
            if (indicatorBadge.isNotEmpty()) chip.text = "${chip.text}  $indicatorBadge"

            // Caricamento SVG come start icon
            val job = viewLifecycleOwner.lifecycleScope.launch {
                val drawable = withTimeoutOrNull(2500) {
                    viewModel.signProvider.getDrawableAsync("B${sym.bciAvId}", 96f * resources.displayMetrics.density)
                }
                if (!isAdded) return@launch
                if (drawable != null && drawable !is BlissSignProvider.PlaceholderDrawable) {
                    chip.chipIcon = drawable
                    chip.isChipIconVisible = true
                } else {
                    chip.isChipIconVisible = false
                }
            }
            svgJobs += job
            addView(chip)
        }
    }

    private fun buildChipContentDescription(sym: BlissSymbol, index: Int, total: Int): String {
        val match = when (sym.matchType) {
            BlissSymbol.MatchType.EXACT -> "corrispondenza esatta"
            BlissSymbol.MatchType.LEMMA -> "lemma"
            BlissSymbol.MatchType.NGRAM -> "frase"
            BlissSymbol.MatchType.FALLBACK_CATEGORY -> "categoria"
            BlissSymbol.MatchType.COMPOUND -> "composto"
            BlissSymbol.MatchType.SEMANTIC -> "semantico"
            BlissSymbol.MatchType.UNKNOWN -> "sconosciuto"
            BlissSymbol.MatchType.FUNCTION_WORD -> "parola funzione"
        }
        return "${sym.gloss}, parola ${index + 1} di $total, $match"
    }

    private fun renderMixedPreview(symbols: List<BlissSymbol>) = with(binding.mixedPreview) {
        removeAllViews()
        setSymbols(symbols)
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

    // ── Blocco D: FAB share → ExportBottomSheetFragment ───────────────

    private fun setupFabShare() {
        binding.fabShare.setOnClickListener {
            val text = binding.etInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), R.string.bliss_input_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExportBottomSheetFragment.newInstance(text).show(parentFragmentManager, "bliss_export_sheet")
        }
    }

    // ── Helpers share/copy/export ────────────────────────────────────────────

    private fun copyToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), R.string.bliss_copied, Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bliss-$text")
        }
    }

    private fun setupToolbarBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_LANG = "arg_lang"

        /**
         * Crea una nuova istanza del fragment con la lingua iniziale.
         * @param lang codice BCP-47 a 2 caratteri (es. "it", "en"), default "it".
         */
        fun newInstance(lang: String = "it"): BlissTranslateFragment =
            BlissTranslateFragment().apply {
                arguments = Bundle().apply { putString(ARG_LANG, lang) }
            }
    }
}

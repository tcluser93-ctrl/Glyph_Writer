package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.databinding.BottomSheetExportBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document

/**
 * BottomSheet per l'export/condivisione della traduzione corrente come
 * SVG / PNG / PDF (Blocco D, H-01/H-02).
 *
 * ## Fix (enterprise-grade audit, 2026-07-20)
 * Questa classe condivideva nome e layout XML (`bottom_sheet_export.xml`)
 * con `com.blueapps.egyptianwriter.ui.ExportBottomSheetFragment`, che
 * implementava il vero export via [BlissExportHelper] (SVG/PNG/PDF reali con
 * `FileProvider`) — ma quella classe non veniva **mai** istanziata da
 * nessun punto raggiungibile dell'app: `BlissTranslateFragment`, stando
 * nello stesso package `bliss`, risolveva `ExportBottomSheetFragment` (senza
 * import) su QUESTA classe, che era invece uno stub (SVG → condividi testo
 * grezzo; PNG/PDF → copia solo il testo negli appunti). Risultato: i
 * pulsanti "Salva come PNG/PDF" non hanno mai prodotto un vero file
 * immagine/PDF, nonostante il KDoc del Fragment padre dichiarasse l'export
 * "delegato a BlissExportHelper" (H-01/H-02).
 *
 * La classe `ui.ExportBottomSheetFragment` (irraggiungibile) è stata
 * rimossa; l'implementazione reale è stata portata qui. Il documento GlyphX
 * viene costruito al volo da [BlissViewModel.UiState.symbols] tramite
 * [BlissViewModel.getBuilder], indipendentemente dal `renderMode` corrente
 * (in modalità STRUCTURED/MIXED `UiState.glyphXDoc` resta `null`, ma
 * l'export flat-grid rimane comunque significativo), così l'export funziona
 * per qualunque traduzione con almeno un simbolo prodotto.
 */
class ExportBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlissViewModel by activityViewModels()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Nulla da esportare (es. sheet riaperto dopo process death, o
        // aperto prima che una traduzione sia mai stata completata).
        if (viewModel.uiState.value.symbols.isEmpty()) {
            dismiss()
            return
        }
        setupClickListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnExportSvg.setOnClickListener {
            export("image/svg+xml") { ctx, builder, doc -> BlissExportHelper.exportSvg(ctx, builder, doc) }
        }
        binding.btnExportPng.setOnClickListener {
            export("image/png") { ctx, builder, doc -> BlissExportHelper.exportPng(ctx, builder, doc) }
        }
        binding.btnExportPdf.setOnClickListener {
            export("application/pdf") { ctx, builder, doc -> BlissExportHelper.exportPdf(ctx, builder, doc) }
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun export(
        mimeType: String,
        produce: suspend (Context, BlissGlyphXBuilder, Document) -> Uri?
    ) {
        val symbols = viewModel.uiState.value.symbols
        val ctx = context
        if (symbols.isEmpty() || ctx == null) {
            Toast.makeText(requireContext(), R.string.bliss_input_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val builder = viewModel.getBuilder()
        // viewLifecycleOwner.lifecycleScope (not the Fragment-level lifecycleScope):
        // auto-cancels if the bottom sheet's view is torn down mid-export,
        // instead of leaking a coroutine tied to a Fragment that may outlive it.
        viewLifecycleOwner.lifecycleScope.launch {
            val doc = withContext(Dispatchers.Default) { builder.build(symbols) }
            val uri = produce(ctx, builder, doc)
            if (uri == null) {
                Toast.makeText(ctx, R.string.export_error_generic, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent.createChooser(
                    BlissExportHelper.shareIntent(uri, mimeType),
                    getString(R.string.export_share_title)
                )
            )
            dismiss()
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "ExportBottomSheet"

        /**
         * Crea una nuova istanza del bottom sheet. L'export legge i simboli
         * direttamente dallo stato condiviso di [BlissViewModel] (via
         * `activityViewModels()`), quindi non richiede più argomenti — a
         * differenza della vecchia API che passava solo il testo grezzo
         * d'input (insufficiente per un export reale, che opera sui
         * [BlissSymbol] risolti, non sul testo).
         */
        fun newInstance(): ExportBottomSheetFragment = ExportBottomSheetFragment()
    }
}

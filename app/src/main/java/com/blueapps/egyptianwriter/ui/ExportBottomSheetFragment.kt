package com.blueapps.egyptianwriter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.bliss.BlissExportHelper
import com.blueapps.egyptianwriter.bliss.BlissGlyphXBuilder
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.w3c.dom.Document

/**
 * Blocco D - Bottom sheet che mostra tre opzioni di export: SVG, PNG, PDF.
 *
 * Come usarlo dal Fragment genitore:
 *
 *     ExportBottomSheetFragment
 *         .newInstance(builder, glyphXDocument)
 *         .show(childFragmentManager, TAG)
 *
 * Il Fragment mantiene riferimenti transient a [builder] e [doc]: non vengono
 * serializzati in Bundle perche Document non e Parcelable. Se il processo
 * viene ricreato il bottom sheet si chiude automaticamente senza crash.
 */
class ExportBottomSheetFragment : BottomSheetDialogFragment() {

    // Passed programmatically - not via Bundle (Document is not Parcelable)
    private var builder: BlissGlyphXBuilder? = null
    private var doc: Document? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_export, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentBuilder = builder
        val currentDoc     = doc

        // If we lost our references (process death), just close
        if (currentBuilder == null || currentDoc == null) {
            dismiss()
            return
        }

        view.findViewById<Button>(R.id.btn_export_svg).setOnClickListener {
            export("image/svg+xml") { ctx ->
                BlissExportHelper.exportSvg(ctx, currentBuilder, currentDoc)
            }
        }

        view.findViewById<Button>(R.id.btn_export_png).setOnClickListener {
            export("image/png") { ctx ->
                BlissExportHelper.exportPng(ctx, currentBuilder, currentDoc)
            }
        }

        view.findViewById<Button>(R.id.btn_export_pdf).setOnClickListener {
            export("application/pdf") { ctx ->
                BlissExportHelper.exportPdf(ctx, currentBuilder, currentDoc)
            }
        }
    }

    // ---- private helpers ----------------------------------------------------

    private fun export(
        mimeType: String,
        produce: suspend (android.content.Context) -> android.net.Uri?
    ) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val uri = produce(ctx)
            if (uri == null) {
                Toast.makeText(ctx,
                    getString(R.string.export_error_generic),
                    Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                android.content.Intent.createChooser(
                    BlissExportHelper.shareIntent(uri, mimeType),
                    getString(R.string.export_share_title)
                )
            )
            dismiss()
        }
    }

    companion object {
        const val TAG = "ExportBottomSheet"

        fun newInstance(
            builder: BlissGlyphXBuilder,
            doc: Document
        ): ExportBottomSheetFragment = ExportBottomSheetFragment().also {
            it.builder = builder
            it.doc     = doc
        }
    }
}

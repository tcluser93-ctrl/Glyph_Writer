package com.blueapps.egyptianwriter.bliss

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.databinding.BottomSheetExportBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet per le azioni di export/share del testo tradotto (Blocco D).
 *
 * Accetta il testo di input via [newInstance] e propone:
 * - **Condividi testo** — Intent.ACTION_SEND
 * - **Copia testo** — ClipboardManager
 *
 * Le azioni PNG/PDF (H-01/H-02) sono delegate a [BlissExportHelper]
 * e possono essere aggiunte ai listener sottostanti quando il helper
 * sarà pronto a fornire un Bitmap/File.
 */
class ExportBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExportBinding? = null
    private val binding get() = _binding!!

    private var inputText: String = ""

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
        inputText = arguments?.getString(ARG_TEXT).orEmpty()
        setupClickListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Condividi testo
        binding.btnShareText.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, inputText)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.bliss_share_title)))
            dismiss()
        }

        // Copia negli appunti
        binding.btnCopyText.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bliss", inputText))
            Toast.makeText(requireContext(), R.string.bliss_copied, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_TEXT = "arg_text"
        private const val TAG = "ExportBottomSheetFragment"

        /**
         * Crea una nuova istanza del bottom sheet con il testo da esportare.
         * @param text stringa di input già digitata dall'utente.
         */
        fun newInstance(text: String): ExportBottomSheetFragment =
            ExportBottomSheetFragment().apply {
                arguments = Bundle().apply { putString(ARG_TEXT, text) }
            }
    }
}

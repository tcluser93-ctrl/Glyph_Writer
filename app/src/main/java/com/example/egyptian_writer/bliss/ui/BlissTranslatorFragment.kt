package com.example.egyptian_writer.bliss.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.example.egyptian_writer.R
import com.example.egyptian_writer.bliss.data.BlissDatabase
import com.example.egyptian_writer.bliss.data.BlissEntry
import com.example.egyptian_writer.bliss.data.BlissRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Fragment principale del traduttore Bliss.
 *
 * Flusso:
 *   Utente scrive frase → [Traduci] → ViewModel.translate()
 *   ↓
 *   BlissUiState.Success → striscia simboli (zona C) + RecyclerView lookup (zona D)
 *   BlissUiState.Empty   → empty state visibile
 *   BlissUiState.Loading → placeholder animato (future: ProgressBar)
 */
class BlissTranslatorFragment : Fragment() {

    // ── ViewModel ─────────────────────────────────────────────────────────────
    private val viewModel: BlissTranslatorViewModel by viewModels {
        val db = BlissDatabase.getInstance(requireContext())
        BlissTranslatorViewModel.Factory(BlissRepository(db.blissDao()))
    }

    // ── View refs ─────────────────────────────────────────────────────────────
    private lateinit var inputEdit: TextInputEditText
    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var symbolRow: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BlissResultAdapter

    // ── Inflate ───────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bliss_translator, container, false)

    // ── View binding manuale ──────────────────────────────────────────────────
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputEdit    = view.findViewById(R.id.bliss_input_edit)
        btnTranslate = view.findViewById(R.id.bliss_btn_translate)
        btnClear     = view.findViewById(R.id.bliss_btn_clear)
        btnCopy      = view.findViewById(R.id.bliss_btn_copy)
        symbolRow    = view.findViewById(R.id.bliss_symbol_row)
        emptyState   = view.findViewById(R.id.bliss_empty_state)
        recycler     = view.findViewById(R.id.bliss_recycler)

        // Adapter RecyclerView
        adapter = BlissResultAdapter { entry ->
            // TODO Fase 4: aprire BottomSheet dettaglio simbolo
        }
        recycler.adapter = adapter

        // TextWatcher → aggiorna ViewModel
        inputEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onInputChanged(s?.toString() ?: "")
            }
        })

        // Bottoni
        btnTranslate.setOnClickListener { viewModel.translate() }
        btnClear.setOnClickListener {
            inputEdit.setText("")
            viewModel.clear()
        }
        btnCopy.setOnClickListener { copyBciIds() }

        // Osserva stato UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    // ── Render state ──────────────────────────────────────────────────────────
    private fun renderState(state: BlissUiState) {
        when (state) {
            is BlissUiState.Idle -> {
                symbolRow.removeAllViews()
                adapter.submitList(emptyList())
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
            }
            is BlissUiState.Loading -> {
                // Fase futura: mostrare ProgressBar
            }
            is BlissUiState.Success -> {
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                renderSymbolStrip(state.symbolStrip)
                renderResultList(state.matches)
            }
            is BlissUiState.Empty -> {
                symbolRow.removeAllViews()
                adapter.submitList(emptyList())
                emptyState.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            }
            is BlissUiState.Error -> {
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Striscia simboli (zona C) ─────────────────────────────────────────────
    private fun renderSymbolStrip(strip: List<BlissEntry?>) {
        symbolRow.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (entry in strip) {
            val chip = inflater.inflate(
                R.layout.bliss_symbol_chip, symbolRow, false
            )
            val labelTv = chip.findViewById<TextView>(R.id.chip_bliss_label)
            val imgV    = chip.findViewById<ImageView>(R.id.chip_bliss_image)

            if (entry != null) {
                labelTv.text = entry.wordIt
                // Fase 4: imgV.load(bciSvgUrl(entry))
                imgV.setImageResource(android.R.drawable.ic_menu_gallery)
                chip.isSelected = false
                chip.setOnClickListener { chip.isSelected = !chip.isSelected }
            } else {
                // Token senza match: mostra "?" con sfondo error
                labelTv.text = "?"
                imgV.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }

            symbolRow.addView(chip)
        }
    }

    // ── Lista lookup (zona D) ─────────────────────────────────────────────────
    private fun renderResultList(matches: Map<String, List<BlissEntry>>) {
        // Flatten: tutti i candidati di tutti i token, in ordine
        val flat = matches.values.flatten().distinctBy { it.id }
        adapter.submitList(flat)
    }

    // ── Copia BCI-AV IDs negli appunti ───────────────────────────────────────
    private fun copyBciIds() {
        val state = viewModel.uiState.value
        if (state !is BlissUiState.Success) return

        val ids = state.symbolStrip
            .filterNotNull()
            .flatMap { it.bciAvIds }
            .joinToString(", ")

        if (ids.isBlank()) return

        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BCI-AV IDs", ids))
        Toast.makeText(
            requireContext(),
            getString(R.string.bliss_copy_success),
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        fun newInstance() = BlissTranslatorFragment()
    }
}

package com.blueapps.egyptianwriter.bliss

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BlissHistoryFragment
 *
 * Mostra la cronologia delle traduzioni salvate in [BlissHistoryRepository].
 * Supporta:
 *  - Lista card (testo sorgente, lingua, timestamp, contatore simboli, copertura)
 *  - Empty-state animato quando la lista è vuota
 *  - Swipe-to-delete con undo via Snackbar
 *  - FAB "cancella tutto" con conferma via Snackbar
 *  - Tap su voce → ripristina la traduzione nel Fragment traduttore
 */
class BlissHistoryFragment : Fragment() {

    private val vm: BlissViewModel by activityViewModels()

    private lateinit var rvHistory:  RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var fabClear:   FloatingActionButton

    private val adapter = HistoryAdapter(
        onItemClick = { entry ->
            vm.restoreFromHistory(entry)
            parentFragmentManager.popBackStack()
        },
        onItemDelete = { entry ->
            vm.deleteHistoryEntry(entry.id)
            Snackbar.make(
                requireView(),
                getString(R.string.history_deleted),
                Snackbar.LENGTH_LONG
            ).setAction(getString(R.string.undo)) {
                vm.restoreHistoryEntry(entry)
            }.show()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvHistory  = view.findViewById(R.id.rv_history)
        emptyState = view.findViewById(R.id.empty_state)
        fabClear   = view.findViewById(R.id.fab_clear_history)

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = adapter

        ItemTouchHelper(SwipeToDeleteCallback { pos ->
            val entry = adapter.currentList[pos]
            adapter.onItemDelete(entry)
        }).attachToRecyclerView(rvHistory)

        fabClear.setOnClickListener {
            val count = adapter.currentList.size
            if (count == 0) return@setOnClickListener
            val backup = adapter.currentList.toList()
            vm.clearHistory()
            Snackbar.make(
                requireView(),
                resources.getQuantityString(R.plurals.history_cleared, count, count),
                Snackbar.LENGTH_LONG
            ).setAction(getString(R.string.undo)) {
                vm.restoreHistoryEntries(backup)
            }.show()
        }

        observeHistory()
    }

    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState
                    .map { it.history }
                    .collect { entries ->
                        adapter.submitList(entries)
                        val empty = entries.isEmpty()
                        rvHistory.isVisible  = !empty
                        emptyState.isVisible = empty
                        fabClear.isVisible   = !empty
                    }
            }
        }
    }

    // ── HistoryAdapter ────────────────────────────────────────────────────

    inner class HistoryAdapter(
        private val onItemClick:  (BlissHistoryEntry) -> Unit,
        val          onItemDelete: (BlissHistoryEntry) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<BlissHistoryEntry,
            HistoryAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<BlissHistoryEntry>() {
            override fun areItemsTheSame(a: BlissHistoryEntry, b: BlissHistoryEntry) =
                a.id == b.id
            override fun areContentsTheSame(a: BlissHistoryEntry, b: BlissHistoryEntry) =
                a == b
        }
    ) {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textSource:      TextView = view.findViewById(R.id.text_source)
            val textTimestamp:   TextView = view.findViewById(R.id.text_timestamp)
            val textGloss:       TextView = view.findViewById(R.id.text_gloss)
            val textLang:        TextView = view.findViewById(R.id.text_lang)
            val textSymbolCount: TextView = view.findViewById(R.id.text_symbol_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = getItem(position)
            val ctx   = holder.itemView.context
            val fmt   = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
            val symbolCount = entry.symbolIds.size

            holder.textSource.text      = entry.inputText
            holder.textTimestamp.text   = fmt.format(Date(entry.timestampMs))
            // glossLine non è persistita: mostriamo la copertura come indicatore
            holder.textGloss.text       = ctx.getString(
                R.string.history_coverage_fmt,
                (entry.coverage * 100).toInt()
            )
            holder.textLang.text        = entry.langCode.uppercase()
            holder.textSymbolCount.text = ctx.resources.getQuantityString(
                R.plurals.history_symbol_count, symbolCount, symbolCount
            )
            holder.itemView.setOnClickListener { onItemClick(entry) }
        }
    }

    // ── SwipeToDeleteCallback ─────────────────────────────────────────────

    private inner class SwipeToDeleteCallback(
        private val onSwiped: (Int) -> Unit
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            onSwiped(viewHolder.adapterPosition)
        }
    }

    companion object {
        fun newInstance() = BlissHistoryFragment()
    }
}

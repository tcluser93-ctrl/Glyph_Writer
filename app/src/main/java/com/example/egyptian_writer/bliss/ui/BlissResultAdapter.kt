package com.example.egyptian_writer.bliss.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.egyptian_writer.R
import com.example.egyptian_writer.bliss.data.BlissEntry

/**
 * Adapter per la RecyclerView dei risultati lookup Bliss.
 * Ogni item mostra: simbolo placeholder | parola IT | traduzioni EN/DE | badge POS | BCI-AV ID.
 */
class BlissResultAdapter(
    private val onItemClick: (BlissEntry) -> Unit = {}
) : ListAdapter<BlissEntry, BlissResultAdapter.ViewHolder>(DIFF) {

    // ── ViewHolder ────────────────────────────────────────────────────────────
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val symbolImg: ImageView = view.findViewById(R.id.item_bliss_symbol_img)
        val wordTv: TextView     = view.findViewById(R.id.item_bliss_word)
        val transTv: TextView    = view.findViewById(R.id.item_bliss_translations)
        val posBadge: TextView   = view.findViewById(R.id.item_bliss_pos_badge)
        val bciIdTv: TextView    = view.findViewById(R.id.item_bliss_bci_id)
    }

    // ── Inflate ───────────────────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bliss_result, parent, false)
        return ViewHolder(v)
    }

    // ── Bind ──────────────────────────────────────────────────────────────────
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)

        // Parola italiana
        holder.wordTv.text = entry.wordIt

        // Traduzioni secondarie (EN · DE)
        val translations = buildList {
            entry.wordEn?.let { add(it) }
            entry.wordDe?.let { add(it) }
        }.joinToString(" · ")
        holder.transTv.text = translations.ifBlank { "—" }

        // Badge POS
        holder.posBadge.text = entry.pos.uppercase()
        holder.posBadge.visibility = if (entry.pos.isNotBlank()) View.VISIBLE else View.GONE

        // BCI-AV ID (primo della lista se multipli)
        val firstId = entry.bciAvIds.firstOrNull()
        holder.bciIdTv.text = if (firstId != null) "BCI·$firstId" else ""

        // Placeholder simbolo (Fase 4: sostituire con Coil + SVG loader)
        // holder.symbolImg.load("https://blissymbolics.org/images/$firstId.svg")
        holder.symbolImg.setImageResource(android.R.drawable.ic_menu_gallery)

        // Click
        holder.itemView.setOnClickListener { onItemClick(entry) }
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlissEntry>() {
            override fun areItemsTheSame(a: BlissEntry, b: BlissEntry) =
                a.id == b.id
            override fun areContentsTheSame(a: BlissEntry, b: BlissEntry) =
                a == b
        }
    }
}

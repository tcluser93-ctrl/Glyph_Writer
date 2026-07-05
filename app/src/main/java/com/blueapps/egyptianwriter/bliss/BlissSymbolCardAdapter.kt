package com.blueapps.egyptianwriter.bliss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R

/**
 * BlissSymbolCardAdapter — Blocco C (modalità CAA)
 *
 * Mostra ogni [BlissSymbol] come una card grande con:
 *  - gloss (nome canonico BCI-AV) — testo grande e leggibile, centrato
 *  - parola sorgente dell'utente — sotto, in secondario
 *  - tipo di match (EXACT / LEMMA / …) — badge colorato
 *
 * Il rendering SVG del simbolo avviene nel Fragment tramite [BlissGlyphXBuilder];
 * la card riserva un placeholder `@+id/card_symbol_svg_placeholder` che il
 * Fragment può popolare iniettando una ImageView con il Bitmap SVG dopo il bind.
 *
 * ## Navigazione
 * Il Fragment mantiene [currentIndex] e chiama [scrollToPosition] sul
 * [RecyclerView]. L'adapter notifica solo le righe cambiate grazie a [DiffUtil].
 */
class BlissSymbolCardAdapter(
    private val onCardFocused: (position: Int, symbol: BlissSymbol) -> Unit = { _, _ -> }
) : ListAdapter<BlissSymbol, BlissSymbolCardAdapter.CardViewHolder>(DIFF) {

    // ── ViewHolder ───────────────────────────────────────────────────────────

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvGloss:      TextView = itemView.findViewById(R.id.card_gloss)
        private val tvSourceWord: TextView = itemView.findViewById(R.id.card_source_word)
        private val tvMatchBadge: TextView = itemView.findViewById(R.id.card_match_badge)
        private val tvPosition:   TextView = itemView.findViewById(R.id.card_position)

        fun bind(symbol: BlissSymbol, position: Int, total: Int) {
            tvGloss.text      = symbol.gloss
            tvSourceWord.text = symbol.sourceWord.ifBlank { "—" }
            tvMatchBadge.text = matchLabel(symbol.matchType)
            tvMatchBadge.setBackgroundColor(matchColor(symbol.matchType))
            tvPosition.text   = "${position + 1} / $total"

            itemView.contentDescription = buildString {
                append(symbol.gloss)
                if (symbol.sourceWord.isNotBlank()) append(", parola: ${symbol.sourceWord}")
                append(", ${position + 1} di $total")
            }
            itemView.setOnClickListener { onCardFocused(position, symbol) }
        }

        private fun matchLabel(mt: BlissSymbol.MatchType): String = when (mt) {
            BlissSymbol.MatchType.EXACT             -> "exact"
            BlissSymbol.MatchType.LEMMA             -> "lemma"
            BlissSymbol.MatchType.NGRAM             -> "frase"
            BlissSymbol.MatchType.FALLBACK_CATEGORY -> "categoria"
            BlissSymbol.MatchType.UNKNOWN           -> "sconosciuto"
        }

        private fun matchColor(mt: BlissSymbol.MatchType): Int = when (mt) {
            BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
            BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
            BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
            BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
            BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
        }
    }

    // ── Adapter overrides ────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder =
        CardViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_symbol_card, parent, false)
        )

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position), position, itemCount)
    }

    // ── DiffUtil ─────────────────────────────────────────────────────────────

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlissSymbol>() {
            override fun areItemsTheSame(a: BlissSymbol, b: BlissSymbol) =
                a.bciAvId == b.bciAvId && a.sourceWord == b.sourceWord
            override fun areContentsTheSame(a: BlissSymbol, b: BlissSymbol) = a == b
        }
    }
}

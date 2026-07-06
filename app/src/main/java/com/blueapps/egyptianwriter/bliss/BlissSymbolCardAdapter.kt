package com.blueapps.egyptianwriter.bliss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BlissSymbolCardAdapter — Blocco C (modalità CAA) + E-01 (rendering SVG)
 *
 * Mostra ogni [BlissSymbol] come una card grande con:
 *  - Simbolo BCI-AV SVG reale (E-01) — caricato in modo asincrono via
 *    [BlissSignProvider.getDrawableAsync] su Dispatchers.IO, visualizzato
 *    in [ImageView] con [android.view.View.LAYER_TYPE_SOFTWARE] per
 *    compatibilità PictureDrawable su tutti i livelli API.
 *  - ProgressBar visibile durante il caricamento, nascosta al completamento.
 *  - gloss (nome canonico BCI-AV) — testo grande e leggibile, centrato.
 *  - parola sorgente dell'utente — sotto, in secondario.
 *  - tipo di match (EXACT / LEMMA / …) — badge colorato.
 *
 * ## Prevenzione late-binding su card riciclate
 * Ogni [CardViewHolder] tiene un riferimento al [Job] di caricamento SVG
 * attivo. [onViewRecycled] cancella il job prima che la card venga riusata
 * da RecyclerView, evitando che un drawable «vecchio» sovrascriva quello
 * della nuova posizione.
 *
 * ## Utilizzo
 * ```kotlin
 * val adapter = BlissSymbolCardAdapter(
 *     signProvider  = blissSignProvider,   // iniettato dal Fragment/ViewModel
 *     adapterScope  = viewLifecycleOwner.lifecycleScope,
 *     onCardFocused = { pos, sym -> /* TTS, highlight, ecc. */ }
 * )
 * recyclerView.adapter = adapter
 * ```
 */
class BlissSymbolCardAdapter(
    private val signProvider: BlissSignProvider,
    private val adapterScope: CoroutineScope,
    private val onCardFocused: (position: Int, symbol: BlissSymbol) -> Unit = { _, _ -> }
) : ListAdapter<BlissSymbol, BlissSymbolCardAdapter.CardViewHolder>(DIFF) {

    // ── ViewHolder ───────────────────────────────────────────────────────────

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivSymbol:     ImageView  = itemView.findViewById(R.id.card_symbol_svg_image)
        private val pbLoading:    ProgressBar = itemView.findViewById(R.id.card_symbol_svg_progress)
        private val tvGloss:      TextView    = itemView.findViewById(R.id.card_gloss)
        private val tvSourceWord: TextView    = itemView.findViewById(R.id.card_source_word)
        private val tvMatchBadge: TextView    = itemView.findViewById(R.id.card_match_badge)
        private val tvPosition:   TextView    = itemView.findViewById(R.id.card_position)

        /** Job di caricamento SVG in volo. Cancellato in onViewRecycled. */
        var loadJob: Job? = null

        fun bind(symbol: BlissSymbol, position: Int, total: Int) {
            // ── testi e badge ──────────────────────────────────────────────
            tvGloss.text      = symbol.gloss
            tvSourceWord.text = symbol.sourceWord.ifBlank { "—" }
            tvMatchBadge.text = matchLabel(symbol.matchType)
            tvMatchBadge.setBackgroundColor(matchColor(symbol.matchType))
            tvPosition.text   = "${position + 1} / $total"

            // ── accessibility ──────────────────────────────────────────────
            ivSymbol.contentDescription = symbol.gloss
            itemView.contentDescription = buildString {
                append(symbol.gloss)
                if (symbol.sourceWord.isNotBlank()) append(", parola: ${symbol.sourceWord}")
                append(", ${position + 1} di $total")
            }
            itemView.setOnClickListener { onCardFocused(position, symbol) }

            // ── E-01: caricamento SVG asincrono ────────────────────────────
            // Azzera lo stato visivo prima di ogni bind, indipendentemente
            // da quale card stia occupando questo ViewHolder.
            ivSymbol.setImageDrawable(null)
            pbLoading.visibility = View.VISIBLE

            val code = "B${symbol.bciAvId}"
            loadJob = adapterScope.launch {
                val drawable = withContext(Dispatchers.IO) {
                    signProvider.getDrawableAsync(code, 180f * itemView.resources.displayMetrics.density)
                }
                // Siamo sul Main thread qui (launch default dispatcher = Main)
                // Verifica che la card non sia stata riciclata nel frattempo
                if (tvGloss.text == symbol.gloss) {      // guard cheapo ma sufficiente
                    ivSymbol.setImageDrawable(drawable)
                    pbLoading.visibility = View.GONE
                }
            }
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
        // Cancella eventuale job in volo prima di riutilizzare il ViewHolder
        holder.loadJob?.cancel()
        holder.bind(getItem(position), position, itemCount)
    }

    /**
     * Chiamato da RecyclerView prima di riciclare una view.
     * Cancella il job SVG pendente per evitare late-binding.
     */
    override fun onViewRecycled(holder: CardViewHolder) {
        super.onViewRecycled(holder)
        holder.loadJob?.cancel()
        holder.loadJob = null
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

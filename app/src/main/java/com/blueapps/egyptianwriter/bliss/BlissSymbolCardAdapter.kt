package com.blueapps.egyptianwriter.bliss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
 * BlissSymbolCardAdapter — Blocco C (modalità CAA) + E-01 (rendering SVG) + E-03 (TalkBack)
 *
 * ## E-03 — TalkBack: contentDescription dinamica per l'immagine SVG
 *
 * L'[ImageView] della card (`card_symbol_svg_image`) passa per tre stati TalkBack:
 *
 * | Stato               | contentDescription               | Quando                        |
 * |---------------------|----------------------------------|-------------------------------|
 * | In caricamento      | `bliss_caa_svg_loading`          | Prima del launch della coroutine |
 * | Simbolo disponibile | `bliss_caa_svg_ready` (%s=gloss) | drawable != null && !Placeholder |
 * | Simbolo mancante    | `bliss_caa_svg_missing` (%s=id)  | drawable null o Placeholder   |
 *
 * **Ordine di lettura** — `setTraversalAfter` nel `init` del ViewHolder garantisce
 * che TalkBack annunci prima l'immagine SVG, poi il gloss testuale.
 *
 * **ProgressBar e badge** — hanno `importantForAccessibility="no"` nel layout:
 * TalkBack li salta completamente.
 *
 * **Card intera** — usa la stringa esistente `bliss_caa_card_cd`
 * (`"%1$s, parola: %2$s, simbolo %3$d di %4$d"`) come contentDescription
 * dell'`itemView`, letta quando l'utente seleziona la card nel suo insieme.
 *
 * ## E-01 — Rendering SVG asincrono
 * Vedi [BlissSignProvider.getDrawableAsync]. Job cancellato in [onViewRecycled].
 *
 * ## Utilizzo
 * ```kotlin
 * val adapter = BlissSymbolCardAdapter(
 *     signProvider  = blissSignProvider,
 *     adapterScope  = viewLifecycleOwner.lifecycleScope,
 *     onCardFocused = { pos, sym -> /* TTS, highlight, ecc. */ }
 * )
 * ```
 */
class BlissSymbolCardAdapter(
    private val signProvider: BlissSignProvider,
    private val adapterScope: CoroutineScope,
    private val onCardFocused: (position: Int, symbol: BlissSymbol) -> Unit = { _, _ -> }
) : ListAdapter<BlissSymbol, BlissSymbolCardAdapter.CardViewHolder>(DIFF) {

    // ── ViewHolder ───────────────────────────────────────────────────────────

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // E-03: ivSymbol e tvGloss non sono private per permettere a
        // setTraversalAfter(ivSymbol) nell'init di compilare correttamente.
        val ivSymbol:     ImageView   = itemView.findViewById(R.id.card_symbol_svg_image)
        val tvGloss:      TextView    = itemView.findViewById(R.id.card_gloss)
        private val pbLoading:    ProgressBar = itemView.findViewById(R.id.card_symbol_svg_progress)
        private val tvSourceWord: TextView    = itemView.findViewById(R.id.card_source_word)
        private val tvMatchBadge: TextView    = itemView.findViewById(R.id.card_match_badge)
        private val tvPosition:   TextView    = itemView.findViewById(R.id.card_position)

        /** Job di caricamento SVG in volo. Cancellato in [onViewRecycled]. */
        var loadJob: Job? = null

        init {
            // E-03: forza l'ordine di navigazione TalkBack: immagine → gloss → parola.
            // "tvGloss viene letto DOPO ivSymbol" = traversalAfter(ivSymbol) su tvGloss.
            ViewCompat.setAccessibilityDelegate(tvGloss,
                object : androidx.core.view.AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.setTraversalAfter(ivSymbol)
                    }
                }
            )
        }

        fun bind(symbol: BlissSymbol, position: Int, total: Int) {
            // ── testi e badge ──────────────────────────────────────────────
            tvGloss.text      = symbol.gloss
            tvSourceWord.text = symbol.sourceWord.ifBlank { "—" }
            tvMatchBadge.text = matchLabel(symbol.matchType)
            tvMatchBadge.setBackgroundColor(matchColor(symbol.matchType))
            tvPosition.text   = "${position + 1} / $total"

            // ── E-03: contentDescription card intera ───────────────────────
            // bliss_caa_card_cd = "%1$s, parola: %2$s, simbolo %3$d di %4$d"
            // Letta da TalkBack quando l'utente seleziona l'intera card.
            itemView.contentDescription = itemView.context.getString(
                R.string.bliss_caa_card_cd,
                symbol.gloss,
                symbol.sourceWord.ifBlank { "—" },
                position + 1,
                total
            )
            itemView.setOnClickListener { onCardFocused(position, symbol) }

            // ── E-01 + E-03: caricamento SVG asincrono con CD dinamica ─────
            // Stato iniziale: immagine vuota, spinner visibile.
            // CD = "Caricamento simbolo in corso" — annunciata da TalkBack
            // non appena la card riceve il focus, prima ancora che l'SVG sia pronto.
            ivSymbol.setImageDrawable(null)
            ivSymbol.contentDescription = itemView.context.getString(
                R.string.bliss_caa_svg_loading
            )
            pbLoading.visibility = View.VISIBLE

            val bciId = symbol.bciAvId
            val code  = "B$bciId"
            loadJob = adapterScope.launch {
                val drawable = withContext(Dispatchers.IO) {
                    signProvider.getDrawableAsync(
                        code,
                        180f * itemView.resources.displayMetrics.density
                    )
                }
                // Main thread — guard anti-late-binding
                if (tvGloss.text == symbol.gloss) {
                    ivSymbol.setImageDrawable(drawable)
                    pbLoading.visibility = View.GONE

                    // E-03: aggiorna CD in base all'esito del caricamento
                    ivSymbol.contentDescription = if (
                        drawable != null &&
                        drawable !is BlissSignProvider.PlaceholderDrawable
                    ) {
                        // SVG trovato e renderizzato: TalkBack annuncia il gloss
                        itemView.context.getString(
                            R.string.bliss_caa_svg_ready,
                            symbol.gloss
                        )
                    } else {
                        // File SVG mancante o errore di parse: TalkBack annuncia l'id BCI-AV
                        itemView.context.getString(
                            R.string.bliss_caa_svg_missing,
                            bciId.toString()
                        )
                    }
                }
            }
        }

        private fun matchLabel(mt: BlissSymbol.MatchType): String = when (mt) {
            BlissSymbol.MatchType.EXACT             -> "exact"
            BlissSymbol.MatchType.LEMMA             -> "lemma"
            BlissSymbol.MatchType.NGRAM             -> "frase"
            BlissSymbol.MatchType.FALLBACK_CATEGORY -> "categoria"
            BlissSymbol.MatchType.COMPOUND          -> "composto"
            BlissSymbol.MatchType.SEMANTIC          -> "semantico"
            BlissSymbol.MatchType.UNKNOWN           -> "sconosciuto"
            BlissSymbol.MatchType.FUNCTION_WORD     -> "funzione"
        }

        private fun matchColor(mt: BlissSymbol.MatchType): Int = when (mt) {
            BlissSymbol.MatchType.EXACT             -> 0xFFD0F0D0.toInt()
            BlissSymbol.MatchType.LEMMA             -> 0xFFD0E8FF.toInt()
            BlissSymbol.MatchType.NGRAM             -> 0xFFFFF3B0.toInt()
            BlissSymbol.MatchType.FALLBACK_CATEGORY -> 0xFFFFDDB0.toInt()
            BlissSymbol.MatchType.COMPOUND          -> 0xFFE8D5FF.toInt()
            BlissSymbol.MatchType.SEMANTIC          -> 0xFFD5EAFF.toInt()
            BlissSymbol.MatchType.UNKNOWN           -> 0xFFFFD0D0.toInt()
            BlissSymbol.MatchType.FUNCTION_WORD     -> 0xFFB0F0F0.toInt()
        }
    }

    // ── Adapter overrides ────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder =
        CardViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_symbol_card, parent, false)
        )

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
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

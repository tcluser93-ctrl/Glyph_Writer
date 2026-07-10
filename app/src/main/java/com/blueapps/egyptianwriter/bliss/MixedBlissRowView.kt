package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.chip.Chip

/**
 * MixedBlissRowView — contenitore orizzontale per output multi-token
 * con token classic (chip) e token structured (SVG) nella stessa riga.
 *
 * ## Problema risolto
 * `renderStructuredMultiToken()` nel Fragment appendeva chip BCI via
 * `applySymbols()` e SVG structured via `BlissRenderer.renderWithAttachments()`
 * sullo stesso `FlexboxLayout`, in due chiamate sequenziali separate.
 * Questo causava:
 *  - ordine token non garantito (race condition sulle coroutine SVG)
 *  - spacing incoerente tra chip e SVG (dimensioni e baseline diversi)
 *  - TalkBack che traversava chip e SVG come due sequenze indipendenti
 *
 * ## Design
 * - [MixedTokenSlot] è una sealed class con tre varianti:
 *   [MixedTokenSlot.ChipSlot] per token classic,
 *   [MixedTokenSlot.SvgSlot] per token structured già risolti,
 *   [MixedTokenSlot.PendingSlot] come placeholder durante risoluzione asincrona.
 * - [bind] pre-alloca tutti gli slot ordinati per indice in un'unica
 *   chiamata sincrona: l'ordine visivo è determinato dall'indice, non
 *   dall'ordine di completamento delle coroutine.
 * - [resolveSlot] aggiorna in-place un [MixedTokenSlot.PendingSlot] →
 *   [MixedTokenSlot.SvgSlot] quando la coroutine SVG termina.
 * - Un singolo [AccessibilityDelegateCompat] espone l'intera riga come
 *   una sequenza lineare di token, con un `contentDescription` unificato.
 *
 * ## Integrazione con BlissTranslateFragment
 * Il Fragment deve:
 * 1. Costruire la lista di [MixedTokenSlot] da `UiState.symbols` e
 *    `UiState.composedWords`.
 * 2. Chiamare `bind(slots)` una volta.
 * 3. Per ogni [MixedTokenSlot.SvgSlot], lanciare
 *    `BlissRenderer.renderWithAttachments()` passando il FrameLayout
 *    recuperato via `findViewWithTag("svg_slot_<sourceWord>")`.
 *
 * @see MixedTokenSlot
 * @see BlissRenderer
 * @see ComposedBlissWord
 */
class MixedBlissRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** Gap orizzontale tra slot in dp. */
    private val GAP_DP = 6

    private val slots = mutableListOf<MixedTokenSlot>()

    init {
        orientation = HORIZONTAL
        val gapPx = with(DpUtil) { GAP_DP.dpToPx(resources) }
        setPadding(gapPx, 0, gapPx, 0)
    }

    // ── API pubblica ──────────────────────────────────────────────────

    /**
     * Sostituisce l'intero contenuto con [tokenSlots], ordinati per [MixedTokenSlot.index].
     * Va chiamato una sola volta da `observeViewModel()` per ogni nuovo `UiState`.
     *
     * @param tokenSlots lista non-ordinata di slot; l'ordine visivo è determinato
     *   dall'indice di ciascun slot, non dall'ordine della lista.
     */
    fun bind(tokenSlots: List<MixedTokenSlot>) {
        slots.clear()
        slots.addAll(tokenSlots)
        removeAllViews()
        slots.sortedBy { it.index }.forEach { slot ->
            addView(buildSlotView(slot))
        }
        refreshAccessibility()
    }

    /**
     * Aggiorna un singolo slot [MixedTokenSlot.PendingSlot] → [MixedTokenSlot.SvgSlot]
     * quando la coroutine SVG ha completato la risoluzione dell'attachment.
     *
     * Se l'indice non corrisponde a nessuno slot registrato, la chiamata è no-op.
     *
     * @param index     indice del token (0-based, allineato a `UiState.symbols`)
     * @param composedWord risultato risolto da inserire
     */
    fun resolveSlot(index: Int, composedWord: ComposedBlissWord) {
        val pos = slots.indexOfFirst { it.index == index }
        if (pos < 0) return
        slots[pos] = MixedTokenSlot.SvgSlot(index, composedWord)
        if (pos < childCount) {
            val updated = buildSlotView(slots[pos])
            removeViewAt(pos)
            addView(updated, pos)
        }
        refreshAccessibility()
    }

    /**
     * Restituisce il [FrameLayout] container del token SVG identificato da [sourceWord],
     * oppure `null` se lo slot non è presente o non è un [MixedTokenSlot.SvgSlot].
     *
     * Il Fragment lo usa per passare il container a
     * `BlissRenderer.renderWithAttachments(container, composedWord)`.
     */
    fun svgContainerFor(sourceWord: String): FrameLayout? =
        findViewWithTag(svgTag(sourceWord))

    // ── Costruzione view ───────────────────────────────────────────────

    private fun buildSlotView(slot: MixedTokenSlot): android.view.View = when (slot) {
        is MixedTokenSlot.ChipSlot    -> buildChip(slot.symbol)
        is MixedTokenSlot.SvgSlot     -> buildSvgContainer(slot.composedWord)
        is MixedTokenSlot.PendingSlot -> buildPendingPlaceholder()
    }

    /**
     * Chip BCI-classic: rispecchia la logica di `renderChips()` nel Fragment
     * (testo label, non-checkable, nessun background custom — il colore del
     * MatchType è responsabilità del Fragment che può post-processare la view).
     */
    private fun buildChip(symbol: BlissSymbol): Chip {
        val gapPx = with(DpUtil) { 4.dpToPx(resources) }
        return Chip(context).apply {
            text              = symbol.displayLabel()
            textSize          = 11f
            isCheckable       = false
            contentDescription = symbol.displayLabel()
            layoutParams = MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginEnd    = gapPx
                it.bottomMargin = gapPx
            }
        }
    }

    /**
     * FrameLayout container per il rendering SVG asincrono.
     * Il tag `svg_slot_<sourceWord>` consente al Fragment di recuperare
     * il container via [svgContainerFor] o `findViewWithTag`.
     *
     * La dimensione minima garantisce che il placeholder non collassi a 0
     * prima che il renderer abbia prodotto il drawable.
     */
    private fun buildSvgContainer(composedWord: ComposedBlissWord): FrameLayout {
        val minSizePx = with(DpUtil) { 48.dpToPx(resources) }
        return FrameLayout(context).apply {
            tag = svgTag(composedWord.sourceWord)
            minimumWidth  = minSizePx
            minimumHeight = minSizePx
            contentDescription = composedWord.components
                .joinToString(separator = " ") { component: ResolvedBlissComponent ->
                    component.symbol.name
                }
            layoutParams = MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginEnd = with(DpUtil) { 4.dpToPx(resources) }
            }
        }
    }

    /**
     * Space placeholder per [MixedTokenSlot.PendingSlot]: mantiene la posizione
     * del token nel layout mentre la coroutine SVG è in esecuzione.
     * Dimensionato a 32×32 dp — sufficiente a evitare collasso, abbastanza piccolo
     * da non disturbare il layout definitivo.
     */
    private fun buildPendingPlaceholder(): Space {
        val sizePx = with(DpUtil) { 32.dpToPx(resources) }
        return Space(context).apply {
            minimumWidth  = sizePx
            minimumHeight = sizePx
        }
    }

    // ── Accessibilità ──────────────────────────────────────────────────

    /**
     * Aggiorna il delegate di accessibilità in modo che TalkBack
     * legga l'intera riga come una sequenza continua di token,
     * anziché traversare chip e SVG-container separatamente.
     *
     * La descrizione è calcolata sullo stato corrente di [slots]:
     * per i [MixedTokenSlot.PendingSlot] viene usata la stringa di sistema
     * `unknownName` come fallback temporaneo.
     */
    private fun refreshAccessibility() {
        val description = slots
            .sortedBy { it.index }
            .joinToString(separator = ", ") { slot ->
                when (slot) {
                    is MixedTokenSlot.ChipSlot    ->
                        slot.symbol.displayLabel() ?: ""
                    is MixedTokenSlot.SvgSlot     ->
                        slot.composedWord.components
                            .joinToString(" ") { component: ResolvedBlissComponent ->
                                component.symbol.name
                            }
                    is MixedTokenSlot.PendingSlot ->
                        context.getString(android.R.string.unknownName)
                }
            }

        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: android.view.View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.contentDescription = description
                }
            }
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun svgTag(sourceWord: String) = "svg_slot_$sourceWord"
}

// ── MixedTokenSlot — sealed hierarchy ────────────────────────────────────────

/**
 * Slot tipizzato per [MixedBlissRowView].
 *
 * Ogni slot ha un [index] che rappresenta la posizione 0-based del token
 * nell'array `UiState.symbols` / `UiState.composedWords`.
 * L'ordinamento per indice in [MixedBlissRowView.bind] garantisce che
 * l'ordine visivo corrisponda all'ordine linguistico della frase.
 *
 * @property index posizione del token (0-based)
 */
sealed class MixedTokenSlot(val index: Int) {

    /**
     * Token classic: viene reso come chip BCI-flat.
     * @property symbol simbolo Bliss da visualizzare
     */
    class ChipSlot(index: Int, val symbol: BlissSymbol) : MixedTokenSlot(index)

    /**
     * Token structured già risolto: viene reso come container SVG
     * tramite [BlissRenderer.renderWithAttachments].
     * @property composedWord parola Bliss composta da visualizzare
     */
    class SvgSlot(index: Int, val composedWord: ComposedBlissWord) : MixedTokenSlot(index)

    /**
     * Placeholder per token structured in attesa di risoluzione asincrona.
     * Viene sostituito da [SvgSlot] tramite [MixedBlissRowView.resolveSlot]
     * quando la coroutine SVG completa.
     */
    class PendingSlot(index: Int) : MixedTokenSlot(index)
}

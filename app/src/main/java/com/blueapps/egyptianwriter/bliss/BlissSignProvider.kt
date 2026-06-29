package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.util.LruCache
import android.util.Log
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.IOException

/**
 * BlissSignProvider — intercetta i codici segno con prefisso "B" (es. "B12335")
 * e restituisce il Drawable SVG BCI-AV corrispondente da assets/bliss/svg/{id}.svg.
 *
 * Integrazione con ThothView (SignProvider API di com.github.ThothDroid:SignProvider):
 *
 *   val blissProvider = BlissSignProvider(context)
 *   thothView.setSignProvider(blissProvider)
 *
 * Per ogni codice non-Bliss (es. geroglifici "A1", "G17"), il provider restituisce
 * null e ThothView usa il proprio renderer nativo.
 *
 * Asset richiesti: app/src/main/assets/bliss/svg/{bci_av_id}.svg
 * Fonte: https://www.blissymbolics.org / BCI-AV dataset (CC BY-SA)
 *
 * Dipendenza SVG: com.caverock:androidsvg:1.4  (aggiungere a build.gradle.kts)
 *
 * Strategia di errore:
 *   1. SVG trovato e parsato correttamente → PictureDrawable normale.
 *   2. File non trovato (FileNotFoundException / IOException) → PlaceholderDrawable
 *      con punto interrogativo e ID, loggato a WARN. La UI non si rompe.
 *   3. SVG malformato (SVGParseException) → PlaceholderDrawable, loggato a ERROR.
 *   4. Qualsiasi altra eccezione imprevista → null (ThothView usa fallback nativo).
 *   5. ID non numerico o vuoto dopo il prefisso "B" → null immediato senza I/O.
 *
 * Il PlaceholderDrawable viene messo in cache come qualsiasi Drawable valido,
 * così lo stesso ID fallito non genera I/O ripetuto a ogni frame.
 *
 * Per ricevere notifiche sugli asset mancanti implementare [MissingAssetListener].
 */
class BlissSignProvider(
    private val context: Context,
    cacheSize: Int = 256,
    private val missingAssetListener: MissingAssetListener? = null
) {

    // ── interfaccia callback ──────────────────────────────────────────────────

    /**
     * Listener opzionale per monitorare gli asset non trovati.
     * Utile in sviluppo per rilevare gap nel corpus SVG caricato.
     */
    fun interface MissingAssetListener {
        /** Chiamata una sola volta per [bciId] (grazie alla cache). */
        fun onMissing(bciId: String, reason: MissingReason)
    }

    enum class MissingReason {
        /** Il file SVG non esiste in assets/bliss/svg/. */
        FILE_NOT_FOUND,
        /** Il file esiste ma il contenuto SVG è malformato. */
        PARSE_ERROR,
        /** Errore I/O generico (disco, permessi). */
        IO_ERROR
    }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG            = "BlissSignProvider"
        private const val BLISS_PREFIX   = "B"
        private const val SVG_BASE_PATH  = "bliss/svg/"

        /** Regex: BCI-AV id è composto solo da cifre (4-6 digit). */
        private val VALID_BCI_ID = Regex("^\\d{4,6}$")
    }

    // ── cache ─────────────────────────────────────────────────────────────────

    /**
     * LRU cache: codice "B12335" → Drawable.
     * Contiene sia PictureDrawable validi sia PlaceholderDrawable per ID falliti,
     * evitando I/O ripetuto per lo stesso asset mancante.
     */
    private val cache = LruCache<String, Drawable>(cacheSize)

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Restituisce il Drawable per il codice dato.
     *
     * @param code  Codice del segno, es. "B12335" o geroglifico "A1".
     *              Restituisce null per codici non-Bliss (ThothView usa il renderer nativo).
     * @param size  Dimensione target in px (hint per SVG viewport).
     * @return      [PictureDrawable] se l'SVG è disponibile,
     *              [PlaceholderDrawable] se l'asset è mancante/corrotto,
     *              null se il codice non è Bliss o l'ID non è valido.
     */
    fun getDrawable(code: String, size: Float = 96f): Drawable? {
        if (!code.startsWith(BLISS_PREFIX)) return null

        val bciId = code.removePrefix(BLISS_PREFIX)

        // Rifiuta ID non numerici subito, senza toccare il filesystem
        if (!VALID_BCI_ID.matches(bciId)) {
            Log.w(TAG, "BCI-AV id non valido: '$bciId' (da code='$code')")
            return null
        }

        cache.get(code)?.let { return it }

        val drawable = loadDrawable(bciId, size)

        // Metti in cache anche i placeholder (evita I/O ripetuto)
        if (drawable != null) cache.put(code, drawable)

        return drawable
    }

    /** Svuota la cache (es. su onTrimMemory). */
    fun clearCache() = cache.evictAll()

    /** True se il codice appartiene al corpus Bliss. */
    fun isBlissCode(code: String): Boolean = code.startsWith(BLISS_PREFIX)

    /** Numero di entry attualmente in cache. */
    val cacheSize: Int get() = cache.size()

    // ── logica di caricamento ─────────────────────────────────────────────────

    private fun loadDrawable(bciId: String, size: Float): Drawable? {
        return try {
            val svg = openSvg(bciId)
            renderSvg(svg, size)

        } catch (e: java.io.FileNotFoundException) {
            // Caso più comune: il corpus SVG caricato non copre questo ID
            Log.w(TAG, "SVG non trovato: assets/${SVG_BASE_PATH}${bciId}.svg")
            missingAssetListener?.onMissing(bciId, MissingReason.FILE_NOT_FOUND)
            PlaceholderDrawable(bciId, size)

        } catch (e: SVGParseException) {
            // File presente ma SVG malformato
            Log.e(TAG, "SVG malformato per BCI-AV id=$bciId", e)
            missingAssetListener?.onMissing(bciId, MissingReason.PARSE_ERROR)
            PlaceholderDrawable(bciId, size)

        } catch (e: IOException) {
            // Errore I/O generico (stream troncato, permessi, ecc.)
            Log.e(TAG, "Errore I/O leggendo SVG per BCI-AV id=$bciId", e)
            missingAssetListener?.onMissing(bciId, MissingReason.IO_ERROR)
            PlaceholderDrawable(bciId, size)

        } catch (e: Exception) {
            // Qualsiasi altro errore imprevisto: non crashare, ma non produrre
            // un placeholder fuorviante — restituiamo null e lasciamo a ThothView
            Log.e(TAG, "Errore imprevisto per code=B$bciId", e)
            null
        }
    }

    private fun openSvg(bciId: String): SVG {
        val path = "$SVG_BASE_PATH$bciId.svg"
        // assets.open lancia FileNotFoundException (sottoclasse di IOException)
        // se il file non esiste — il catch sopra la intercetta specificamente.
        context.assets.open(path).use { stream ->
            return SVG.getFromInputStream(stream)
        }
    }

    private fun renderSvg(svg: SVG, size: Float): PictureDrawable {
        svg.documentWidth  = size
        svg.documentHeight = size
        return PictureDrawable(svg.renderToPicture(size.toInt(), size.toInt()))
    }

    // ── PlaceholderDrawable ───────────────────────────────────────────────────

    /**
     * Drawable di fallback mostrato quando l'SVG non è disponibile.
     *
     * Aspetto: rettangolo grigio chiaro con bordo tratteggiato, punto interrogativo
     * centrato e ID BCI-AV in piccolo in basso. Rende il gap visivamente ovvio
     * durante lo sviluppo senza crashare l'app.
     *
     *   ┌ ─ ─ ─ ─ ┐
     *      ?
     *   │  12335  │
     *   └ ─ ─ ─ ─ ┘
     */
    class PlaceholderDrawable(
        private val bciId: String,
        private val sizePx: Float
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEEEEE.toInt()
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }

        private val questionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.40f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF999999.toInt()
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.18f
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = sizePx
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()

            // Sfondo
            canvas.drawRect(b, bgPaint)
            canvas.drawRect(
                b.left + 2f, b.top + 2f,
                b.right - 2f, b.bottom - 2f,
                borderPaint
            )

            // Punto interrogativo
            val textBounds = Rect()
            questionPaint.getTextBounds("?", 0, 1, textBounds)
            canvas.drawText("?", cx, cy - textBounds.height() * 0.1f, questionPaint)

            // ID in basso
            canvas.drawText(bciId, cx, b.bottom - s * 0.08f, labelPaint)
        }

        override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            bgPaint.colorFilter = cf
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()  = sizePx.toInt()
        override fun getIntrinsicHeight() = sizePx.toInt()
    }
}

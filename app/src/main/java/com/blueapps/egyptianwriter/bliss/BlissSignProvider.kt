package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.util.LruCache
import android.util.Log
import com.caverock.androidsvg.SVG
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
 */
class BlissSignProvider(
    private val context: Context,
    cacheSize: Int = 256
) {

    companion object {
        private const val TAG = "BlissSignProvider"
        private const val BLISS_PREFIX = "B"
        private const val SVG_BASE_PATH = "bliss/svg/"
    }

    /** LRU cache: codice → Drawable (PictureDrawable da SVG) */
    private val cache = LruCache<String, Drawable>(cacheSize)

    /**
     * Restituisce il Drawable per il codice dato, oppure null se il codice
     * non appartiene al corpus Bliss (prefisso non-"B").
     *
     * @param code  Codice del segno, es. "B12335" o geroglifico "A1"
     * @param size  Dimensione target in pixel (hint per il rendering SVG)
     */
    fun getDrawable(code: String, size: Float = 96f): Drawable? {
        if (!code.startsWith(BLISS_PREFIX)) return null   // Non è un simbolo Bliss

        val bciId = code.removePrefix(BLISS_PREFIX)       // "12335"

        cache.get(code)?.let { return it }                // Cache hit

        return try {
            val svg = loadSvg(bciId)
            val drawable = renderSvg(svg, size)
            cache.put(code, drawable)
            drawable
        } catch (e: IOException) {
            Log.w(TAG, "SVG non trovato per BCI-AV id=$bciId: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Errore rendering SVG per $code", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Privati
    // -------------------------------------------------------------------------

    private fun loadSvg(bciId: String): SVG {
        val path = "$SVG_BASE_PATH$bciId.svg"
        context.assets.open(path).use { stream ->
            return SVG.getFromInputStream(stream)
        }
    }

    private fun renderSvg(svg: SVG, size: Float): PictureDrawable {
        svg.documentWidth  = size
        svg.documentHeight = size
        val picture = svg.renderToPicture(size.toInt(), size.toInt())
        return PictureDrawable(picture)
    }

    /** Svuota la cache (es. in caso di bassa memoria). */
    fun clearCache() = cache.evictAll()

    /** Controlla se il codice appartiene al corpus Bliss. */
    fun isBlissCode(code: String): Boolean = code.startsWith(BLISS_PREFIX)
}

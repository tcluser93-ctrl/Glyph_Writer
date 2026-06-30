package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.util.Log
import androidx.annotation.WorkerThread
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * BlissSignProvider — loads BCI-AV SVG assets and caches them in a byte-bounded LRU.
 *
 * ## Enterprise changes (F1-06 / F1-07 / F1-08)
 *
 * ### F1-06 — Byte-bounded LRU cache
 * The original cache used `LruCache<String, Drawable>(256)` with count-based eviction.
 * SVG files range from 2 KB to 80 KB; 256 entries could allocate 20 MB of heap.
 * The new cache is bounded to [maxCacheBytes] (default 8 MB) using a size-in-bytes
 * `LruCache` that measures each `PictureDrawable` via `picture.byteCount`.
 * PlaceholderDrawables are counted as 1 byte (negligible) to avoid distorting eviction.
 *
 * ### F1-07 — Thread-safe cache: Mutex per-key
 * The original implementation had a TOCTOU race on cache miss:
 *   thread A checks cache → miss → thread B checks cache → miss → both do I/O
 * Fixed with a `Mutex` guarding the (check + load + put) critical section.
 * Using `kotlinx.coroutines.sync.Mutex` instead of `@Synchronized` avoids blocking
 * a platform thread while coroutines suspend inside the lock.
 *
 * ### F1-08 — @WorkerThread enforcement
 * `getDrawableAsync()` (suspending, Dispatchers.IO-safe) is the primary API.
 * The legacy `getDrawable()` is retained but annotated `@WorkerThread` and will
 * throw `IllegalStateException` on the Main Thread in debug builds.
 *
 * ### Asset path
 * `assets/bliss/svg/{bciAvId}.svg`
 *
 * ### Dependencies
 * - `com.caverock:androidsvg:1.4`
 * - `org.jetbrains.kotlinx:kotlinx-coroutines-android`
 */
class BlissSignProvider(
    private val context: Context,
    maxCacheBytes: Int = DEFAULT_CACHE_BYTES,
    private val missingAssetListener: MissingAssetListener? = null
) {

    // ── interfaces ───────────────────────────────────────────────────────────

    fun interface MissingAssetListener {
        /** Called once per [bciId] (subsequent calls hit the cache). */
        fun onMissing(bciId: String, reason: MissingReason)
    }

    enum class MissingReason { FILE_NOT_FOUND, PARSE_ERROR, IO_ERROR }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG           = "BlissSignProvider"
        private const val BLISS_PREFIX  = "B"
        private const val SVG_BASE_PATH = "bliss/svg/"

        /** Default max cache size: 8 MB. Covers ~200 typical Bliss SVGs. */
        const val DEFAULT_CACHE_BYTES = 8 * 1024 * 1024

        /** Regex: BCI-AV id is 4-6 decimal digits. */
        private val VALID_BCI_ID = Regex("^\\d{4,6}$")
    }

    // ── byte-bounded LRU cache (F1-06) ────────────────────────────────────────

    private val cache = object : android.util.LruCache<String, Drawable>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Drawable): Int = when (value) {
            is PictureDrawable -> value.picture.byteCount.coerceAtLeast(1)
            else               -> 1   // PlaceholderDrawable — negligible
        }
    }

    /** Diagnostic: total bytes currently held in cache. */
    val cacheSizeBytes: Int get() = cache.size()

    /** Total I/O calls made (for instrumentation / debug overlay). */
    private val _ioCount = AtomicLong(0)
    val ioCount: Long get() = _ioCount.get()

    // ── per-key mutex map (F1-07) ─────────────────────────────────────────────

    // One Mutex per BCI-AV id prevents concurrent I/O for the same asset while
    // allowing different assets to load in parallel.
    private val mutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(key: String) = mutexMap.getOrPut(key) { Mutex() }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Suspending version — safe to call from any dispatcher.
     * The actual I/O happens on the calling coroutine's dispatcher;
     * call this from `withContext(Dispatchers.IO)` in your ViewModel/UseCase.
     *
     * @param code  Sign code, e.g. "B12335". Returns null for non-Bliss codes.
     * @param size  Render size hint in px.
     */
    suspend fun getDrawableAsync(code: String, size: Float = 96f): Drawable? {
        if (!code.startsWith(BLISS_PREFIX)) return null
        val bciId = code.removePrefix(BLISS_PREFIX)
        if (!VALID_BCI_ID.matches(bciId)) {
            Log.w(TAG, "Invalid BCI-AV id: '$bciId' (code='$code')")
            return null
        }
        // Fast path — no lock needed if already cached
        cache.get(code)?.let { return it }
        // Slow path — serialize concurrent loaders for the same key
        return mutexFor(code).withLock {
            cache.get(code) ?: run {
                val d = loadDrawable(bciId, size)
                if (d != null) cache.put(code, d)
                d
            }
        }
    }

    /**
     * Synchronous version — retained for legacy call sites.
     * Must NOT be called on the Main Thread in production.
     * In debug builds, calling from Main Thread throws [IllegalStateException].
     */
    @WorkerThread
    fun getDrawable(code: String, size: Float = 96f): Drawable? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                // In debug builds throw; in release log a warning.
                if (context.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    error("BlissSignProvider.getDrawable() called on Main Thread! Use getDrawableAsync().")
                } else {
                    Log.e(TAG, "getDrawable() on Main Thread — switch to getDrawableAsync()")
                }
            }
        }
        if (!code.startsWith(BLISS_PREFIX)) return null
        val bciId = code.removePrefix(BLISS_PREFIX)
        if (!VALID_BCI_ID.matches(bciId)) return null
        cache.get(code)?.let { return it }
        val d = loadDrawable(bciId, size)
        if (d != null) cache.put(code, d)
        return d
    }

    /** Release all cached drawables (call from onTrimMemory / onLowMemory). */
    fun clearCache() {
        cache.evictAll()
        mutexMap.clear()
    }

    /** @return true if the code belongs to the Bliss corpus. */
    fun isBlissCode(code: String): Boolean = code.startsWith(BLISS_PREFIX)

    // ── private load pipeline ─────────────────────────────────────────────────

    private fun loadDrawable(bciId: String, size: Float): Drawable? {
        _ioCount.incrementAndGet()
        return try {
            val svg = openSvg(bciId)
            renderSvg(svg, size)
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "SVG not found: $SVG_BASE_PATH${bciId}.svg")
            missingAssetListener?.onMissing(bciId, MissingReason.FILE_NOT_FOUND)
            PlaceholderDrawable(bciId, size)
        } catch (e: SVGParseException) {
            Log.e(TAG, "Malformed SVG for BCI-AV id=$bciId", e)
            missingAssetListener?.onMissing(bciId, MissingReason.PARSE_ERROR)
            PlaceholderDrawable(bciId, size)
        } catch (e: IOException) {
            Log.e(TAG, "I/O error reading SVG for BCI-AV id=$bciId", e)
            missingAssetListener?.onMissing(bciId, MissingReason.IO_ERROR)
            PlaceholderDrawable(bciId, size)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error for code=B$bciId", e)
            null
        }
    }

    private fun openSvg(bciId: String): SVG {
        val path = "$SVG_BASE_PATH$bciId.svg"
        context.assets.open(path).use { return SVG.getFromInputStream(it) }
    }

    private fun renderSvg(svg: SVG, size: Float): PictureDrawable {
        svg.documentWidth  = size
        svg.documentHeight = size
        return PictureDrawable(svg.renderToPicture(size.toInt(), size.toInt()))
    }

    // ── PlaceholderDrawable ───────────────────────────────────────────────────

    /**
     * Shown when an SVG asset is missing or corrupt.
     * Renders a dashed-border rectangle with "?" and BCI-AV id.
     * Counted as 1 byte in the LRU to avoid distorting eviction.
     */
    class PlaceholderDrawable(
        private val bciId: String,
        private val sizePx: Float
    ) : Drawable() {

        private val bgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEEEEE.toInt(); style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt(); style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect  = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        private val questionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt(); textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.40f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF999999.toInt(); textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.18f
        }

        override fun draw(canvas: Canvas) {
            val b  = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            canvas.drawRect(b, bgPaint)
            canvas.drawRect(b.left + 2f, b.top + 2f, b.right - 2f, b.bottom - 2f, borderPaint)
            val tb = Rect(); questionPaint.getTextBounds("?", 0, 1, tb)
            canvas.drawText("?", cx, cy - tb.height() * 0.1f, questionPaint)
            canvas.drawText(bciId, cx, b.bottom - sizePx * 0.08f, labelPaint)
        }

        override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { bgPaint.colorFilter = cf }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()  = sizePx.toInt()
        override fun getIntrinsicHeight() = sizePx.toInt()
    }
}

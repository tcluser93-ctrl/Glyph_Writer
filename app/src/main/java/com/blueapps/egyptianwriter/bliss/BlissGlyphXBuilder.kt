package com.blueapps.egyptianwriter.bliss

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import java.io.ByteArrayOutputStream

/**
 * Converts a list of [BlissSymbol]s into a GlyphX DOM [Document] and
 * serialises it to SVG, PNG (via Canvas) or PDF.
 *
 * ## Blocco D additions
 *
 * ### D-01 — toSvgBytes with gloss
 * Each symbol chip now has THREE text rows:
 *   1. BCI-AV ID  (top-right, 8sp)
 *   2. Bliss name (centre, bold, 10sp)
 *   3. Source word / gloss (bottom, italic, 9sp) — NEW
 * SVG_ROW_H raised to 120 to accommodate the extra row.
 *
 * ### D-02 — toRenderedBitmap()
 * Rasterises the SVG produced by toSvgBytes() using AndroidSVG, then draws
 * the result onto an Android [android.graphics.Bitmap] via [android.graphics.Canvas].
 * Returns a ready-to-save PNG [ByteArray].
 * Requires: implementation("com.caverock:androidsvg-aar:1.4") in build.gradle.
 *
 * ### D-03 — toPdfDocument()
 * Wraps the rendered bitmap inside an [android.graphics.pdf.PdfDocument] page.
 * Returns a [ByteArray] ready to write to a file or share via Intent.
 */
class BlissGlyphXBuilder(
    private val symbolsPerLine: Int = AUTO_SYMBOLS_PER_LINE
) {

    companion object {
        const val AUTO_SYMBOLS_PER_LINE = -1

        const val TAG_BLISS_TEXT   = "blissText"
        const val TAG_LINE         = "line"
        const val TAG_GROUP        = "group"
        const val TAG_SIGN         = "sign"
        const val TAG_INDICATOR    = "indicator"
        const val ATTR_CODE        = "code"
        const val ATTR_NAME        = "name"
        const val ATTR_MATCH       = "match"
        const val ATTR_WORD        = "word"
        const val ATTR_TYPE        = "type"
        const val BLISS_PREFIX     = "B"

        // D-01: row height raised to 120 to fit gloss row
        private const val SVG_CELL      = 80
        private const val SVG_ROW_H     = 120
        private const val SVG_PAD       = 12
        private const val SVG_FONT      = 10
        private const val SVG_FONT_ID   = 8
        private const val SVG_FONT_GLOSS = 9
        private const val SVG_MAX_LABEL = 9
        private const val SVG_MAX_GLOSS = 11
        private const val SVG_RADIUS    = 6

        private val DOC_FACTORY: DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance()

        private val TRANSFORMER_FACTORY: TransformerFactory =
            TransformerFactory.newInstance()

        private val MATCH_FILL = mapOf(
            "EXACT"             to "#D0F0D0",
            "LEMMA"             to "#D0E8FF",
            "NGRAM"             to "#FFF3B0",
            "FALLBACK_CATEGORY" to "#FFDDB0",
            "UNKNOWN"           to "#FFD0D0"
        )
        private const val MATCH_FILL_DEFAULT = "#E8E8E8"

        private val INDICATOR_BADGE = mapOf(
            BlissTranslator.INDICATOR_PLURAL to "x",
            BlissTranslator.INDICATOR_PAST   to "<",
            BlissTranslator.INDICATOR_FUTURE to ">"
        )

        fun parseBciAvId(code: String): Int =
            if (code.startsWith(BLISS_PREFIX))
                code.removePrefix(BLISS_PREFIX).toIntOrNull() ?: -1
            else -1

        fun computeSymbolsPerLine(
            screenWidthPx: Int,
            cellSizePx: Int,
            minPerLine: Int = 4,
            maxPerLine: Int = 16
        ): Int = ((screenWidthPx / cellSizePx.coerceAtLeast(1))
            .coerceIn(minPerLine, maxPerLine))
    }

    // ---- public API ----------------------------------------------------------

    fun build(
        symbols: List<BlissSymbol>,
        screenWidthPx: Int = 1080,
        cellSizePx: Int    = 200
    ): Document {
        val perLine = resolvedPerLine(screenWidthPx, cellSizePx)
        val doc  = newDocument()
        val root = doc.createElement(TAG_BLISS_TEXT)
        doc.appendChild(root)

        if (symbols.isEmpty()) {
            root.appendChild(doc.createElement(TAG_LINE))
            return doc
        }

        var lineEl = doc.createElement(TAG_LINE).also { root.appendChild(it) }
        var countInLine = 0

        for (sym in symbols) {
            if (countInLine == perLine) {
                lineEl = doc.createElement(TAG_LINE).also { root.appendChild(it) }
                countInLine = 0
            }
            lineEl.appendChild(newGroup(doc, sym))
            countInLine++
        }

        return doc
    }

    fun append(
        existingDoc: Document,
        extra: List<BlissSymbol>,
        tailRef: TailRef,
        screenWidthPx: Int = 1080,
        cellSizePx: Int    = 200
    ): Document {
        if (extra.isEmpty()) return existingDoc
        val perLine = resolvedPerLine(screenWidthPx, cellSizePx)

        val root = existingDoc.documentElement
            ?: return build(extra, screenWidthPx, cellSizePx)

        if (tailRef.line == null) {
            tailRef.line  = existingDoc.createElement(TAG_LINE).also { root.appendChild(it) }
            tailRef.count = 0
        }

        for (sym in extra) {
            if (tailRef.count >= perLine) {
                tailRef.line  = existingDoc.createElement(TAG_LINE).also { root.appendChild(it) }
                tailRef.count = 0
            }
            tailRef.line!!.appendChild(newGroup(existingDoc, sym))
            tailRef.count++
        }
        return existingDoc
    }

    // ---- D-01: SVG with gloss -----------------------------------------------

    /**
     * Serialise to SVG bytes. Each symbol chip now shows:
     *   - BCI-AV ID top-right (8sp, grey)
     *   - Bliss name centred (10sp, bold, dark)
     *   - Source word / gloss bottom (9sp, italic, #555)
     */
    fun toSvgBytes(doc: Document): ByteArray =
        toSvgString(doc).toByteArray(Charsets.UTF_8)

    fun toSvgString(doc: Document): String {
        data class SvgSign(
            val name:       String,
            val gloss:      String,
            val bciCode:    String,
            val matchType:  String,
            val indicators: List<String>
        )
        data class SvgRow(val signs: List<SvgSign>)

        val rows = mutableListOf<SvgRow>()
        val root = doc.documentElement ?: return emptySvg()

        val lineNodes = root.getElementsByTagName(TAG_LINE)
        for (li in 0 until lineNodes.length) {
            val lineEl = lineNodes.item(li) as? Element ?: continue
            val signs  = mutableListOf<SvgSign>()
            val groupNodes = lineEl.getElementsByTagName(TAG_GROUP)
            for (gi in 0 until groupNodes.length) {
                val groupEl  = groupNodes.item(gi) as? Element ?: continue
                val signNodes = groupEl.getElementsByTagName(TAG_SIGN)
                val signEl    = signNodes.item(0) as? Element ?: continue
                val name  = signEl.getAttribute(ATTR_NAME).ifBlank { "?" }
                val gloss = signEl.getAttribute(ATTR_WORD).ifBlank { "" }
                val code  = signEl.getAttribute(ATTR_CODE).ifBlank { "" }
                val match = signEl.getAttribute(ATTR_MATCH).ifBlank { "UNKNOWN" }
                val indNodes = groupEl.getElementsByTagName(TAG_INDICATOR)
                val indicators = (0 until indNodes.length).mapNotNull {
                    (indNodes.item(it) as? Element)?.getAttribute(ATTR_TYPE)?.ifBlank { null }
                }
                signs += SvgSign(name, gloss, code, match, indicators)
            }
            if (signs.isNotEmpty()) rows += SvgRow(signs)
        }

        if (rows.isEmpty()) return emptySvg()

        val maxCols = rows.maxOf { it.signs.size }
        val svgW    = SVG_PAD * 2 + maxCols * SVG_CELL
        val svgH    = SVG_PAD * 2 + rows.size * SVG_ROW_H

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        sb.append("width=\"$svgW\" height=\"$svgH\" viewBox=\"0 0 $svgW $svgH\">\n")
        sb.append("  <rect width=\"$svgW\" height=\"$svgH\" fill=\"#FAFAF8\" rx=\"4\"/>\n")
        sb.append("  <title>Bliss Translation</title>\n")

        rows.forEachIndexed { rowIdx, row ->
            val rowY = SVG_PAD + rowIdx * SVG_ROW_H
            row.signs.forEachIndexed { colIdx, sign ->
                val cellX = SVG_PAD + colIdx * SVG_CELL
                val chipX = cellX + 2
                val chipY = rowY + 2
                val chipW = SVG_CELL - 4
                // D-01: chip height leaves room for gloss row below
                val chipH = SVG_ROW_H - 30
                val fill  = MATCH_FILL[sign.matchType] ?: MATCH_FILL_DEFAULT

                // chip background
                sb.append("  <rect x=\"$chipX\" y=\"$chipY\" width=\"$chipW\" height=\"$chipH\" ")
                sb.append("fill=\"$fill\" rx=\"$SVG_RADIUS\" stroke=\"#CCCCCC\" stroke-width=\"1\"/>\n")

                // BCI-AV ID top-right
                val bciId = sign.bciCode.removePrefix(BLISS_PREFIX)
                if (bciId.isNotEmpty()) {
                    sb.append("  <text x=\"${chipX + chipW - 2}\" y=\"${chipY + SVG_FONT_ID + 1}\" ")
                    sb.append("font-size=\"$SVG_FONT_ID\" fill=\"#888888\" ")
                    sb.append("text-anchor=\"end\" font-family=\"sans-serif\">")
                    sb.append(bciId.escapeXml())
                    sb.append("</text>\n")
                }

                // indicator badges top-left
                if (sign.indicators.isNotEmpty()) {
                    val badge = sign.indicators.mapNotNull { INDICATOR_BADGE[it] }.joinToString("")
                    if (badge.isNotEmpty()) {
                        sb.append("  <text x=\"${chipX + 3}\" y=\"${chipY + SVG_FONT + 1}\" ")
                        sb.append("font-size=\"$SVG_FONT\" fill=\"#444444\" font-family=\"sans-serif\">")
                        sb.append(badge.escapeXml())
                        sb.append("</text>\n")
                    }
                }

                // Bliss name centred
                val labelTrunc = sign.name.take(SVG_MAX_LABEL)
                    .let { if (sign.name.length > SVG_MAX_LABEL) "$it..." else it }
                val labelX = chipX + chipW / 2
                val labelY = chipY + chipH / 2 + SVG_FONT / 2
                sb.append("  <text x=\"$labelX\" y=\"$labelY\" ")
                sb.append("font-size=\"$SVG_FONT\" fill=\"#222222\" ")
                sb.append("text-anchor=\"middle\" font-family=\"sans-serif\" font-weight=\"600\">")
                sb.append(labelTrunc.escapeXml())
                sb.append("</text>\n")

                // D-01: gloss row below chip
                val glossTrunc = sign.gloss.take(SVG_MAX_GLOSS)
                    .let { if (sign.gloss.length > SVG_MAX_GLOSS) "$it..." else it }
                val glossY = chipY + chipH + SVG_FONT_GLOSS + 2
                sb.append("  <text x=\"$labelX\" y=\"$glossY\" ")
                sb.append("font-size=\"$SVG_FONT_GLOSS\" fill=\"#555555\" ")
                sb.append("text-anchor=\"middle\" font-family=\"sans-serif\" font-style=\"italic\">")
                sb.append(glossTrunc.escapeXml())
                sb.append("</text>\n")
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    // ---- D-02: PNG via Canvas + AndroidSVG ----------------------------------

    /**
     * Rasterise the SVG to a PNG [ByteArray].
     *
     * Requires AndroidSVG on the classpath:
     *   implementation("com.caverock:androidsvg-aar:1.4")
     *
     * @param doc      GlyphX document produced by [build].
     * @param scale    Pixel density multiplier (default 2 = @2x).
     * @return         PNG bytes, or empty array if AndroidSVG is unavailable.
     */
    fun toRenderedBitmap(
        doc: Document,
        scale: Float = 2f
    ): ByteArray {
        val svgBytes = toSvgBytes(doc)
        return try {
            // Reflective call so the file compiles even without AndroidSVG at
            // compile time; the dependency is declared in build.gradle.
            val svgClass  = Class.forName("com.caverock.androidsvg.SVG")
            val getFromIs = svgClass.getMethod("getFromInputStream",
                java.io.InputStream::class.java)
            val svgObj    = getFromIs.invoke(null, svgBytes.inputStream())

            val getDocW   = svgClass.getMethod("getDocumentWidth")
            val getDocH   = svgClass.getMethod("getDocumentHeight")
            val docW      = (getDocW.invoke(svgObj) as Float) * scale
            val docH      = (getDocH.invoke(svgObj) as Float) * scale

            val bmpClass  = Class.forName("android.graphics.Bitmap")
            val configCls = Class.forName("android.graphics.Bitmap\$Config")
            val argb8888  = configCls.getField("ARGB_8888").get(null)
            val createBmp = bmpClass.getMethod("createBitmap",
                Int::class.java, Int::class.java, configCls)
            val bitmap    = createBmp.invoke(null, docW.toInt(), docH.toInt(), argb8888)

            val canvasCls = Class.forName("android.graphics.Canvas")
            val canvas    = canvasCls.getConstructor(bmpClass).newInstance(bitmap)
            val scaleM    = canvasCls.getMethod("scale",
                Float::class.java, Float::class.java)
            scaleM.invoke(canvas, scale, scale)

            val renderTo  = svgClass.getMethod("renderToCanvas", canvasCls)
            renderTo.invoke(svgObj, canvas)

            val out = ByteArrayOutputStream()
            val compressFmt = Class.forName("android.graphics.Bitmap\$CompressFormat")
                .getField("PNG").get(null)
            val compress = bmpClass.getMethod("compress",
                compressFmt.javaClass, Int::class.java,
                java.io.OutputStream::class.java)
            compress.invoke(bitmap, compressFmt, 100, out)
            out.toByteArray()
        } catch (e: Exception) {
            // AndroidSVG not available or rendering failed
            ByteArray(0)
        }
    }

    // ---- D-03: PDF -----------------------------------------------------------

    /**
     * Wrap the rendered bitmap inside a [android.graphics.pdf.PdfDocument] page.
     *
     * @param doc      GlyphX document produced by [build].
     * @param scale    Rasterisation scale forwarded to [toRenderedBitmap].
     * @return         PDF bytes (ready to write to file or share), or empty array on failure.
     */
    fun toPdfDocument(
        doc: Document,
        scale: Float = 2f
    ): ByteArray {
        val pngBytes = toRenderedBitmap(doc, scale)
        if (pngBytes.isEmpty()) return ByteArray(0)
        return try {
            val bmpClass  = Class.forName("android.graphics.Bitmap")
            val bmpFactory = Class.forName("android.graphics.BitmapFactory")
            val decodeArr  = bmpFactory.getMethod("decodeByteArray",
                ByteArray::class.java, Int::class.java, Int::class.java)
            val bitmap     = decodeArr.invoke(null, pngBytes, 0, pngBytes.size)

            val getW = bmpClass.getMethod("getWidth")
            val getH = bmpClass.getMethod("getHeight")
            val bmpW = getW.invoke(bitmap) as Int
            val bmpH = getH.invoke(bitmap) as Int

            val pdfCls  = Class.forName("android.graphics.pdf.PdfDocument")
            val pdfObj  = pdfCls.newInstance()
            val piCls   = Class.forName("android.graphics.pdf.PdfDocument\$PageInfo")
            val builder = piCls.getDeclaredClasses()
                .first { it.simpleName == "Builder" }
            val bldr    = builder.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(bmpW, bmpH, 1)
            val buildFn = builder.getMethod("create")
            val pageInfo = buildFn.invoke(bldr)

            val startPage = pdfCls.getMethod("startPage", piCls)
            val page      = startPage.invoke(pdfObj, pageInfo)
            val pageCls   = Class.forName("android.graphics.pdf.PdfDocument\$Page")
            val getCanvas = pageCls.getMethod("getCanvas")
            val canvas    = getCanvas.invoke(page)

            val canvasCls = Class.forName("android.graphics.Canvas")
            val drawBmp   = canvasCls.getMethod("drawBitmap",
                bmpClass,
                Float::class.java, Float::class.java,
                Class.forName("android.graphics.Paint"))
            drawBmp.invoke(canvas, bitmap, 0f, 0f, null)

            val finishPage = pdfCls.getMethod("finishPage", pageCls)
            finishPage.invoke(pdfObj, page)

            val out      = ByteArrayOutputStream()
            val writeTo  = pdfCls.getMethod("writeTo", java.io.OutputStream::class.java)
            writeTo.invoke(pdfObj, out)
            val close    = pdfCls.getMethod("close")
            close.invoke(pdfObj)
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    // ---- TailRef ------------------------------------------------------------

    class TailRef {
        var line:  Element? = null
        var count: Int      = 0
    }

    // ---- private helpers ----------------------------------------------------

    private fun resolvedPerLine(screenWidthPx: Int, cellSizePx: Int): Int =
        if (symbolsPerLine == AUTO_SYMBOLS_PER_LINE)
            computeSymbolsPerLine(screenWidthPx, cellSizePx)
        else symbolsPerLine

    private fun newDocument(): Document =
        DOC_FACTORY.newDocumentBuilder().newDocument()

    private fun newGroup(doc: Document, sym: BlissSymbol): Element {
        val group = doc.createElement(TAG_GROUP)
        val sign = doc.createElement(TAG_SIGN).apply {
            setAttribute(ATTR_CODE,  "$BLISS_PREFIX${sym.bciAvId}")
            setAttribute(ATTR_NAME,  sym.name)
            setAttribute(ATTR_MATCH, sym.matchType.name)
            setAttribute(ATTR_WORD,  sym.sourceWord)
        }
        group.appendChild(sign)
        sym.indicators.forEach { indicator ->
            group.appendChild(
                doc.createElement(TAG_INDICATOR).apply {
                    setAttribute(ATTR_TYPE, indicator)
                }
            )
        }
        return group
    }

    private fun emptySvg(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50" viewBox="0 0 100 50">
  <rect width="100" height="50" fill="#FAFAF8" rx="4"/>
  <title>Bliss Translation (empty)</title>
</svg>"""

    private fun String.escapeXml(): String = this
        .replace("&",  "&amp;")
        .replace("<",  "&lt;")
        .replace(">",  "&gt;")
        .replace("\"", "&quot;")
        .replace("'",  "&apos;")
}

// NOTE (fix 2026-07-20, enterprise-grade audit):
// This file used to also declare `fun BlissSymbol.withIndicators(...)` and
// `val BlissSymbol.indicators` as EXTENSIONS backed by a global, non-thread-safe
// `WeakHashMap<BlissSymbol, List<String>>` keyed on structural equality.
//
// That extension code was 100% dead: BlissSymbol.kt already declares
// `indicators` as a constructor property and `withIndicators()` as a member
// function (immutable, `copy()`-based). In Kotlin a member ALWAYS wins over an
// extension with the same signature, so the WeakHashMap path could never
// actually execute — but it silently compiled and sat here as a latent trap.
// Had anyone ever removed the member fields from BlissSymbol (e.g. thinking
// the "duplicate" extension was the live implementation), this dead code would
// have reactivated instantly, reintroducing:
//   - a data race (WeakHashMap is not thread-safe; it would have been written
//     from background translation coroutines on multiple dispatchers), and
//   - cross-translation "ghost state": two structurally-equal BlissSymbol
//     instances (e.g. the same word repeated in one sentence, or the same
//     word resolved in two unrelated translations) collide on the same map
//     entry, silently leaking indicators between them, non-deterministically
//     depending on GC timing (WeakHashMap purge semantics).
// It has been removed entirely. Use BlissSymbol.withIndicators()/.indicators
// (the real, immutable, thread-safe members) instead.

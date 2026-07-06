package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import java.io.File

/**
 * Blocco D helper: write export bytes to cache and return a shareable [Uri].
 *
 * All I/O is performed on [Dispatchers.IO] via [withContext] so callers on the
 * main thread are safe to use suspend functions directly from a coroutine.
 *
 * Usage (from a Fragment/ViewModel):
 *
 *     val uri = BlissExportHelper.exportSvg(requireContext(), builder, glyphXDoc)
 *     startActivity(BlissExportHelper.shareIntent(uri, "image/svg+xml"))
 *
 * FileProvider authority: "${applicationId}.fileprovider"
 * Make sure res/xml/file_paths.xml includes the cache-path entry.
 */
object BlissExportHelper {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val DIR_EXPORT       = "bliss_export"
    private const val FILE_SVG         = "bliss_translation.svg"
    private const val FILE_PNG         = "bliss_translation.png"
    private const val FILE_PDF         = "bliss_translation.pdf"

    // ---- public API ---------------------------------------------------------

    /** Export as SVG. Returns a content:// [Uri] or null on failure. */
    suspend fun exportSvg(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        writeToCache(context, FILE_SVG, builder.toSvgBytes(doc))
    }

    /** Export as PNG (rasterised via AndroidSVG). Returns null if AndroidSVG is absent. */
    suspend fun exportPng(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = builder.toRenderedBitmap(doc)
        if (bytes.isEmpty()) null else writeToCache(context, FILE_PNG, bytes)
    }

    /** Export as PDF. Returns null on failure. */
    suspend fun exportPdf(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = builder.toPdfDocument(doc)
        if (bytes.isEmpty()) null else writeToCache(context, FILE_PDF, bytes)
    }

    /** Build a share [Intent] for [uri] with the given MIME type. */
    fun shareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ---- private helpers ----------------------------------------------------

    private fun writeToCache(context: Context, fileName: String, bytes: ByteArray): Uri? {
        return try {
            val dir  = File(context.cacheDir, DIR_EXPORT).also { it.mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}

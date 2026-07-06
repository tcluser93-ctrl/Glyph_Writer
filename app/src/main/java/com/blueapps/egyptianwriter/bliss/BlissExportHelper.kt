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
 * FileProvider authority: "com.blueapps.fileprovider" (vedere AndroidManifest.xml).
 * Cache path registered in res/xml/provider_paths.xml as bliss_export.
 */
object BlissExportHelper {

    private const val AUTHORITY    = "com.blueapps.fileprovider"
    private const val DIR_EXPORT   = "bliss_export"
    private const val FILE_SVG     = "bliss_translation.svg"
    private const val FILE_PNG     = "bliss_translation.png"
    private const val FILE_PDF     = "bliss_translation.pdf"

    suspend fun exportSvg(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        writeToCache(context, FILE_SVG, builder.toSvgBytes(doc))
    }

    suspend fun exportPng(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = builder.toRenderedBitmap(doc)
        if (bytes.isEmpty()) null else writeToCache(context, FILE_PNG, bytes)
    }

    suspend fun exportPdf(
        context: Context,
        builder: BlissGlyphXBuilder,
        doc: Document
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = builder.toPdfDocument(doc)
        if (bytes.isEmpty()) null else writeToCache(context, FILE_PDF, bytes)
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun writeToCache(context: Context, fileName: String, bytes: ByteArray): Uri? {
        return try {
            val dir  = File(context.cacheDir, DIR_EXPORT).also { it.mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (e: Exception) {
            null
        }
    }
}

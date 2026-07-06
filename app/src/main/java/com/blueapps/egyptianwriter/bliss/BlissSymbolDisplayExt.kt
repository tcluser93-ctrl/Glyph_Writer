package com.blueapps.egyptianwriter.bliss

import android.text.style.RelativeSizeSpan
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans

/**
 * Android-only display extensions for [BlissSymbol].
 *
 * Kept in a separate file so [BlissSymbol] itself has no Android imports
 * and can be compiled by JVM unit tests without an Android classpath.
 *
 * ## F1-11 — SpannableString label
 * [displayLabel] returns a [CharSequence] backed by SpannableString so the
 * BCI-AV id line renders at 70% size in any Android TextView context.
 */

/**
 * Short label shown on UI chips as a [CharSequence] (SpannableString).
 *
 * Line 1: BCI-AV id at 70% text size  (e.g. "#12335")
 * Line 2: the source word typed by the user (e.g. "voglio"), truncated to [nameMax].
 *         Falls back to the English BCI canonical name only when sourceWord is blank
 *         (e.g. for programmatically-inserted symbols like UNKNOWN or BLANK).
 *
 * Works in any Android TextView — no `\n` rendering issues (F1-11).
 *
 * @param nameMax  Maximum characters for the label line before truncation (default 14).
 */
fun BlissSymbol.displayLabel(nameMax: Int = 14): CharSequence {
    val label = if (sourceWord.isNotBlank()) sourceWord else name
    val truncated = if (label.length <= nameMax) label else label.take(nameMax - 1) + "\u2026"
    return buildSpannedString {
        inSpans(RelativeSizeSpan(0.7f)) { append("#$bciAvId") }
        append("\n")
        append(truncated)
    }
}

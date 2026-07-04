package com.blueapps.egyptianwriter.bliss

import android.text.SpannableStringBuilder
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
 * Line 2: truncated name at 100%       (e.g. "camminare")
 *
 * Works in any Android TextView — no `\n` rendering issues (F1-11).
 *
 * @param nameMax  Maximum characters for the name line before truncation (default 14).
 */
fun BlissSymbol.displayLabel(nameMax: Int = 14): CharSequence =
    buildSpannedString {
        inSpans(RelativeSizeSpan(0.7f)) { append("#$bciAvId") }
        append("\n")
        append(gloss(nameMax))
    }

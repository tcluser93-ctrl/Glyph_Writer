package com.blueapps.egyptianwriter

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

/**
 * AppPreferences — wrapper singleton su SharedPreferences per le opzioni utente.
 *
 * Chiavi gestite:
 *  - [KEY_DARK_THEME]          (E-05): forza il tema dark indipendentemente dal sistema
 *  - [KEY_HAPTIC_CAA]          (E-06): abilita il feedback aptico sui pulsanti CAA
 *  - [KEY_BLISS_CARDS_PER_ROW] (C-05): numero di card Bliss per riga nella vista CARDS (2–8)
 *  - [KEY_BLISS_LANG]          (audit EG, 2026-07-22): lingua di traduzione Bliss scelta
 *    dall'utente, indipendente dalla lingua di sistema del dispositivo
 */
object AppPreferences {

    private const val PREFS_NAME   = "glyph_writer_prefs"
    const val KEY_DARK_THEME           = "pref_dark_theme"
    const val KEY_HAPTIC_CAA           = "pref_haptic_caa"
    const val KEY_BLISS_CARDS_PER_ROW  = "bliss_cards_per_row"
    const val KEY_BLISS_LANG           = "bliss_lang"

    private const val DEFAULT_BLISS_CARDS_PER_ROW = 4
    private const val MIN_BLISS_CARDS_PER_ROW     = 2
    private const val MAX_BLISS_CARDS_PER_ROW     = 8

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Dark theme ────────────────────────────────────────────────────────────

    /** true = modalità dark forzata; false = segui il sistema. */
    fun isDarkTheme(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DARK_THEME, false)

    fun setDarkTheme(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_DARK_THEME, enabled) }

    // ── Haptic CAA ────────────────────────────────────────────────────────────

    /** true = vibrazione breve al tap su Prev/Next in modalità CAA. */
    fun isHapticCaa(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HAPTIC_CAA, true)

    fun setHapticCaa(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_HAPTIC_CAA, enabled) }

    // ── Bliss cards per row (C-05) ────────────────────────────────────────────

    /**
     * Numero di card Bliss per riga nella vista CARDS.
     * Valore garantito nel range [MIN_BLISS_CARDS_PER_ROW, MAX_BLISS_CARDS_PER_ROW]
     * tramite [coerceIn], così eventuali valori corrotti non causano crash nel GridLayoutManager.
     */
    fun getBlissCardsPerRow(ctx: Context): Int =
        prefs(ctx)
            .getInt(KEY_BLISS_CARDS_PER_ROW, DEFAULT_BLISS_CARDS_PER_ROW)
            .coerceIn(MIN_BLISS_CARDS_PER_ROW, MAX_BLISS_CARDS_PER_ROW)

    fun setBlissCardsPerRow(ctx: Context, columns: Int) =
        prefs(ctx).edit {
            putInt(
                KEY_BLISS_CARDS_PER_ROW,
                columns.coerceIn(MIN_BLISS_CARDS_PER_ROW, MAX_BLISS_CARDS_PER_ROW)
            )
        }

    // ── Bliss translation language (audit EG, 2026-07-22) ──────────────────────

    /**
     * ## Fix (audit EG, 2026-07-22)
     * Before this, the Bliss translation target language was *always*
     * `Locale.getDefault().language` (the device's system locale) — there
     * was no user-facing way to translate into a different language than
     * the phone's own UI language (e.g. an Italian-language device used to
     * learn/practice English Bliss symbols). [MainActivity] read
     * `Locale.getDefault()` directly at two separate call sites with no
     * shared source of truth.
     *
     * Raw stored preference: the ISO-639-1 code the user picked in
     * Impostazioni, or `null` if unset/cleared ("segui lingua di sistema").
     * Most callers should use [resolveBlissLang] instead, which applies the
     * system-locale fallback — this raw accessor exists mainly for the
     * Settings UI itself, which needs to distinguish "unset" from "system
     * locale happens to be this same value" to pre-select the right
     * dropdown entry.
     */
    fun getBlissLang(ctx: Context): String? =
        prefs(ctx).getString(KEY_BLISS_LANG, null)

    /** `null` clears the preference — resets to "follow system locale". */
    fun setBlissLang(ctx: Context, lang: String?) =
        prefs(ctx).edit {
            if (lang == null) remove(KEY_BLISS_LANG) else putString(KEY_BLISS_LANG, lang)
        }

    /**
     * The Bliss translation language actually in effect: the user's
     * explicit choice ([getBlissLang]) if set, otherwise the device's
     * system locale — preserving the app's original default behaviour.
     * [BlissLookup.normaliseLang] applies its own further fallback (to
     * Italian) if this returned code isn't one of the 8 supported
     * languages, so no validation happens here.
     */
    fun resolveBlissLang(ctx: Context): String =
        getBlissLang(ctx) ?: Locale.getDefault().language.take(2)
}

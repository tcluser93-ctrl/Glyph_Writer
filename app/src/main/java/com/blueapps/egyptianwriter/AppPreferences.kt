package com.blueapps.egyptianwriter

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * AppPreferences — wrapper singleton su SharedPreferences per le opzioni utente.
 *
 * Chiavi gestite:
 *  - [KEY_DARK_THEME]          (E-05): forza il tema dark indipendentemente dal sistema
 *  - [KEY_HAPTIC_CAA]          (E-06): abilita il feedback aptico sui pulsanti CAA
 *  - [KEY_BLISS_CARDS_PER_ROW] (C-05): numero di card Bliss per riga nella vista CARDS (2–8)
 */
object AppPreferences {

    private const val PREFS_NAME   = "glyph_writer_prefs"
    const val KEY_DARK_THEME           = "pref_dark_theme"
    const val KEY_HAPTIC_CAA           = "pref_haptic_caa"
    const val KEY_BLISS_CARDS_PER_ROW  = "bliss_cards_per_row"

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
}

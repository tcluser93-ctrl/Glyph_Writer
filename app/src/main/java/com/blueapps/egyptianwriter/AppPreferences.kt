package com.blueapps.egyptianwriter

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * AppPreferences — wrapper singleton su SharedPreferences per le opzioni utente.
 *
 * Chiavi gestite:
 *  - [KEY_DARK_THEME]  (E-05): forza il tema dark indipendentemente dal sistema
 *  - [KEY_HAPTIC_CAA]  (E-06): abilita il feedback aptico sui pulsanti CAA
 */
object AppPreferences {

    private const val PREFS_NAME   = "glyph_writer_prefs"
    const val KEY_DARK_THEME = "pref_dark_theme"
    const val KEY_HAPTIC_CAA = "pref_haptic_caa"

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
}

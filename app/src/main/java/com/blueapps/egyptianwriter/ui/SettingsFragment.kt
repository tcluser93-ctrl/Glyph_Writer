package com.blueapps.egyptianwriter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.blueapps.egyptianwriter.AppPreferences
import com.blueapps.egyptianwriter.R
import com.blueapps.egyptianwriter.bliss.BlissLookup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Locale

/**
 * SettingsFragment — impostazioni utente E-05, E-06, C-05 e lingua Bliss.
 *
 * Contiene:
 *  - `switch_dark_theme`          (E-05): forza il tema dark tramite [AppCompatDelegate]
 *    e persiste la scelta in [AppPreferences.KEY_DARK_THEME].
 *  - `switch_haptic_caa`          (E-06): abilita/disabilita il feedback aptico sui
 *    pulsanti CAA; il valore è letto da [BlissTranslateFragment] tramite
 *    [AppPreferences.KEY_HAPTIC_CAA] ad ogni tap.
 *  - `dropdown_bliss_lang`        (audit EG, 2026-07-22): lingua di traduzione Bliss,
 *    persistita in [AppPreferences.KEY_BLISS_LANG]. `null` = segui la lingua di
 *    sistema (comportamento originale dell'app). [BlissTranslateFragment.onResume]
 *    rilegge [AppPreferences.resolveBlissLang] e ricarica il motore se cambiata.
 *  - `slider_bliss_cards_per_row` (C-05): numero di card Bliss per riga nella vista CARDS
 *    (range 2–8, default 4); persiste in [AppPreferences.KEY_BLISS_CARDS_PER_ROW].
 *    [BlissTranslateFragment.onResume] rilegge il valore e aggiorna il GridLayoutManager.
 */
class SettingsFragment : Fragment() {

    /** Nome della lingua [code] nella lingua stessa (es. "it" → "Italiano"). */
    private fun displayLanguageName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()

        // ── E-05: Tema dark ─────────────────────────────────────────────
        val switchDark = view.findViewById<SwitchMaterial>(R.id.switch_dark_theme)
        switchDark.isChecked = AppPreferences.isDarkTheme(ctx)
        switchDark.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setDarkTheme(ctx, checked)
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else         AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }

        // ── E-06: Haptic feedback CAA ─────────────────────────────────
        val switchHaptic = view.findViewById<SwitchMaterial>(R.id.switch_haptic_caa)
        switchHaptic.isChecked = AppPreferences.isHapticCaa(ctx)
        switchHaptic.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setHapticCaa(ctx, checked)
        }

        // ── Lingua di traduzione Bliss (audit EG, 2026-07-22) ───────────
        // Prima non esisteva alcuna UI per scegliere la lingua di traduzione
        // indipendentemente dalla lingua di sistema del dispositivo — vedi
        // AppPreferences.resolveBlissLang KDoc per il dettaglio del fix.
        val dropdownLang = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_bliss_lang)

        // entries[0] = "segui sistema" (mappa a null); il resto è una entry
        // per lingua supportata, ordinata alfabeticamente per nome visualizzato.
        val sortedLangs = BlissLookup.SUPPORTED_LANGS.sortedBy { displayLanguageName(it) }
        val codesForEntries: List<String?> = listOf(null) + sortedLangs
        val entries = listOf(
            getString(
                R.string.settings_bliss_lang_system,
                displayLanguageName(Locale.getDefault().language.take(2))
            )
        ) + sortedLangs.map { displayLanguageName(it) }

        dropdownLang.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, entries))

        val selectedIndex = codesForEntries.indexOf(AppPreferences.getBlissLang(ctx)).coerceAtLeast(0)
        dropdownLang.setText(entries[selectedIndex], false)

        dropdownLang.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setBlissLang(ctx, codesForEntries[position])
        }

        // ── C-05: Card Bliss per riga ─────────────────────────────────
        val slider   = view.findViewById<Slider>(R.id.slider_bliss_cards_per_row)
        val tvCurrent = view.findViewById<TextView>(R.id.tv_bliss_cards_per_row_current)

        val savedColumns = AppPreferences.getBlissCardsPerRow(ctx)
        slider.value = savedColumns.toFloat()
        tvCurrent.text = savedColumns.toString()

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val columns = value.toInt()
                AppPreferences.setBlissCardsPerRow(ctx, columns)
                tvCurrent.text = columns.toString()
            }
        }
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}

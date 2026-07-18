package com.blueapps.egyptianwriter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.blueapps.egyptianwriter.AppPreferences
import com.blueapps.egyptianwriter.R
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * SettingsFragment — impostazioni utente E-05, E-06 e C-05.
 *
 * Contiene:
 *  - `switch_dark_theme`          (E-05): forza il tema dark tramite [AppCompatDelegate]
 *    e persiste la scelta in [AppPreferences.KEY_DARK_THEME].
 *  - `switch_haptic_caa`          (E-06): abilita/disabilita il feedback aptico sui
 *    pulsanti CAA; il valore è letto da [BlissTranslateFragment] tramite
 *    [AppPreferences.KEY_HAPTIC_CAA] ad ogni tap.
 *  - `slider_bliss_cards_per_row` (C-05): numero di card Bliss per riga nella vista CARDS
 *    (range 2–8, default 4); persiste in [AppPreferences.KEY_BLISS_CARDS_PER_ROW].
 *    [BlissTranslateFragment.onResume] rilegge il valore e aggiorna il GridLayoutManager.
 */
class SettingsFragment : Fragment() {

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

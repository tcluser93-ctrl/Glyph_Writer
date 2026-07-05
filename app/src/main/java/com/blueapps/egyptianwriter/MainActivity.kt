package com.blueapps.egyptianwriter

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.blueapps.egyptianwriter.bliss.BlissHistoryFragment
import com.blueapps.egyptianwriter.bliss.BlissTranslateFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import java.util.Locale

/**
 * MainActivity — Fase 6 Blocco A
 *
 * Ospita un [DrawerLayout] con:
 *  - [MaterialToolbar] con hamburger toggle
 *  - [NavigationView] laterale (nav_menu.xml)
 *  - FragmentContainerView come host dei fragment principali
 *
 * Destinazioni:
 *  - nav_translate → [BlissTranslateFragment]  (default al lancio)
 *  - nav_history   → [BlissHistoryFragment]
 *  - nav_settings  → stub (SettingsFragment placeholder)
 *
 * Il back-stack è gestito manualmente: il back button chiude il drawer
 * se aperto, altrimenti poppa il fragment stack (se non è il root).
 */
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView:      NavigationView
    private lateinit var toolbar:      MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        navView      = findViewById(R.id.nav_view)
        toolbar      = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)

        // Hamburger toggle
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.nav_drawer_open,
            R.string.nav_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)

        // Destinazione iniziale
        if (savedInstanceState == null) {
            val initLang = Locale.getDefault().language.take(2)
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_container,
                    BlissTranslateFragment.newInstance(initLang),
                    TAG_TRANSLATE
                )
                .commit()
            navView.setCheckedItem(R.id.nav_translate)
        }
    }

    // ── NavigationView listener ───────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_translate -> navigateTo(TAG_TRANSLATE) {
                val lang = Locale.getDefault().language.take(2)
                BlissTranslateFragment.newInstance(lang)
            }
            R.id.nav_history   -> navigateTo(TAG_HISTORY) {
                BlissHistoryFragment.newInstance()
            }
            R.id.nav_settings  -> navigateTo(TAG_SETTINGS) {
                // Placeholder: fragment vuoto fino alla Fase 8
                androidx.fragment.app.Fragment()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    /**
     * Naviga verso la destinazione [tag]. Se il fragment con quel tag
     * è già nel back-stack viene ripristinato (pop inclusive); altrimenti
     * viene aggiunto con addToBackStack.
     * Il fragment radice (nav_translate) non va mai nello stack.
     */
    private fun navigateTo(tag: String, factory: () -> androidx.fragment.app.Fragment) {
        val fm = supportFragmentManager

        if (tag == TAG_TRANSLATE) {
            // Torna sempre alla radice pulendo lo stack
            fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            if (fm.findFragmentByTag(TAG_TRANSLATE) == null) {
                fm.beginTransaction()
                    .replace(R.id.fragment_container, factory(), tag)
                    .commit()
            }
            return
        }

        if (fm.findFragmentByTag(tag) != null) {
            fm.popBackStack(tag, 0)
            return
        }

        fm.beginTransaction()
            .replace(R.id.fragment_container, factory(), tag)
            .addToBackStack(tag)
            .commit()
    }

    // ── Back button ───────────────────────────────────────────────────────

    @Deprecated("Using onBackPressedDispatcher via legacy override for API < 33 compat")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) ->
                drawerLayout.closeDrawer(GravityCompat.START)
            supportFragmentManager.backStackEntryCount > 0 ->
                supportFragmentManager.popBackStack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ── Constants ────────────────────────────────────────────────────────

    companion object {
        private const val TAG_TRANSLATE = "translate"
        private const val TAG_HISTORY   = "history"
        private const val TAG_SETTINGS  = "settings"
    }
}

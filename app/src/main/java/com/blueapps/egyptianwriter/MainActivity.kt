package com.blueapps.egyptianwriter

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.blueapps.egyptianwriter.bliss.BlissHistoryFragment
import com.blueapps.egyptianwriter.bliss.BlissTranslateFragment
import com.blueapps.egyptianwriter.bliss.BlissViewModel
import com.blueapps.egyptianwriter.ui.SettingsFragment
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
 *  - nav_translate  → [BlissTranslateFragment]  (default al lancio)
 *  - nav_history    → [BlissHistoryFragment]
 *  - nav_settings   → [SettingsFragment]  (E-05/E-06)
 *
 * ## E-05 — Tema dark
 * [applyDarkTheme] viene chiamato nel [onCreate] prima di [setContentView]:
 * se la preferenza è attiva forza [AppCompatDelegate.MODE_NIGHT_YES],
 * altrimenti lascia [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM].
 * Il cambio da [SettingsFragment] riscrea l’Activity automaticamente.
 */
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView:      NavigationView
    private lateinit var toolbar:      MaterialToolbar

    /**
     * Same ViewModelStore-scoped instance the Fragments obtain via
     * `activityViewModels()`. Only used here to reach
     * [BlissViewModel.signProvider] from [onTrimMemory] — see its KDoc.
     */
    private val blissViewModel: BlissViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // E-05: applica tema dark prima del layout
        applyDarkTheme()
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

        // ── Back handling ──────────────────────────────────────────────
        // Fix (audit EG, 2026-07-21): back handling used to be split between
        // this Activity's deprecated onBackPressed() override (invoked
        // directly by the framework on API < 33, bypassing the dispatcher
        // entirely) and a second, independent OnBackPressedCallback
        // registered by BlissTranslateFragment itself (the only path
        // actually exercised on API 33+, since predictive-back dispatches
        // through the OnBackPressedDispatcher rather than calling
        // Activity.onBackPressed()). The fragment's callback unconditionally
        // called parentFragmentManager.popBackStack() with no check of
        // whether there was anything to pop — on the root nav_translate
        // destination (empty back stack) this was a silent no-op, so on
        // API 33+ the system back gesture/button did *nothing* from the
        // root screen instead of exiting the app, while pre-33 devices
        // (routed through the Activity override) behaved correctly. A single
        // callback registered here, on the Activity's own dispatcher, is now
        // the one and only back-handling path on every API level, and
        // BlissTranslateFragment.setupToolbarBackHandling() has been removed.
        onBackPressedDispatcher.addCallback(this, backCallback)

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

    // ── E-05: applica preferenza dark theme ───────────────────────────

    private fun applyDarkTheme() {
        val mode = if (AppPreferences.isDarkTheme(this))
            AppCompatDelegate.MODE_NIGHT_YES
        else
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * ## Fix (enterprise-grade audit, 2026-07-20)
     * [com.blueapps.egyptianwriter.bliss.BlissSignProvider.clearCache] existed
     * (a well-designed byte-bounded LRU + per-key Mutex, up to 8 MB of
     * decoded SVG `PictureDrawable`s) but was never called from anywhere —
     * no `onTrimMemory`/`onLowMemory` hook existed in the app, so the cache
     * stayed fully resident regardless of system memory-pressure signals.
     * `blissViewModel` uses the lazy `by viewModels()` delegate: if no
     * Fragment has requested the ViewModel yet, first access happens here
     * and simply constructs it (cheap — no asset loading happens until
     * `setLang()` is called), so this is safe to call at any point in the
     * Activity's lifecycle.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            blissViewModel.signProvider.clearCache()
        }
    }

    // ── NavigationView listener ──────────────────────────────────────

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
                SettingsFragment.newInstance()
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

        // Fix (audit EG, 2026-07-21): navigateTo() is reachable from a
        // NavigationView item click, which the drawer's own close animation
        // can defer just long enough to land after onSaveInstanceState() —
        // e.g. tapping a drawer destination in the moment the user hits
        // Home/recents. FragmentManager throws IllegalStateException ("Can
        // not perform this action after onSaveInstanceState") from both
        // popBackStack() and commit() once state is saved. There is no
        // useful recovery here (the Activity is being backgrounded/
        // recreated regardless), so this is a no-op guard rather than a
        // deferred retry: the same destination is still selectable once the
        // Activity is interactive again.
        if (fm.isStateSaved) return

        if (tag == TAG_TRANSLATE) {
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

    // ── Back button ─────────────────────────────────────────────

    /**
     * Single, unified back-handling path for every API level. See the
     * "Back handling" comment in [onCreate] for why this replaced both the
     * deprecated `onBackPressed()` override and
     * `BlissTranslateFragment.setupToolbarBackHandling()`.
     *
     * When neither the drawer nor the fragment back stack claims the event,
     * this callback disables itself for one dispatch and re-invokes
     * [onBackPressedDispatcher] so the *next* callback/default handler
     * (finishing the Activity) runs, then re-enables itself.
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    drawerLayout.closeDrawer(GravityCompat.START)
                supportFragmentManager.backStackEntryCount > 0 ->
                    supportFragmentManager.popBackStack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    // ── Constants ─────────────────────────────────────────────

    companion object {
        private const val TAG_TRANSLATE = "translate"
        private const val TAG_HISTORY   = "history"
        private const val TAG_SETTINGS  = "settings"
    }
}

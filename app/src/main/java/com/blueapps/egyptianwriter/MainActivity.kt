package com.blueapps.egyptianwriter

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.blueapps.egyptianwriter.bliss.BlissTranslateFragment

/**
 * Stub launcher per l'opzione A.
 * Ospita direttamente [BlissTranslateFragment]; zero dipendenze da THOTH.
 * I package geroglifici (dashboard, editor, export, ecc.) verranno rimossi
 * nella pulizia B.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, BlissTranslateFragment())
                .commit()
        }
    }
}

// Root build file.
plugins {
    alias(libs.plugins.android.application) apply false
    // KSP2 + AGP 9: il plugin deve essere nel classpath root con apply false
    // prima che il modulo app/ lo applichi. Senza questa riga KSP non viene
    // trovato durante la resolution del plugin in app/build.gradle.kts.
    alias(libs.plugins.ksp) apply false
}

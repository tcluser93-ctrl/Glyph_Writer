// Root build file.
plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-android: nel classpath root con apply false, richiesto da AGP built-in Kotlin
    alias(libs.plugins.kotlin.android) apply false
    // KSP2 + AGP: deve essere nel classpath root prima che app/ lo applichi
    alias(libs.plugins.ksp) apply false
}

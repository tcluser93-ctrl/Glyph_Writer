plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Forza kotlin-stdlib e moduli correlati a 2.2.0.
// Una dipendenza transitiva dichiara kotlin-stdlib:2.2.21 (inesistente su Maven Central).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.2.0")
            because("Pin kotlin-stdlib to the actual published version; 2.2.21 does not exist")
        }
    }
}

android {
    namespace = "com.blueapps.egyptianwriter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blueapps.egyptianwriter"
        minSdk = 23
        targetSdk = 36
        versionCode = 10
        versionName = "17.02.2026@0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    buildFeatures {
        viewBinding = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Workaround: AGP 8.10.x + Gradle 9.4 — checkClasspath non riesce a serializzare
// compileVersionMap come input fingerprint (bug interno di AGP con il nuovo
// serializzatore di Gradle 9). Il task è solo un controllo di compatibilità
// opzionale e non influisce sulla correttezza del build o dei test.
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("Classpath")) {
        enabled = false
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register("testClasses")

dependencies {
    implementation(libs.signprovider)
    implementation(libs.documentfile)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.gridlayout)
    implementation(libs.commons.lang)
    implementation(libs.zoomlayout)
    implementation(libs.autobreaklinelayout)

    // THOTH rimosso: l'app usa ora solo MAAT + rendering Canvas custom (BlissRenderer).
    // implementation(libs.thoth)
    implementation(libs.maat)

    implementation(libs.glyphconverter)
    implementation(libs.expandable.layout)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.viewpager2)

    // ── NLP: Morfologik offline FSA lemmatizer ────────────────────────────────────────────
    implementation(libs.morfologik.stemming)

    // ── DB: Room FTS5 BCI lookup ────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── UI: FlexboxLayout ──────────────────────────────────────────────────────────
    implementation(libs.flexbox)

    // ── Test ──────────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

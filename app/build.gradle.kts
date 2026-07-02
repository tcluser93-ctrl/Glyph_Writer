plugins {
    alias(libs.plugins.android.application)
    // kotlin-android è già iniettato da AGP 9.x built-in Kotlin — NON ridichiararlo
    alias(libs.plugins.ksp)  // KSP per Room compiler (kapt non compatibile con AGP 9.x)
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

    //implementation(libs.thoth)
    implementation(libs.maat)
    implementation(files("../../THOTExpampleApp/thoth/build/outputs/aar/thoth-debug.aar"))

    implementation(libs.glyphconverter)
    implementation(libs.expandable.layout)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.viewpager2)

    // ── NLP: Morfologik offline FSA lemmatizer ─────────────────────────────
    implementation(libs.morfologik.stemming)

    // ── DB: Room FTS5 BCI lookup ───────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)  // KSP invece di kapt — richiesto da AGP 9.x

    // ── UI: FlexboxLayout ─────────────────────────────────────────────────
    implementation(libs.flexbox)

    // ── Test ──────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

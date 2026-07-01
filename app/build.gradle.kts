plugins {
    alias(libs.plugins.android.application)
    // kotlin-android è già incluso da AGP 9.x — non serve aggiungerlo manualmente
    id("kotlin-kapt")  // Room compiler (kapt)
}

android {
    namespace = "com.blueapps.egyptianwriter"
    compileSdk {
        version = release(36)
    }

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
    kotlinOptions {
        jvmTarget = "11"
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
    useJUnitPlatform()  // abilita JUnit 5 per tutti i test locali
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

    // ── NLP: Morfologik offline FSA lemmatizer (IT / EN / DE) ──────────────
    implementation(libs.morfologik.stemming)

    // ── DB: Room FTS5 BCI lookup ───────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // ── UI: FlexboxLayout (chip Bliss con wrap automatico) ──────────────
    implementation(libs.flexbox)

    // ── Test: JVM unit tests ───────────────────────────────────────
    testImplementation(libs.junit)                    // JUnit 4 (backward compat)
    testImplementation(libs.junit.jupiter)            // JUnit 5 API
    testRuntimeOnly(libs.junit.jupiter.engine)        // JUnit 5 engine
    testImplementation(libs.kotlinx.coroutines.test)  // TestCoroutineScope / runTest
    testImplementation(libs.arch.core.testing)        // InstantTaskExecutorRule (LiveData)
    testImplementation(libs.mockito.kotlin)           // mock<T>(), whenever()

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

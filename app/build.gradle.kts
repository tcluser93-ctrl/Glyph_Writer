plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
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
    buildFeatures {
        viewBinding = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
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

    // implementation(libs.thoth)
    implementation(libs.maat)

    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.fragment.ktx)
    implementation(libs.viewpager2)

    // SVG rendering (BlissRenderer + BlissSignProvider)
    implementation(libs.androidsvg)

    // Lifecycle KTX: viewModelScope, repeatOnLifecycle, activityViewModels
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.morfologik.stemming)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.flexbox)

    // ── unit test dependencies ────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    // junit-platform-launcher MUST be on the runtime classpath when using
    // useJUnitPlatform() with AGP 8+. Without it Gradle cannot bootstrap the
    // JUnit Platform and the test process fails immediately with
    // "Failed to load JUnit Platform".
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.mockito.kotlin)
    // Room annotation classes needed on the JVM test compile classpath because
    // BlissHistoryEntry is referenced transitively by BlissViewModel.UiState.
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

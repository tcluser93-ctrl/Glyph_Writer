pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("com\\.google\\.devtools.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // jitpack.io removed (2026-07-20): it was only needed for the 4
    // com.github.ThothDroid dependencies (SignProvider, THOTH, MAAT,
    // GlyphConverter), which turned out to be leftovers from a pre-Bliss
    // version of the app (see git history around "Migrated to
    // SignProvider-Library", 2026-02-17) — no longer referenced anywhere
    // in the current source. See gradle/libs.versions.toml: the
    // `expandable-layout` entry (com.github.cachapa) is also jitpack-hosted
    // but, like the 4 removed above, is not applied via implementation()
    // anywhere in app/build.gradle.kts, so it does not require this
    // repository either. If a future dependency actually needs jitpack.io
    // again, re-add `maven { url = uri("https://jitpack.io") }` here.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Egyptian Writer"
include(":app")

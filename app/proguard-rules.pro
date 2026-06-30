# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Morfologik offline FSA lemmatizer ────────────────────────────────────────
# The FSA binary format is loaded via reflection-style class loading internally.
-keep class morfologik.** { *; }
-keep class org.morfologik.** { *; }
-dontwarn morfologik.**
-dontwarn org.morfologik.**

# ── Room / SQLite FTS5 ────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep @androidx.room.Database class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}
# FTS4/FTS5 tokenizers are loaded by name at runtime
-keep class androidx.sqlite.db.** { *; }
-dontwarn androidx.room.**

# ── Bliss package — keep all public API for reflection-free access ────────────
-keep class com.blueapps.egyptianwriter.bliss.** { *; }

package com.blueapps.egyptianwriter.bliss

import java.lang.reflect.Field

/**
 * Shared test utilities for the Bliss test package.
 *
 * A file-level (non-private) typealias so every test file in this package
 * can use `MatchType` as shorthand for `BlissSymbol.MatchType` without
 * re-declaring it per-file (which would cause Redeclaration errors).
 */
internal typealias MatchType = BlissSymbol.MatchType

/**
 * Reflectively constructs a `BlissLookup.Tables` snapshot and injects it into
 * [lookup]'s private `_tables` field.
 *
 * ## Fix (audit EG, 2026-07-21)
 * Extracted from BlissLookupTest's original per-file `injectTables()` helper
 * so BlissSemanticComposerTest can seed the same real [BlissLookup] singleton
 * with synthetic data instead of the invalid `TestLookup : BlissLookup`
 * subclass it used to declare — [BlissLookup] has a private constructor and
 * is not `open`, so it was never actually implementable, and the interface
 * it assumed (`findByLemma`/`findBucket`/`hasSynset`/`BlissEntry`) does not
 * exist on the real class. Using the real singleton plus reflection into its
 * `Tables` snapshot (see [BlissLookup.Tables]'s KDoc) exercises
 * [BlissSemanticComposer] against the actual production lookup surface
 * ([BlissLookup.synsets], [BlissLookup.lookupSurface], etc.) instead of a
 * hand-rolled double that can silently drift from it again.
 */
internal fun injectBlissTables(
    lookup:        BlissLookup,
    names:         Map<Int, String> = emptyMap(),
    synsets:       Map<Int, Long>   = emptyMap(),
    lexicon:       Map<String, Int> = emptyMap(),
    lemmaIndex:    Map<String, Int> = emptyMap(),
    lemmaPoSIndex: Map<String, Int> = emptyMap(),
    ngramIndex:    Map<String, Int> = emptyMap()
) {
    val tablesClass = Class.forName("com.blueapps.egyptianwriter.bliss.BlissLookup\$Tables")
    val ctor = tablesClass.getDeclaredConstructor(
        Map::class.java, Map::class.java, Map::class.java,
        Map::class.java, Map::class.java, Map::class.java
    )
    ctor.isAccessible = true
    val tables = ctor.newInstance(names, synsets, lexicon, lemmaIndex, lemmaPoSIndex, ngramIndex)
    val f: Field = BlissLookup::class.java.getDeclaredField("_tables")
    f.isAccessible = true
    f.set(lookup, tables)
}

/**
 * Resets and clears [BlissLookup]'s process-wide singleton so each test
 * starts from a clean slate, regardless of what a previous test left behind.
 *
 * Kotlin compiles `companion object { private var INSTANCE }` as a *static*
 * field on the outer JVM class ([BlissLookup]), not on the `Companion` inner
 * class — reflection must target `BlissLookup::class.java` accordingly.
 */
internal fun resetBlissLookupSingleton() {
    val outerClass: Class<*> = BlissLookup::class.java
    val f: Field = outerClass.getDeclaredField("INSTANCE")
    f.isAccessible = true
    (f.get(null) as? BlissLookup)?.reset()
    f.set(null, null)
}

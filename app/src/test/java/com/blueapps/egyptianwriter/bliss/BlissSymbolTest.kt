package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BlissSymbol] data class, including its [withIndicators] /
 * [indicators] members (immutable, copy()-based).
 */
@DisplayName("BlissSymbol — data class and indicator extensions")
class BlissSymbolTest {

    // gloss is a computed property (= name), not a constructor param — omit it.
    private fun sym(id: Int = 1, name: String = "test") = BlissSymbol(
        bciAvId    = id,
        name       = name,
        matchType  = BlissSymbol.MatchType.EXACT,
        sourceWord = name
    )

    @Test
    @DisplayName("Default indicators list is empty")
    fun defaultIndicatorsEmpty() {
        assertTrue(sym().indicators.isEmpty())
    }

    @Test
    @DisplayName("withIndicators() stores and retrieves via the copy()-based member")
    fun withIndicatorsRoundTrip() {
        val s = sym().withIndicators(listOf("plural", "past"))
        assertEquals(listOf("plural", "past"), s.indicators)
    }

    @Test
    @DisplayName("Different instances do not share indicators")
    fun noIndicatorLeakBetweenInstances() {
        val s1 = sym(1, "walk").withIndicators(listOf("plural"))
        val s2 = sym(2, "run")
        assertTrue(s2.indicators.isEmpty(), "s2 should have no indicators")
        assertEquals(listOf("plural"), s1.indicators)
    }

    @Test
    @DisplayName("withIndicators() returns a NEW immutable copy, original unchanged")
    fun withIndicatorsReturnsNewCopyAndKeepsOriginalImmutable() {
        // BlissSymbol.withIndicators() is the real member (copy()-based, immutable).
        // A now-removed extension in BlissGlyphXBuilder.kt (backed by a global,
        // non-thread-safe WeakHashMap) was always shadowed by this member and
        // could never actually run — this test previously asserted that dead
        // extension's "same instance" semantics, which never matched the real
        // (correct) behaviour below.
        val s = sym()
        val modified = s.withIndicators(listOf("future"))
        assertNotSame(s, modified, "withIndicators() must return a different instance")
        assertTrue(s.indicators.isEmpty(), "original instance must remain unmodified")
        assertEquals(listOf("future"), modified.indicators)
    }
}

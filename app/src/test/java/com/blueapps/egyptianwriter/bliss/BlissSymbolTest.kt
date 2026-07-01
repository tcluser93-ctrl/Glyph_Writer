package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BlissSymbol] data class + [withIndicators] / [indicators] extensions.
 */
@DisplayName("BlissSymbol — data class and indicator extensions")
class BlissSymbolTest {

    private fun sym(id: Int = 1, name: String = "test") = BlissSymbol(
        bciAvId    = id,
        name       = name,
        matchType  = MatchType.EXACT,
        sourceWord = name,
        gloss      = name
    )

    @Test
    @DisplayName("Default indicators list is empty")
    fun defaultIndicatorsEmpty() {
        assertTrue(sym().indicators.isEmpty())
    }

    @Test
    @DisplayName("withIndicators() stores and retrieves via extension property")
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
    @DisplayName("withIndicators() returns same instance (fluent API)")
    fun withIndicatorsReturnsSelf() {
        val s = sym()
        assertSame(s, s.withIndicators(listOf("future")))
    }
}

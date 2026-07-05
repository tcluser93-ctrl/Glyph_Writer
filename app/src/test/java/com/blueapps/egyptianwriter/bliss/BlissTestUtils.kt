package com.blueapps.egyptianwriter.bliss

/**
 * Shared test utilities for the Bliss test package.
 *
 * A file-level (non-private) typealias so every test file in this package
 * can use `MatchType` as shorthand for `BlissSymbol.MatchType` without
 * re-declaring it per-file (which would cause Redeclaration errors).
 */
internal typealias MatchType = BlissSymbol.MatchType

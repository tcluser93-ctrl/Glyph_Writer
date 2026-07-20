package com.blueapps.egyptianwriter.bliss

/**
 * Canonical, single-source-of-truth registry of Bliss grammatical indicators
 * supported by this app.
 *
 * ## Background
 * The official Unicode/BCI proposal for encoding Blissymbols
 * (ISO/IEC JTC1/SC2/WG2 N5228, "Preliminary proposal for encoding
 * Blissymbols in the UCS", 2023-06-23) defines exactly 34 non-spacing
 * combining characters ("indicators") used to mark grammatical categories
 * (nominal: plural/definite/thing; verbal: tense/mood/voice; adjectival:
 * description/participle forms). See §4.3 and §12 of that document for the
 * authoritative list and glyph names.
 *
 * ## Fix (enterprise-grade audit, 2026-07-20)
 * Before this file existed, the app implemented only **3 of the 34** official
 * indicators (plural, past, future), each hardcoded independently in two
 * separate places — [BlissTranslator] (`indicatorIdToName`) and
 * [BlissRenderer] (`bciIdToIndicatorName`) — with **no single source of
 * truth**. That duplication had already caused a real bug: both copies used
 * BCI-AV id `9007` for "past" and `9008` for "future". Cross-checking
 * against this app's own bundled `assets/bliss/bci_names.json`, those ids
 * actually belong to different indicators:
 *   - `9007` = `indicator_(past_passive)`   (not `indicator_(past_action)`)
 *   - `9008` = `indicator_(present_passive_conditional)` (not `indicator_(future_action)`)
 * The correct ids are `9004` (past_action) and `8999` (future_action) — see
 * [PAST] and [FUTURE] below. This means the SVG rendered for "past"/"future"
 * indicators was silently wrong in both the CARDS/chip badge path
 * ([BlissGlyphXBuilder]) and the MIXED overlay path ([BlissRenderer]) —
 * exactly the kind of silent linguistic-correctness bug that matters most in
 * a mission-critical AAC app: the user would see *a* symbol, just not the
 * one they asked for.
 *
 * ## Coverage: 28 of 34 official indicators
 * Every entry below was verified against **this app's own bundled assets**
 * (`assets/bliss/bci_names.json` for the id↔name mapping, and the presence
 * of a matching `assets/bliss/svg/{id}.svg` file) before being added — no id
 * here is guessed. Six official indicators are deliberately **not**
 * included because this app's current BCI-AV vocabulary snapshot has no
 * corresponding, unambiguous, renderable entry:
 *  - `INDICATOR CONTINUOUS` — id 28043 exists in `bci_names.json` but has
 *    **no matching SVG asset**; cannot be safely rendered.
 *  - `INDICATOR FUTURE PARTICIPLE`, `INDICATOR PAST PERFECTIVE PARTICIPLE`,
 *    `INDICATOR PAST/PRESENT/FUTURE PASSIVE PARTICIPLE` (5 indicators) — no
 *    corresponding entry found in `bci_names.json` at all under this app's
 *    vocabulary snapshot.
 * [PAST_PARTICIPLE] itself is a best-effort choice: the asset data has
 * *two* candidate entries (`indicator_(past_participle_1)` id 24674 and
 * `indicator_(past_participle_2)` id 24675) with no further disambiguating
 * information available from the bundled data; id 24674 was chosen as the
 * primary form. This ambiguity should be resolved by someone with access to
 * the actual BCI-AV reference documentation before this indicator is relied
 * upon for anything beyond a best-effort default.
 *
 * ## What this file does NOT do
 * This registry makes all 28 indicators available for *rendering* (SVG
 * lookup, chip badges) and for anyone constructing a
 * `List<String>` of indicators by hand (composer logic, tests). It does
 * **not** extend automatic *detection* of these indicators from natural-
 * language input: [BlissTranslator.detectIndicators] and
 * [MorfologikTagMapper.toBlissIndicators] still only recognise plural/past/
 * future. Extending detection to the other 25 (verb/adjective/adverb
 * markers, conditionals, passives, participles) would require verified
 * knowledge of each bundled Morfologik dictionary's actual POS-tag string
 * format per language — guessing at tag substrings in a mission-critical app
 * risks silently mis-tagging grammar, which is worse than not detecting it
 * at all. That is intentionally left as a separate, follow-up task.
 */
object BlissIndicator {

    /** Broad grammatical category, used to group indicators (e.g. for UI). */
    enum class Category { NOMINAL, VERBAL, ADJECTIVAL }

    /**
     * @param bciAvId  Verified BCI-AV id (see class KDoc for how these were checked).
     * @param category Broad grammatical category.
     * @param badge    Short label for the on-chip indicator badge
     *                 ([BlissGlyphXBuilder]). The three legacy indicators
     *                 (plural/past/future) keep their original single-glyph
     *                 badges ("x", "<", ">"); the newly-added ones use the
     *                 official Leipzig-style glossing abbreviations from the
     *                 Unicode proposal (§6.1) rather than invented icons, to
     *                 avoid unreviewed new iconography in a mission-critical
     *                 app. A follow-up design pass may want dedicated glyphs.
     */
    data class Meta(
        val bciAvId:  Int,
        val category: Category,
        val badge:    String
    )

    // ── Nominal indicators (7 of 7 official — full coverage) ───────────────
    const val PLURAL                = "plural"
    const val DEFINITE_PLURAL       = "definite_plural"
    const val DEFINITE              = "definite"
    const val THING                 = "thing"
    const val PLURAL_THING          = "plural_thing"
    const val DEFINITE_PLURAL_THING = "definite_plural_thing"
    const val DEFINITE_THING        = "definite_thing"

    // ── Verbal indicators (15 of 16 official — CONTINUOUS excluded, see KDoc) ─
    const val ACTION                      = "action"
    const val ACTIVE                      = "active"
    const val PAST                        = "past"
    const val PRESENT                     = "present"
    const val FUTURE                      = "future"
    const val IMPERATIVE                  = "imperative"
    const val PAST_CONDITIONAL            = "past_conditional"
    const val PRESENT_CONDITIONAL         = "present_conditional"
    const val FUTURE_CONDITIONAL          = "future_conditional"
    const val PAST_PASSIVE                = "past_passive"
    const val PASSIVE                     = "passive"
    const val FUTURE_PASSIVE              = "future_passive"
    const val PAST_PASSIVE_CONDITIONAL    = "past_passive_conditional"
    const val PRESENT_PASSIVE_CONDITIONAL = "present_passive_conditional"
    const val FUTURE_PASSIVE_CONDITIONAL  = "future_passive_conditional"

    // ── Adjectival / adverbial indicators (6 of 11 official — 5 participle
    //    forms excluded, see KDoc) ───────────────────────────────────────────
    const val DESCRIPTION_BEFORE_FACT = "description_before_fact"
    const val DESCRIPTION             = "description"
    const val DESCRIPTION_AFTER_FACT  = "description_after_fact"
    /** Best-effort: see class KDoc — ambiguous between two asset candidates. */
    const val PAST_PARTICIPLE         = "past_participle"
    const val PRESENT_PARTICIPLE      = "present_participle"
    /** "Adverb" marker — turns a base concept into a description-of-action. */
    const val DESCRIPTION_OF_ACTION   = "description_of_action"

    private val REGISTRY: Map<String, Meta> = mapOf(
        PLURAL                to Meta(9011,  Category.NOMINAL,    "x"),
        DEFINITE_PLURAL       to Meta(28044, Category.NOMINAL,    "PL.DEF"),
        DEFINITE               to Meta(24667, Category.NOMINAL,   "DEF"),
        THING                  to Meta(9009,  Category.NOMINAL,   "CON"),
        PLURAL_THING           to Meta(9010,  Category.NOMINAL,   "CON.PL"),
        DEFINITE_PLURAL_THING  to Meta(28046, Category.NOMINAL,   "CON.PL.DEF"),
        DEFINITE_THING         to Meta(28045, Category.NOMINAL,   "CON.DEF"),

        ACTION                       to Meta(8993, Category.VERBAL, "VB"),
        ACTIVE                       to Meta(8994, Category.VERBAL, "ACT"),
        PAST                         to Meta(9004, Category.VERBAL, "<"),
        PRESENT                      to Meta(24807, Category.VERBAL, "PRS"),
        FUTURE                       to Meta(8999, Category.VERBAL, ">"),
        IMPERATIVE                   to Meta(24670, Category.VERBAL, "IMP"),
        PAST_CONDITIONAL             to Meta(9005, Category.VERBAL, "COND.PST"),
        PRESENT_CONDITIONAL          to Meta(8995, Category.VERBAL, "COND"),
        FUTURE_CONDITIONAL           to Meta(9000, Category.VERBAL, "COND.FUT"),
        PAST_PASSIVE                 to Meta(9007, Category.VERBAL, "PASS.PST"),
        PASSIVE                      to Meta(9003, Category.VERBAL, "PASS"),
        FUTURE_PASSIVE               to Meta(9001, Category.VERBAL, "PASS.FUT"),
        PAST_PASSIVE_CONDITIONAL     to Meta(9006, Category.VERBAL, "PASS.COND.PST"),
        PRESENT_PASSIVE_CONDITIONAL  to Meta(9008, Category.VERBAL, "PASS.COND"),
        FUTURE_PASSIVE_CONDITIONAL   to Meta(9002, Category.VERBAL, "PASS.COND.FUT"),

        DESCRIPTION_BEFORE_FACT to Meta(8997,  Category.ADJECTIVAL, "POT.ADJ"),
        DESCRIPTION              to Meta(8998, Category.ADJECTIVAL, "ADJ"),
        DESCRIPTION_AFTER_FACT   to Meta(8996, Category.ADJECTIVAL, "ADJ.RES"),
        PAST_PARTICIPLE          to Meta(24674, Category.ADJECTIVAL, "PST.ADJ"),
        PRESENT_PARTICIPLE       to Meta(24677, Category.ADJECTIVAL, "PRS.ADJ"),
        DESCRIPTION_OF_ACTION    to Meta(24665, Category.ADJECTIVAL, "ADJ.VB")
    )

    /** Reverse lookup, built once from [REGISTRY]. */
    private val BY_BCI_ID: Map<Int, String> =
        REGISTRY.entries.associate { (name, meta) -> meta.bciAvId to name }

    /** The verified BCI-AV id for [indicator], or null if not in the registry. */
    fun bciAvId(indicator: String): Int? = REGISTRY[indicator]?.bciAvId

    /** The indicator name for a known BCI-AV [id], or null if not in the registry. */
    fun nameOf(id: Int): String? = BY_BCI_ID[id]

    /** Short chip-badge label for [indicator], or null if not in the registry. */
    fun badge(indicator: String): String? = REGISTRY[indicator]?.badge

    /** Grammatical category for [indicator], or null if not in the registry. */
    fun category(indicator: String): Category? = REGISTRY[indicator]?.category

    /** True if [indicator] is a recognised key in this registry. */
    fun isKnown(indicator: String): Boolean = indicator in REGISTRY

    /** All indicator names currently in the registry (28 of 34 official). */
    fun all(): Set<String> = REGISTRY.keys
}

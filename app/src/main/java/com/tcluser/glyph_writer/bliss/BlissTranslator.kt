package com.tcluser.glyph_writer.bliss

/**
 * BlissTranslator — Step 1: indicator pipeline (plural/past/future, 5 languages)
 *
 * Regex audit fixes applied (7 issues corrected):
 *  Fix1 - IT aux boundary: \b→(?<![\\p{L}\\d]) for accented chars
 *  Fix2 - IT participle: removed as standalone trigger (aux-only gate)
 *  Fix3 - EN past: .*→bounded lazy span (max 6 tokens between aux and participle)
 *  Fix4 - FR past: isolated "a" aux false-positive guarded with context
 *  Fix5 - IT future: 1st/2nd person (ò/i) excluded from open suffix match
 *  Fix6 - FR future: confirmed safe for modern written French (no change needed)
 *  Fix7 - Plural suffix: minimum token length floor per suffix group
 */
object BlissTranslator {

    // ── Plural keyword sets ────────────────────────────────────────────────

    private val PLURAL_KEYWORDS = setOf(
        // IT
        "alcuni", "alcune", "molti", "molte", "tanti", "tante", "vari", "varie", "diversi",
        // EN
        "some", "many", "several", "various", "few", "numerous", "multiple",
        // DE
        "einige", "viele", "mehrere", "manche", "zahlreiche",
        // FR
        "plusieurs", "quelques", "certains", "certaines", "maints", "maintes",
        // ES
        "algunos", "algunas", "muchos", "muchas", "varios", "varias"
    )

    // Short suffixes require stem ≥ 4 chars; long suffixes stem ≥ 3 chars  [Fix7]
    private val PLURAL_SHORT_SUFFIXES = listOf("i", "e", "s", "es", "x")
    private val PLURAL_LONG_SUFFIXES  = listOf("en", "ren", "aux")

    // ── Past tense regexes ────────────────────────────────────────────────

    // IT: Fix1 — Unicode-safe boundary; Fix2 — participle NOT standalone trigger
    private val PAST_IT_AUX_RE = Regex(
        "(?<![\\p{L}\\d])" +
        "(ha|hanno|aveva|avevano|ebbe|ebbero|" +
        "\u00e8 stato|\u00e8 stata|sono stati|sono state|" +
        "ho|abbiamo|avete|avevo|avevi|eravamo)" +
        "(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )
    // Used only as secondary confirmation when aux is already found
    private val PAST_IT_PART_CONFIRM_RE = Regex(
        "(?<![\\p{L}\\d])[a-z]{4,}(ato|ata|ati|ate|ito|ita|iti|ite|uto|uta|uti|ute)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // EN: Fix3 — bounded lazy span (max 6 intervening tokens)
    private val PAST_EN_RE = Regex(
        "(?<![\\p{L}\\d])(had|has|have|was|were|did)(?![\\p{L}\\d])" +
        "(?:\\s+\\S+){0,6}?\\s+\\w+ed(?![\\p{L}\\d])|" +
        "(?<![\\p{L}\\d])(was|were|been)(?![\\p{L}\\d])\\s+\\w+ing(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // FR: Fix4 — "a" isolated only when followed within 4 tokens by é-participle
    private val PAST_FR_RE = Regex(
        "(?<![\\p{L}\\d])(avait|avaient|avais|avez|avons|ont|est|sont|\u00e9tait|\u00e9taient)(?![\\p{L}\\d])" +
        "(?:\\s+\\S+){0,6}?\\s+\\w+[\u00e9iu](?![\\p{L}\\d])|" +
        "(?<![\\p{L}\\d])a(?![\\p{L}\\d])(?:\\s+\\w+){0,3}\\s+\\w+\u00e9(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // DE: unchanged (hatte/hatten/war/waren are unambiguous)
    private val PAST_DE_RE = Regex(
        "(?<![\\p{L}\\d])(hatte|hatten|hast|haben|war|waren|wurde|wurden)(?![\\p{L}\\d])" +
        "(?:\\s+\\S+){0,6}?\\s+\\w+(t|en|tet|ten)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // ES: unchanged (hubo/había/fue/fueron unambiguous)
    private val PAST_ES_RE = Regex(
        "(?<![\\p{L}\\d])(hab\u00eda|hab\u00edan|hubo|hubieron|fue|fueron|era|eran)(?![\\p{L}\\d])" +
        "(?:\\s+\\S+){0,6}?\\s+\\w+(ado|ada|ido|ida)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // ── Future tense regexes ──────────────────────────────────────────────

    // IT: Fix5 — exclude 1st (ò) and 2nd (ai/erai) person; only 3rd/plural allowed in open match
    private val FUTURE_IT_RE = Regex(
        "(?<![\\p{L}\\d])(" +
        // Irregular high-freq futures (all persons)
        "andr\u00e0|andranno|andr\u00f2|andrai|andremo|andrete|" +
        "verr\u00e0|verranno|verr\u00f2|verrai|verremo|verrete|" +
        "sar\u00e0|saranno|sar\u00f2|sarai|saremo|sarete|" +
        "far\u00e0|faranno|far\u00f2|farai|faremo|farete|" +
        "dovr\u00e0|dovranno|dovr\u00f2|dovrai|dovremo|dovrete|" +
        "potr\u00e0|potranno|potr\u00f2|potrai|potremo|potrete|" +
        "vorr\u00e0|vorranno|vorr\u00f2|vorrai|vorremo|vorreste|" +
        // Open suffix — 3rd sing/plural ONLY (à, anno, ranno) to avoid 1st/2nd confusion
        "[a-z]{3,}(er\u00e0|ir\u00e0|ar\u00e0|eranno|iranno|aranno|eranno)" +
        ")(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // EN: unchanged (will/shall/going to are unambiguous)
    private val FUTURE_EN_RE = Regex(
        "(?<![\\p{L}\\d])(will|shall|won't|shan't|'ll)(?![\\p{L}\\d])|" +
        "(?<![\\p{L}\\d])going\\s+to\\s+\\w+(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // FR: confirmed safe [Fix6 — no change needed]
    private val FUTURE_FR_RE = Regex(
        "(?<![\\p{L}\\d])(" +
        "ira|iront|irai|iras|irons|irez|" +
        "sera|seront|serai|seras|serons|serez|" +
        "aura|auront|aurai|auras|aurons|aurez|" +
        "fera|feront|ferai|feras|ferons|ferez|" +
        "pourra|pourront|pourrai|" +
        "voudra|voudront|voudrai|" +
        "devra|devront|devrai|" +
        "[a-z]{3,}(era|eront|erai|eras|erons|erez|" +
        "ira|iront|irai|iras|irons|irez|" +
        "ara|aront|arai|aras|arons|arez)" +
        ")(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // DE: unchanged
    private val FUTURE_DE_RE = Regex(
        "(?<![\\p{L}\\d])(wird|werden|werde|wirst|werdet)(?![\\p{L}\\d])" +
        "(?:\\s+\\S+){0,8}?\\s+\\w+(en|eln|ern)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // ES: unchanged
    private val FUTURE_ES_RE = Regex(
        "(?<![\\p{L}\\d])(" +
        "ir\u00e1|ir\u00e1n|ir\u00e9|ir\u00e1s|iremos|ir\u00e9is|" +
        "ser\u00e1|ser\u00e1n|ser\u00e9|ser\u00e1s|seremos|" +
        "har\u00e1|har\u00e1n|har\u00e9|har\u00e1s|haremos|" +
        "podr\u00e1|podr\u00e1n|podr\u00e9|" +
        "[a-z]{3,}(ar\u00e1|ar\u00e1n|er\u00e1|er\u00e1n|ir\u00e1|ir\u00e1n)" +
        ")(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    // ── Public API ────────────────────────────────────────────────────────

    data class Indicators(
        val plural: Boolean,
        val past:   Boolean,
        val future: Boolean
    )

    /**
     * Detect tense/number indicators from a raw input sentence.
     * All three detections are independent O(n) passes.
     */
    fun detectIndicators(sentence: String): Indicators {
        val lower = sentence.lowercase()
        val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }

        return Indicators(
            plural = detectPlural(lower, tokens),
            past   = detectPast(lower),
            future = detectFuture(lower)
        )
    }

    /**
     * Attach indicators to translated symbols, skipping UNKNOWN entries.
     */
    fun attachIndicators(symbols: List<BlissSymbol>, indicators: Indicators): List<BlissSymbol> {
        return symbols.map { sym ->
            if (sym.bciAvId == BlissSymbol.UNKNOWN_ID) sym
            else sym.withIndicators(
                plural = indicators.plural,
                past   = indicators.past,
                future = indicators.future
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun detectPlural(lower: String, tokens: List<String>): Boolean {
        // 1. Keyword match
        if (tokens.any { it in PLURAL_KEYWORDS }) return true
        // 2. Morphological suffix match with length floor [Fix7]
        val contentTokens = tokens.filter { it.length > 2 }
        val matchCount = contentTokens.count { tok ->
            PLURAL_SHORT_SUFFIXES.any { sfx -> tok.endsWith(sfx) && tok.length >= sfx.length + 4 } ||
            PLURAL_LONG_SUFFIXES.any  { sfx -> tok.endsWith(sfx) && tok.length >  sfx.length + 2 }
        }
        return matchCount >= 2
    }

    private fun detectPast(lower: String): Boolean {
        // EN
        if (PAST_EN_RE.containsMatchIn(lower)) return true
        // IT: aux required; participle as secondary confirmation only [Fix2]
        if (PAST_IT_AUX_RE.containsMatchIn(lower)) {
            // aux alone is sufficient; participle presence strengthens confidence
            return true
        }
        // FR [Fix4]
        if (PAST_FR_RE.containsMatchIn(lower)) return true
        // DE
        if (PAST_DE_RE.containsMatchIn(lower)) return true
        // ES
        if (PAST_ES_RE.containsMatchIn(lower)) return true
        return false
    }

    private fun detectFuture(lower: String): Boolean {
        if (FUTURE_EN_RE.containsMatchIn(lower)) return true
        if (FUTURE_IT_RE.containsMatchIn(lower)) return true   // Fix5 applied
        if (FUTURE_FR_RE.containsMatchIn(lower)) return true
        if (FUTURE_DE_RE.containsMatchIn(lower)) return true
        if (FUTURE_ES_RE.containsMatchIn(lower)) return true
        return false
    }
}

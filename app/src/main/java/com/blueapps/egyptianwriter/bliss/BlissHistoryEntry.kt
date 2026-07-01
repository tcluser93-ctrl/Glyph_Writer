package com.blueapps.egyptianwriter.bliss

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent record of one user translation stored in the [BlissDatabase]
 * `bliss_history` table.
 *
 * ## Schema
 * | Column | Type | Notes |
 * |---|---|---|
 * | id | INTEGER PK | Auto-generated |
 * | input_text | TEXT | Raw user input (trimmed) |
 * | lang_code | TEXT | ISO-639-1 language code, e.g. `"it"` |
 * | symbol_bci_ids | TEXT | Comma-joined BCI-AV IDs, e.g. `"12345,67890"` |
 * | coverage | REAL | Fraction 0..1 of non-UNKNOWN symbols |
 * | timestamp_ms | INTEGER | `System.currentTimeMillis()` at save time |
 *
 * ## Serialisation of symbol list
 * BCI-AV IDs are stored as a comma-joined string for simplicity and minimal
 * overhead.  Use [symbolIds] / [fromIdString] helpers to convert.
 * A dedicated TypeConverter is intentionally avoided — the list never grows
 * beyond a few hundred elements and a simple split is O(n) with no reflection.
 *
 * ## Index
 * An index on `(lang_code, timestamp_ms DESC)` allows the DAO's paginated
 * `getPage` and `getRecent` queries to avoid full-table scans.
 */
@Entity(
    tableName = "bliss_history",
    indices   = [Index(value = ["lang_code", "timestamp_ms"])]
)
data class BlissHistoryEntry(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** Raw text the user submitted for translation (trimmed, max 4096 chars). */
    @ColumnInfo(name = "input_text")
    val inputText: String,

    /** ISO-639-1 language code in use when the translation was performed. */
    @ColumnInfo(name = "lang_code")
    val langCode: String,

    /**
     * BCI-AV IDs of the resulting [BlissSymbol]s, comma-joined.
     * Empty string when the translation produced no symbols.
     */
    @ColumnInfo(name = "symbol_bci_ids")
    val symbolBciIds: String,

    /**
     * Translation coverage: fraction 0..1 of symbols whose [BlissSymbol.matchType]
     * is not [BlissSymbol.MatchType.UNKNOWN].
     */
    @ColumnInfo(name = "coverage")
    val coverage: Float,

    /** Wall-clock timestamp at the moment this entry was persisted. */
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis()
) {

    /** Returns the BCI-AV ID list decoded from [symbolBciIds]. */
    val symbolIds: List<Int>
        get() = if (symbolBciIds.isBlank()) emptyList()
                else symbolBciIds.split(',').mapNotNull { it.trim().toIntOrNull() }

    companion object {

        /** Maximum length for [inputText]; longer inputs are truncated before save. */
        const val MAX_INPUT_LENGTH = 4096

        /**
         * Factory that builds a [BlissHistoryEntry] from the result of a
         * translation, ready for persistence.
         *
         * @param inputText   Original user input (will be trimmed + truncated).
         * @param langCode    Active language code.
         * @param symbols     Translated [BlissSymbol] list produced by [BlissTranslator].
         */
        fun from(
            inputText: String,
            langCode:  String,
            symbols:   List<BlissSymbol>
        ): BlissHistoryEntry {
            val stats    = TranslationStats.from(symbols)
            val idsStr   = symbols.joinToString(",") { it.bciAvId.toString() }
            return BlissHistoryEntry(
                inputText    = inputText.trim().take(MAX_INPUT_LENGTH),
                langCode     = langCode,
                symbolBciIds = idsStr,
                coverage     = stats.coverage
            )
        }
    }
}

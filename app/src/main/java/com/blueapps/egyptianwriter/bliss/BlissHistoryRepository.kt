package com.blueapps.egyptianwriter.bliss

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository that mediates between [BlissViewModel] and the Room
 * `bliss_history` table via [BlissHistoryDao].
 *
 * ## Responsibilities
 * - Persist a new [BlissHistoryEntry] after each successful translation.
 * - Enforce a maximum history size ([MAX_HISTORY_SIZE]) by pruning stale rows.
 * - Expose reactive [Flow]s for the history list and the typeahead input list.
 * - Provide delete operations (single entry, by language, all).
 *
 * ## Threading contract
 * All `suspend` methods switch to [Dispatchers.IO] internally so callers on
 * the Main dispatcher or [androidx.lifecycle.viewModelScope] are safe to
 * call them directly without wrapping in `withContext`.
 *
 * ## Instantiation
 * The repository is instantiated once per [BlissViewModel] and shares the
 * [BlissDatabase] singleton.  It must NOT be created on the Main thread
 * (Room’s database builder performs a small sync check on first access).
 *
 * @param db  Application-scoped [BlissDatabase] singleton.
 */
class BlissHistoryRepository(private val db: BlissDatabase) {

    private val dao: BlissHistoryDao = db.historyDao()

    // ── reactive reads (Flow) ─────────────────────────────────────────────────

    /**
     * Returns a [Flow] of up to [limit] history entries for [langCode],
     * ordered newest-first.  Room invalidates the flow automatically on
     * every table write so the UI stays in sync without polling.
     *
     * Pass [ALL_LANGS] as [langCode] to observe entries across all languages.
     *
     * @param langCode  ISO-639-1 code, e.g. `"it"`, or [ALL_LANGS] (`"%"`).
     * @param limit     Maximum rows emitted per update (default 50).
     */
    fun recentHistory(
        langCode: String = ALL_LANGS,
        limit:    Int    = 50
    ): Flow<List<BlissHistoryEntry>> =
        dao.observeAll(langCode = langCode, limit = limit)

    /**
     * Returns a [Flow] of up to [limit] **distinct** input texts for [langCode],
     * ordered by most-recent occurrence.  Used to populate the typeahead
     * autocomplete dropdown in [BlissTranslateFragment].
     *
     * @param langCode  ISO-639-1 code (required; not nullable here to prevent
     *                  accidentally querying across all languages in the autocomplete).
     * @param limit     Maximum items in the emitted list (default 20).
     */
    fun recentInputs(
        langCode: String,
        limit:    Int = 20
    ): Flow<List<String>> =
        dao.observeRecentInputs(langCode = langCode, limit = limit)

    // ── writes (suspend) ────────────────────────────────────────────────────────────

    /**
     * Persists a new history entry built from [inputText], [langCode], and
     * [symbols], then prunes excess rows so the table never exceeds
     * [MAX_HISTORY_SIZE] entries.
     *
     * This is the canonical write path; callers never build [BlissHistoryEntry]
     * themselves — they pass the raw translation result here.
     *
     * @param inputText  Raw user input (will be trimmed/truncated by
     *                   [BlissHistoryEntry.from]).
     * @param langCode   Active language code at translation time.
     * @param symbols    Full symbol list returned by [BlissTranslator].
     * @return           The Room row-ID of the inserted entry, or -1 on error.
     */
    suspend fun saveTranslation(
        inputText: String,
        langCode:  String,
        symbols:   List<BlissSymbol>
    ): Long = withContext(Dispatchers.IO) {
        if (inputText.isBlank() || symbols.isEmpty()) return@withContext -1L
        return@withContext try {
            val entry  = BlissHistoryEntry.from(inputText, langCode, symbols)
            val rowId  = dao.insert(entry)
            deleteExcess()
            Log.d(TAG, "History saved: id=$rowId, lang=$langCode, symbols=${symbols.size}")
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history entry", e)
            -1L
        }
    }

    /**
     * Hard-deletes a single history entry by its primary key.
     * No-op (and silent) if the ID does not exist.
     *
     * @param id  Row ID of the [BlissHistoryEntry] to remove.
     */
    suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
        try {
            dao.deleteById(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete history entry id=$id", e)
        }
    }

    /**
     * Deletes all history entries for a specific language.
     *
     * @param langCode  ISO-639-1 code, e.g. `"it"`.
     */
    suspend fun clearLang(langCode: String) = withContext(Dispatchers.IO) {
        try {
            dao.deleteByLang(langCode)
            Log.i(TAG, "History cleared for lang=$langCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history for lang=$langCode", e)
        }
    }

    /**
     * Deletes the entire history table across all languages.
     * Typically called from a user-facing ‘Clear all history’ action.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            dao.deleteAll()
            Log.i(TAG, "Full history cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear full history", e)
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────────────────

    /**
     * Deletes the oldest entries when the table exceeds [MAX_HISTORY_SIZE].
     * Uses a single `DELETE ... WHERE id IN (SELECT ...)` to minimise
     * round-trips; safe to call frequently as it is a no-op when the table
     * is within budget.
     */
    private suspend fun deleteExcess() {
        val total = dao.count()
        if (total <= MAX_HISTORY_SIZE) return
        val excess = (total - MAX_HISTORY_SIZE).toInt()
        // Fetch oldest IDs and delete them in one batch via deleteById loop.
        // Room does not expose a generic DELETE LIMIT; the approach below is
        // both readable and safe on all SQLite versions shipped with Android.
        val oldest = dao.getPage(
            langCode = "%",
            limit    = excess,
            offset   = MAX_HISTORY_SIZE.toInt()
        )
        oldest.forEach { dao.deleteById(it.id) }
        Log.d(TAG, "History pruned: removed ${oldest.size} excess entries")
    }

    // ── companion ───────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BlissHistoryRepository"

        /** Maximum number of history entries retained across all languages. */
        const val MAX_HISTORY_SIZE: Long = 100L

        /**
         * Wildcard value for the `langCode` parameter in [recentHistory].
         * Matches all language-tagged rows in the FTS / history tables.
         */
        const val ALL_LANGS = "%"
    }
}

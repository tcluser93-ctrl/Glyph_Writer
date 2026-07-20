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
 * - Provide delete / restore operations (single entry, by language, all).
 * - Expose [searchHistory] for filtered full-text search on `input_text`.
 *
 * ## Threading contract
 * All `suspend` methods switch to [Dispatchers.IO] internally so callers on
 * the Main dispatcher or [androidx.lifecycle.viewModelScope] are safe to
 * call them directly without wrapping in `withContext`.
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
     */
    fun recentHistory(
        langCode: String = ALL_LANGS,
        limit:    Int    = 50
    ): Flow<List<BlissHistoryEntry>> =
        dao.observeAll(langCode = langCode, limit = limit)

    /**
     * Returns a [Flow] of up to [limit] distinct input texts for [langCode],
     * ordered by most-recent occurrence.
     */
    fun recentInputs(
        langCode: String,
        limit:    Int = 20
    ): Flow<List<String>> =
        dao.observeRecentInputs(langCode = langCode, limit = limit)

    /**
     * Returns a [Flow] of up to [limit] history entries whose `input_text`
     * contains [query] (case-insensitive substring match), filtered by
     * [langCode] and ordered newest-first.
     *
     * When [query] is blank the result is equivalent to [recentHistory].
     * Delegates directly to [BlissHistoryDao.searchByText]; Room handles
     * thread switching internally.
     *
     * @param query    Substring to search for inside `input_text`.
     * @param langCode Language filter — pass [ALL_LANGS] for all languages.
     * @param limit    Maximum rows per emission.
     */
    fun searchHistory(
        query:    String,
        langCode: String = ALL_LANGS,
        limit:    Int    = 50
    ): Flow<List<BlissHistoryEntry>> =
        dao.searchByText(query = query, langCode = langCode, limit = limit)

    // ── writes (suspend) ─────────────────────────────────────────────────────

    /**
     * Persists a new history entry built from [inputText], [langCode], and
     * [symbols], then prunes excess rows so the table never exceeds
     * [MAX_HISTORY_SIZE] entries.
     *
     * @return  The Room row-ID of the inserted entry, or -1 on error.
     */
    suspend fun saveTranslation(
        inputText: String,
        langCode:  String,
        symbols:   List<BlissSymbol>
    ): Long = withContext(Dispatchers.IO) {
        if (inputText.isBlank() || symbols.isEmpty()) return@withContext -1L
        return@withContext try {
            val entry = BlissHistoryEntry.from(inputText, langCode, symbols)
            val rowId = dao.insert(entry)
            deleteExcess()
            Log.d(TAG, "History saved: id=$rowId, lang=$langCode, symbols=${symbols.size}")
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history entry", e)
            -1L
        }
    }

    /**
     * Re-inserts a previously deleted [BlissHistoryEntry] (e.g. undo
     * swipe-to-delete or undo clear-all).
     *
     * ## Fix (enterprise-grade audit, 2026-07-20)
     * Previously the entry was re-inserted with its **original** primary
     * key, and [BlissHistoryDao.insert] uses
     * [androidx.room.OnConflictStrategy.REPLACE]. `BlissHistoryEntry` uses
     * `@PrimaryKey(autoGenerate = true)` without the SQLite `AUTOINCREMENT`
     * keyword, so a rowid can be reused by a later insert once the row that
     * held it is deleted (most reachable when the deleted row held the
     * current highest id — e.g. the user just deleted their most recent
     * translation). Sequence that used to silently destroy data:
     *  1. User swipes away entry id=42 (the most recent one) → Snackbar
     *     with "Undo" appears, holding a reference to the deleted entry.
     *  2. Before tapping Undo, the user performs another translation, which
     *     auto-saves via [saveTranslation] — SQLite may reuse the now-free
     *     id 42 for this brand-new, unrelated row.
     *  3. The user taps "Undo" on the (now stale) Snackbar → `insertEntry`
     *     re-inserts the OLD entry with id=42 → REPLACE silently overwrites
     *     the NEW translation that happens to occupy id=42, permanently
     *     losing it with no error, crash, or user-visible signal.
     *
     * Fixed by always stripping the id (forcing Room to autogenerate a
     * fresh, guaranteed-unused one) before restoring. This has no visible
     * UX effect — the history list is ordered by `timestamp_ms DESC`
     * ([BlissHistoryDao.observeAll]), never by id — while eliminating the
     * whole class of rowid-reuse collisions.
     *
     * @param entry  The entry to restore. Its original [BlissHistoryEntry.id]
     *               is intentionally discarded; Room assigns a new one.
     * @return       The Room row-ID, or -1 on error.
     */
    suspend fun insertEntry(entry: BlissHistoryEntry): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            val rowId = dao.insert(entry.copy(id = 0L))
            Log.d(TAG, "History entry restored as new id=$rowId (was id=${entry.id})")
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore history entry (was id=${entry.id})", e)
            -1L
        }
    }

    /**
     * Hard-deletes a single history entry by its primary key.
     * No-op (and silent) if the ID does not exist.
     */
    suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
        try {
            dao.deleteById(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete history entry id=$id", e)
        }
    }

    /** Deletes all history entries for a specific language. */
    suspend fun clearLang(langCode: String) = withContext(Dispatchers.IO) {
        try {
            dao.deleteByLang(langCode)
            Log.i(TAG, "History cleared for lang=$langCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history for lang=$langCode", e)
        }
    }

    /** Deletes the entire history table across all languages. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            dao.deleteAll()
            Log.i(TAG, "Full history cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear full history", e)
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private suspend fun deleteExcess() {
        val total = dao.count()
        if (total <= MAX_HISTORY_SIZE) return
        val excess = (total - MAX_HISTORY_SIZE).toInt()
        val oldest = dao.getPage(
            langCode = "%",
            limit    = excess,
            offset   = MAX_HISTORY_SIZE.toInt()
        )
        oldest.forEach { dao.deleteById(it.id) }
        Log.d(TAG, "History pruned: removed ${oldest.size} excess entries")
    }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BlissHistoryRepository"
        const val MAX_HISTORY_SIZE: Long = 100L
        const val ALL_LANGS = "%"
    }
}

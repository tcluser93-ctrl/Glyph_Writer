package com.blueapps.egyptianwriter.bliss

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `bliss_history` table.
 *
 * All write operations are `suspend` and must be called from a coroutine
 * running on an appropriate dispatcher (Room will internally switch to an IO
 * thread pool thread regardless, but following the convention keeps callers
 * honest and avoids accidental main-thread invocations).
 *
 * [observeAll] returns a [Flow] that Room invalidates automatically whenever
 * the table is written, enabling reactive UI updates with zero polling.
 */
@Dao
interface BlissHistoryDao {

    // ── writes ────────────────────────────────────────────────────────────────

    /**
     * Inserts a new history entry and returns the generated row ID.
     * Uses [OnConflictStrategy.REPLACE] so a re-inserted duplicate (same
     * auto-generated id=0) is treated as a fresh insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BlissHistoryEntry): Long

    /**
     * Hard-deletes a specific entry by its primary key.
     * No-op if the ID is not found.
     */
    @Query("DELETE FROM bliss_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Clears the entire history table for the current language. */
    @Query("DELETE FROM bliss_history WHERE lang_code = :langCode")
    suspend fun deleteByLang(langCode: String)

    /** Clears the entire history table across all languages. */
    @Query("DELETE FROM bliss_history")
    suspend fun deleteAll()

    // ── reads (suspend) ───────────────────────────────────────────────────────

    /**
     * Returns a single page of history entries ordered by most-recent first.
     *
     * @param langCode  Language filter (pass `"%"` to query all languages).
     * @param limit     Maximum rows to return (page size).
     * @param offset    Row offset for pagination.
     */
    @Query("""
        SELECT * FROM bliss_history
        WHERE lang_code LIKE :langCode
        ORDER BY timestamp_ms DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPage(
        langCode: String = "%",
        limit:    Int    = 20,
        offset:   Int    = 0
    ): List<BlissHistoryEntry>

    /**
     * Returns the most recent [n] entries regardless of language, ordered
     * newest-first.  Useful for a compact "recent translations" summary.
     */
    @Query("""
        SELECT * FROM bliss_history
        ORDER BY timestamp_ms DESC
        LIMIT :n
    """)
    suspend fun getRecent(n: Int = 5): List<BlissHistoryEntry>

    /** Total row count across all languages. */
    @Query("SELECT COUNT(*) FROM bliss_history")
    suspend fun count(): Long

    // ── reactive reads (Flow) ────────────────────────────────────────────────

    /**
     * Returns a [Flow] that emits the full history list whenever the table
     * changes, filtered to [langCode].
     *
     * Collect this on [androidx.lifecycle.Lifecycle.State.STARTED] or later
     * to avoid background emissions while the UI is invisible.
     *
     * @param langCode  ISO-639-1 code, or `"%"` to observe all languages.
     * @param limit     Cap the emitted list size (most recent entries first).
     */
    @Query("""
        SELECT * FROM bliss_history
        WHERE lang_code LIKE :langCode
        ORDER BY timestamp_ms DESC
        LIMIT :limit
    """)
    fun observeAll(
        langCode: String = "%",
        limit:    Int    = 50
    ): Flow<List<BlissHistoryEntry>>

    /**
     * Returns a [Flow] that emits the list of **distinct** input texts for
     * a given [langCode], ordered by recency.  Used to populate the
     * "recent queries" autocomplete dropdown in [BlissTranslateFragment].
     */
    @Query("""
        SELECT DISTINCT input_text FROM bliss_history
        WHERE lang_code = :langCode
        ORDER BY timestamp_ms DESC
        LIMIT :limit
    """)
    fun observeRecentInputs(
        langCode: String,
        limit:    Int = 20
    ): Flow<List<String>>
}

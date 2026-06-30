package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.FtsOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// ── Entity ────────────────────────────────────────────────────────────────────

/**
 * Row in the `bci_fts` FTS4 virtual table.
 *
 * FTS4 is used instead of FTS5 for maximum compatibility across Android API
 * levels (SQLite version bundled with Android before API 30 may lack FTS5).
 * All columns except [bciId] and [pos] are indexed for full-text search.
 *
 * Schema equivalent:
 * ```sql
 * CREATE VIRTUAL TABLE bci_fts USING fts4(
 *     keyword, lang,
 *     content="",          -- contentless for minimal size
 *     tokenize="unicode61 remove_diacritics=1"
 * );
 * ```
 */
@Entity(tableName = "bci_fts")
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"]
)
data class BciFtsEntry(
    /** Surface word or lemma (lower-cased, diacritics normalized by tokenizer). */
    val keyword: String,
    /** ISO-639-1 language code, e.g. `"it"`, `"en"`, `"de"`. */
    val lang: String,
    /** BCI-AV numeric ID. Stored as plain column (unindexed in FTS sense). */
    val bciId: Int,
    /** POS category code: N V A R P D C I X (optional, empty string if unknown). */
    val pos: String = ""
)

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface BciDao {

    /**
     * Exact keyword + language lookup. Equivalent to
     * `SELECT bciId FROM bci_fts WHERE keyword = :word AND lang = :lang LIMIT 1`.
     *
     * FTS4 exact match is achieved with `"keyword"` quoting to suppress stemming.
     */
    @Query("""
        SELECT bciId FROM bci_fts
        WHERE keyword MATCH '"' || :word || '"' AND lang = :lang
        LIMIT 1
    """)
    suspend fun lookupExact(word: String, lang: String): Int?

    /**
     * Prefix search. Returns up to [limit] BCI IDs whose keyword starts with
     * [prefix] in [lang]. Enables real-time typeahead suggestions in the UI.
     */
    @Query("""
        SELECT bciId FROM bci_fts
        WHERE keyword MATCH :prefix || '*' AND lang = :lang
        ORDER BY rowid
        LIMIT :limit
    """)
    suspend fun lookupPrefix(prefix: String, lang: String, limit: Int = 10): List<Int>

    /** Bulk insert — used during first-run DB population from JSON assets. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<BciFtsEntry>)

    /** Row count — used to detect an empty / unpopulated database. */
    @Query("SELECT COUNT(*) FROM bci_fts")
    suspend fun count(): Long
}

// ── Database ──────────────────────────────────────────────────────────────────

/**
 * Room database wrapping the BCI-AV FTS4 lookup table.
 *
 * ## First-run population
 * On first install the database is empty. [BlissLookup] calls [populateIfEmpty]
 * which bulk-inserts all entries from the in-memory HashMap once loaded.
 * Subsequent launches hit the pre-populated DB directly.
 *
 * ## Usage
 * ```kotlin
 * val db = BlissDatabase.getInstance(context)
 * val id: Int? = db.bciDao().lookupExact("walk", "en")
 * val suggestions: List<Int> = db.bciDao().lookupPrefix("wal", "en", limit = 5)
 * ```
 */
@Database(
    entities = [BciFtsEntry::class],
    version = 1,
    exportSchema = false
)
abstract class BlissDatabase : RoomDatabase() {

    abstract fun bciDao(): BciDao

    companion object {
        private const val TAG     = "BlissDatabase"
        private const val DB_NAME = "bci_fts.db"

        @Volatile private var INSTANCE: BlissDatabase? = null

        /**
         * Returns the process-wide singleton Room database.
         * Always pass [applicationContext].
         */
        fun getInstance(context: Context): BlissDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlissDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }

        /**
         * Populates the FTS table from [lexicon] if the table is empty.
         * Called once per language change from [BlissLookup.initDb].
         *
         * @param db      Open [BlissDatabase] instance.
         * @param lexicon Surface-word → BCI-AV ID map (from [BlissLookup._lexicon]).
         * @param lemmas  Lemma → BCI-AV ID map (from [BlissLookup._lemmaIndex]).
         * @param lang    ISO-639-1 code, used to tag every inserted row.
         */
        suspend fun populateIfEmpty(
            db:      BlissDatabase,
            lexicon: Map<String, Int>,
            lemmas:  Map<String, Int>,
            lang:    String
        ) = withContext(Dispatchers.IO) {
            val dao = db.bciDao()
            if (dao.count() > 0L) {
                Log.d(TAG, "DB already populated — skip")
                return@withContext
            }
            val batch = ArrayList<BciFtsEntry>(lexicon.size + lemmas.size)
            lexicon.forEach { (word, id) ->
                batch += BciFtsEntry(keyword = word, lang = lang, bciId = id)
            }
            lemmas.forEach { (lemma, id) ->
                // avoid duplicating keys already in lexicon
                if (lemma !in lexicon) {
                    batch += BciFtsEntry(keyword = lemma, lang = lang, bciId = id)
                }
            }
            // Insert in chunks of 500 to avoid SQLite bind-parameter limit
            batch.chunked(500).forEach { chunk -> dao.insertAll(chunk) }
            Log.i(TAG, "DB populated: ${batch.size} entries for lang=$lang")
        }
    }
}

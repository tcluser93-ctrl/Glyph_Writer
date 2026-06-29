package com.blueapps.blisswriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blueapps.blisswriter.data.model.BlissEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface BlissDao {

    /** Inserimento/aggiornamento bulk al seeding iniziale */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<BlissEntry>)

    /** Cerca tutte le entry per una parola esatta in una lingua */
    @Query("SELECT * FROM bliss_entries WHERE sourceWord = :word AND sourceLang = :lang")
    fun findByWord(word: String, lang: String): Flow<List<BlissEntry>>

    /** Ricerca fuzzy — parole che iniziano con il prefisso dato */
    @Query("SELECT * FROM bliss_entries WHERE sourceWord LIKE :prefix || '%' AND sourceLang = :lang LIMIT 50")
    fun searchByPrefix(prefix: String, lang: String): Flow<List<BlissEntry>>

    /** Tutte le entry di una lingua (per debug/export) */
    @Query("SELECT * FROM bliss_entries WHERE sourceLang = :lang ORDER BY sourceWord ASC")
    fun allForLang(lang: String): Flow<List<BlissEntry>>

    /** Conta totale entry nel DB */
    @Query("SELECT COUNT(*) FROM bliss_entries")
    suspend fun count(): Long
}

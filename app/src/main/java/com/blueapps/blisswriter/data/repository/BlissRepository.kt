package com.blueapps.blisswriter.data.repository

import com.blueapps.blisswriter.data.db.BlissDao
import com.blueapps.blisswriter.data.model.BlissEntry
import kotlinx.coroutines.flow.Flow

/**
 * Repository unico per il lessico Bliss.
 * Espone operazioni di lettura/scrittura al ViewModel
 * nascondendo i dettagli di Room.
 */
class BlissRepository(private val dao: BlissDao) {

    fun translate(word: String, lang: String): Flow<List<BlissEntry>> =
        dao.findByWord(word.trim().lowercase(), lang)

    fun suggest(prefix: String, lang: String): Flow<List<BlissEntry>> =
        dao.searchByPrefix(prefix.trim().lowercase(), lang)

    fun allEntries(lang: String): Flow<List<BlissEntry>> =
        dao.allForLang(lang)

    suspend fun seed(entries: List<BlissEntry>) = dao.insertAll(entries)

    suspend fun isEmpty(): Boolean = dao.count() == 0L
}

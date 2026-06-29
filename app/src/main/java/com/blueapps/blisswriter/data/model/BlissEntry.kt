package com.blueapps.blisswriter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entità Room — riga del lessico Bliss.
 *
 * Ogni riga mappa una parola/concetto in una lingua sorgente
 * a uno o più simboli Bliss (sequenza ordinata di bciAvId).
 *
 * @param id          PK auto-generato
 * @param sourceWord  Parola nella lingua sorgente (es. "mamma")
 * @param sourceLang  Codice ISO 639-1 ("it", "en", "de", ...)
 * @param bciAvIds    Sequenza JSON di bciAvId (es. "[12335, 17697]")
 * @param partOfSpeech Categoria grammaticale primaria
 * @param notes       Note opzionali (disambiguazioni, varianti regionali)
 */
@Entity(tableName = "bliss_entries")
data class BlissEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceWord: String,
    val sourceLang: String,
    val bciAvIds: String,          // JSON array serializzato
    val partOfSpeech: String,
    val notes: String = ""
)

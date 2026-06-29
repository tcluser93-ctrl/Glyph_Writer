package com.blueapps.blisswriter.data.seed

import android.content.Context
import com.blueapps.blisswriter.data.model.BlissEntry
import com.blueapps.blisswriter.data.repository.BlissRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Carica il lessico Bliss dal file JSON negli assets nel database Room.
 * È idempotente: non fa nulla se il DB contiene già dati.
 *
 * Utilizzo (es. in Application.onCreate o in una WorkRequest):
 *   BlissSeedLoader.seedIfEmpty(context, repository)
 */
object BlissSeedLoader {

    /**
     * Legge [assetFileName] dagli assets, parsa il JSON
     * e chiama [repository].seed() solo se il DB è vuoto.
     *
     * @param context       Contesto Android (applicationContext)
     * @param repository    Istanza di BlissRepository già inizializzata
     * @param assetFileName Nome file in assets/ (default: bliss_seed_it.json)
     */
    suspend fun seedIfEmpty(
        context: Context,
        repository: BlissRepository,
        assetFileName: String = "bliss_seed_it.json"
    ) = withContext(Dispatchers.IO) {
        if (!repository.isEmpty()) return@withContext

        val entries = parseAsset(context, assetFileName)
        if (entries.isNotEmpty()) {
            repository.seed(entries)
        }
    }

    /**
     * Parsa il file JSON e restituisce una lista di [BlissEntry].
     * Gestisce sia il formato { bciAvIds: [int] } che { bciAvIds: int }.
     */
    fun parseAsset(context: Context, assetFileName: String): List<BlissEntry> {
        val json = context.assets.open(assetFileName)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parseJson(json)
    }

    /**
     * Parsa una stringa JSON (esposto per i test unitari senza Context).
     */
    fun parseJson(json: String): List<BlissEntry> {
        val root = JSONObject(json)
        val entriesJson = root.getJSONArray("entries")
        val result = mutableListOf<BlissEntry>()

        for (i in 0 until entriesJson.length()) {
            val obj = entriesJson.getJSONObject(i)

            // bciAvIds può essere array o intero singolo
            val idsArray = obj.getJSONArray("bciAvIds")
            val ids = buildList {
                for (j in 0 until idsArray.length()) add(idsArray.getInt(j))
            }

            result.add(
                BlissEntry(
                    sourceWord   = obj.getString("sourceWord").lowercase().trim(),
                    sourceLang   = obj.getString("sourceLang"),
                    bciAvIds     = ids.toString(),   // serializzato come "[12335, 13070]"
                    partOfSpeech = obj.optString("partOfSpeech", "UNKNOWN"),
                    notes        = obj.optString("notes", "")
                )
            )
        }
        return result
    }
}

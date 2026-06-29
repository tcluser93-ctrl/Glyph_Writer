package com.example.egyptian_writer.bliss.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.egyptian_writer.bliss.data.BlissEntry
import com.example.egyptian_writer.bliss.data.BlissRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Stato UI del traduttore Bliss. */
sealed interface BlissUiState {
    /** Schermata iniziale — nessun input ancora. */
    object Idle : BlissUiState

    /** Ricerca in corso. */
    object Loading : BlissUiState

    /** Traduzione completata con successo. */
    data class Success(
        /** Token originali della frase (parole dopo tokenizzazione base). */
        val tokens: List<String>,
        /** Mappa token → lista di candidati BlissEntry ordinati per rilevanza. */
        val matches: Map<String, List<BlissEntry>>,
        /** Sequenza piatta di entry «migliori» per la striscia simboli. */
        val symbolStrip: List<BlissEntry?>
    ) : BlissUiState

    /** Input fornito ma nessuna corrispondenza trovata. */
    data class Empty(val query: String) : BlissUiState

    /** Errore generico. */
    data class Error(val message: String) : BlissUiState
}

@OptIn(FlowPreview::class)
class BlissTranslatorViewModel(
    private val repository: BlissRepository
) : ViewModel() {

    // ── Input dell'utente (testo grezzo) ──────────────────────────────────────
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // ── Stato UI esposto alla View ────────────────────────────────────────────
    private val _uiState = MutableStateFlow<BlissUiState>(BlissUiState.Idle)
    val uiState: StateFlow<BlissUiState> = _uiState.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────

    /** Aggiorna il testo di input (chiamato da TextWatcher). */
    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    /**
     * Avvia la traduzione della frase corrente.
     * Tokenizzazione: split su spazi + strip punteggiatura + lowercase.
     * Per ogni token cerca nel repository per corrispondenza esatta (IT),
     * poi per prefisso se nessun esatto trovato.
     */
    fun translate() {
        val raw = _inputText.value.trim()
        if (raw.isBlank()) {
            _uiState.value = BlissUiState.Idle
            return
        }

        viewModelScope.launch {
            _uiState.value = BlissUiState.Loading

            try {
                val tokens = tokenize(raw)
                val matches = mutableMapOf<String, List<BlissEntry>>()

                for (token in tokens) {
                    // 1. Esatta
                    var results = repository.findByWordIt(token)
                    // 2. Prefisso fallback
                    if (results.isEmpty()) {
                        results = repository.searchByPrefix(token)
                    }
                    matches[token] = results
                }

                val strip = tokens.map { token ->
                    matches[token]?.firstOrNull()
                }

                if (matches.values.all { it.isEmpty() }) {
                    _uiState.value = BlissUiState.Empty(raw)
                } else {
                    _uiState.value = BlissUiState.Success(
                        tokens = tokens,
                        matches = matches,
                        symbolStrip = strip
                    )
                }
            } catch (e: Exception) {
                _uiState.value = BlissUiState.Error(e.message ?: "Errore sconosciuto")
            }
        }
    }

    /** Pulisce input e stato. */
    fun clear() {
        _inputText.value = ""
        _uiState.value = BlissUiState.Idle
    }

    /**
     * Tokenizza la frase:
     * 1. Lowercase
     * 2. Rimuove punteggiatura (tutto ciò che non è lettera, cifra o spazio)
     * 3. Split su whitespace
     * 4. Filtra token vuoti
     */
    internal fun tokenize(sentence: String): List<String> =
        sentence
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    // ── Factory ───────────────────────────────────────────────────────────────
    class Factory(private val repository: BlissRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BlissTranslatorViewModel::class.java))
            return BlissTranslatorViewModel(repository) as T
        }
    }
}

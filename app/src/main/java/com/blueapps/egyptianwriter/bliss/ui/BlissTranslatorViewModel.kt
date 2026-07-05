package com.blueapps.egyptianwriter.bliss.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blueapps.egyptianwriter.bliss.data.BlissEntry
import com.blueapps.egyptianwriter.bliss.data.BlissRepository
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
        val tokens: List<String>,
        val matches: Map<String, List<BlissEntry>>,
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

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _uiState = MutableStateFlow<BlissUiState>(BlissUiState.Idle)
    val uiState: StateFlow<BlissUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

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
                    var results = repository.findByWordIt(token)
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

    fun clear() {
        _inputText.value = ""
        _uiState.value = BlissUiState.Idle
    }

    internal fun tokenize(sentence: String): List<String> =
        sentence
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    class Factory(private val repository: BlissRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BlissTranslatorViewModel::class.java))
            return BlissTranslatorViewModel(repository) as T
        }
    }
}

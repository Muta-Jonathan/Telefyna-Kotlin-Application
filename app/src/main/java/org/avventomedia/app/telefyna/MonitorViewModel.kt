package org.avventomedia.app.telefyna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MonitorViewModel : ViewModel() {
    data class UiState(
        val tickerText: String = "",
        val showOverlay: Boolean = false,
        val diagnostics: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun setTicker(text: String) {
        viewModelScope.launch { _uiState.emit(_uiState.value.copy(tickerText = text)) }
    }

    fun setOverlay(show: Boolean) {
        viewModelScope.launch { _uiState.emit(_uiState.value.copy(showOverlay = show)) }
    }

    fun setDiagnostics(text: String) {
        viewModelScope.launch { _uiState.emit(_uiState.value.copy(diagnostics = text)) }
    }
}

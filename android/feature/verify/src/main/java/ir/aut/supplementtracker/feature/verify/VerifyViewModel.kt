package ir.aut.supplementtracker.feature.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.data.AnalyticsStore
import ir.aut.supplementtracker.core.data.VerifyCacheStore
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ReportCounterfeitUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.model.QrPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VerifyViewModel(
    private val verifyProduct: VerifyProductUseCase,
    private val verifyCacheStore: VerifyCacheStore? = null,
    private val reportCounterfeit: ReportCounterfeitUseCase? = null,
    private val analyticsStore: AnalyticsStore? = null,
    private val analyticsEnabled: Boolean = true,
    reportsEnabled: Boolean = true,
    scanEnabled: Boolean = true,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            VerifyUiState(
                reportsEnabled = reportsEnabled,
                scanEnabled = scanEnabled,
            ),
        )
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VerifyUiEffect>()
    val effects: SharedFlow<VerifyUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: VerifyUiEvent) {
        when (event) {
            is VerifyUiEvent.InputChanged ->
                _state.update {
                    it.copy(
                        input = event.value,
                        errorMessage = null,
                        authenticityStatus = null,
                    )
                }
            VerifyUiEvent.Submit -> submit()
            VerifyUiEvent.ReportCounterfeit -> report()
            VerifyUiEvent.ScanQr ->
                viewModelScope.launch {
                    if (_state.value.scanEnabled) {
                        _effects.emit(VerifyUiEffect.NavigateToScan)
                    }
                }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isLoading) return
        val payload = QrPayload.parse(current.input.trim())
        val productId = payload?.productId ?: current.input.trim()
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, result = null) }
            runCatching { verifyProduct(productId) }
                .onSuccess { result ->
                    verifyCacheStore?.save(result)
                    if (analyticsEnabled) {
                        analyticsStore?.incrementVerify()
                    }
                    val status = result.toAuthenticityStatus()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            result = result,
                            authenticityStatus = status,
                            errorMessage = result.message,
                        )
                    }
                    _effects.emit(
                        VerifyUiEffect.ShowMessage(
                            result.message ?: status.name,
                        ),
                    )
                }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    val status = when (error) {
                        is DomainError.Network, is DomainError.RateLimited ->
                            AuthenticityStatus.NetworkError
                        else -> AuthenticityStatus.NotFound
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            authenticityStatus = status,
                            errorMessage = message,
                        )
                    }
                    _effects.emit(VerifyUiEffect.ShowMessage(message))
                }
        }
    }

    private fun report() {
        val current = _state.value
        val result = current.result ?: return
        val useCase = reportCounterfeit ?: return
        if (current.isReporting || !current.reportsEnabled) return
        viewModelScope.launch {
            _state.update { it.copy(isReporting = true) }
            runCatching {
                useCase(result.chainProductId)
            }.onSuccess {
                _state.update { it.copy(isReporting = false) }
                _effects.emit(VerifyUiEffect.ReportSubmitted)
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update { it.copy(isReporting = false, errorMessage = message) }
                _effects.emit(VerifyUiEffect.ShowMessage(message))
            }
        }
    }

    companion object {
        fun factory(
            verifyProduct: VerifyProductUseCase,
            verifyCacheStore: VerifyCacheStore? = null,
            reportCounterfeit: ReportCounterfeitUseCase? = null,
            analyticsStore: AnalyticsStore? = null,
            analyticsEnabled: Boolean = true,
            reportsEnabled: Boolean = true,
            scanEnabled: Boolean = true,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VerifyViewModel(
                        verifyProduct = verifyProduct,
                        verifyCacheStore = verifyCacheStore,
                        reportCounterfeit = reportCounterfeit,
                        analyticsStore = analyticsStore,
                        analyticsEnabled = analyticsEnabled,
                        reportsEnabled = reportsEnabled,
                        scanEnabled = scanEnabled,
                    ) as T
                }
            }
    }
}

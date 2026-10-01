package ir.aut.supplementtracker.feature.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.data.AnalyticsStore
import ir.aut.supplementtracker.core.data.ScanContextStore
import ir.aut.supplementtracker.core.data.VerifyCacheStore
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ReportCounterfeitUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.domain.VerifyUnitUseCase
import ir.aut.supplementtracker.core.model.ScannedCode
import ir.aut.supplementtracker.core.model.UnitRef
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
    private val verifyUnit: VerifyUnitUseCase? = null,
    private val verifyCacheStore: VerifyCacheStore? = null,
    private val reportCounterfeit: ReportCounterfeitUseCase? = null,
    private val analyticsStore: AnalyticsStore? = null,
    private val scanContextStore: ScanContextStore? = null,
    private val analyticsEnabled: Boolean = true,
    reportsEnabled: Boolean = true,
    scanEnabled: Boolean = true,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            VerifyUiState(
                reportsEnabled = reportsEnabled,
                scanEnabled = scanEnabled,
                region = scanContextStore?.region(),
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
                        hiddenLabelEntered = false,
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
            is VerifyUiEvent.RegionSelected -> {
                scanContextStore?.setRegion(event.region)
                _state.update { it.copy(region = event.region) }
            }
            VerifyUiEvent.RecordConsumption -> {
                val current = _state.value
                val unit = current.unit?.verification?.unit ?: return
                if (!current.canRecordConsumption) return
                viewModelScope.launch { _effects.emit(VerifyUiEffect.NavigateToConsume(unit)) }
            }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isLoading) return
        val raw = current.input.trim()
        when (val code = ScannedCode.parse(raw)) {
            is ScannedCode.Public -> submitUnit(code.ref, hidden = false)
            // The hidden code identifies its unit too; verify it, but warn that it is private.
            is ScannedCode.Hidden -> submitUnit(code.label.unit, hidden = true)
            is ScannedCode.Legacy -> submitLegacy(code.productId)
            null -> submitLegacy(raw)
        }
    }

    private fun submitUnit(unit: UnitRef, hidden: Boolean) {
        val useCase = verifyUnit ?: return submitLegacy(unit.path)
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = true, errorMessage = null, result = null, unit = null, hiddenLabelEntered = hidden)
            }
            runCatching { useCase(unit) }
                .onSuccess { verified ->
                    if (analyticsEnabled) analyticsStore?.incrementVerify()
                    val status = verified.toAuthenticityStatus()
                    _state.update { it.copy(isLoading = false, unit = verified, authenticityStatus = status) }
                    _effects.emit(VerifyUiEffect.ShowMessage(status.name))
                }
                .onFailure(::fail)
        }
    }

    private fun submitLegacy(productId: String) {
        if (productId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, result = null, unit = null) }
            runCatching { verifyProduct(productId) }
                .onSuccess { result ->
                    verifyCacheStore?.save(result)
                    if (analyticsEnabled) analyticsStore?.incrementVerify()
                    val status = result.toAuthenticityStatus()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            result = result,
                            authenticityStatus = status,
                            errorMessage = result.message,
                        )
                    }
                    _effects.emit(VerifyUiEffect.ShowMessage(result.message ?: status.name))
                }
                .onFailure(::fail)
        }
    }

    private fun fail(error: Throwable) {
        val message = ErrorMapper.toUserMessage(error)
        val status = when (error) {
            is DomainError.Network, is DomainError.RateLimited -> AuthenticityStatus.NetworkError
            else -> AuthenticityStatus.NotFound
        }
        _state.update { it.copy(isLoading = false, authenticityStatus = status, errorMessage = message) }
        viewModelScope.launch { _effects.emit(VerifyUiEffect.ShowMessage(message)) }
    }

    private fun report() {
        val current = _state.value
        val subject = current.unit?.verification?.unit?.path ?: current.result?.chainProductId ?: return
        val useCase = reportCounterfeit ?: return
        if (current.isReporting || !current.reportsEnabled) return
        viewModelScope.launch {
            _state.update { it.copy(isReporting = true) }
            runCatching {
                useCase(subject)
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
            verifyUnit: VerifyUnitUseCase? = null,
            verifyCacheStore: VerifyCacheStore? = null,
            reportCounterfeit: ReportCounterfeitUseCase? = null,
            analyticsStore: AnalyticsStore? = null,
            scanContextStore: ScanContextStore? = null,
            analyticsEnabled: Boolean = true,
            reportsEnabled: Boolean = true,
            scanEnabled: Boolean = true,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VerifyViewModel(
                        verifyProduct = verifyProduct,
                        verifyUnit = verifyUnit,
                        verifyCacheStore = verifyCacheStore,
                        reportCounterfeit = reportCounterfeit,
                        analyticsStore = analyticsStore,
                        scanContextStore = scanContextStore,
                        analyticsEnabled = analyticsEnabled,
                        reportsEnabled = reportsEnabled,
                        scanEnabled = scanEnabled,
                    ) as T
                }
            }
    }
}

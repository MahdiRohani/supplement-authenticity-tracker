package ir.aut.supplementtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.data.SessionStore
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.SupplementTopBar
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.core.domain.TransferProductUseCase
import ir.aut.supplementtracker.core.model.SupplyRole
import ir.aut.supplementtracker.core.model.UserSession
import ir.aut.supplementtracker.feature.history.HistoryScreen
import ir.aut.supplementtracker.feature.history.HistoryUiEffect
import ir.aut.supplementtracker.feature.history.HistoryViewModel
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterScreen
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterUiEffect
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterViewModel
import ir.aut.supplementtracker.feature.transfer.TransferScreen
import ir.aut.supplementtracker.feature.transfer.TransferUiEffect
import ir.aut.supplementtracker.feature.transfer.TransferViewModel
import kotlinx.coroutines.flow.collectLatest

private enum class AppDestination {
    Register,
    Transfer,
    History,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionStore = SessionStore(applicationContext)
        val repository = HttpProductRepository()
        val registerProduct = RegisterProductUseCase(repository)
        val transferProduct = TransferProductUseCase(repository)
        val getHistory = GetOwnershipHistoryUseCase(repository)

        setContent {
            SupplementTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var session by remember { mutableStateOf(sessionStore.read()) }
                var draftRole by remember {
                    mutableStateOf(session?.role ?: SupplyRole.Manufacturer)
                }
                var draftAddress by remember {
                    mutableStateOf(session?.address ?: defaultSessionFor(SupplyRole.Manufacturer).address)
                }
                var destination by remember { mutableStateOf(AppDestination.Register) }

                if (session == null) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        DevLoginScreen(
                            role = draftRole,
                            address = draftAddress,
                            onRoleSelected = { role ->
                                draftRole = role
                                draftAddress = defaultSessionFor(role).address
                            },
                            onAddressChanged = { draftAddress = it },
                            onContinue = {
                                val next = UserSession(role = draftRole, address = draftAddress)
                                sessionStore.save(next)
                                session = next
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                    return@SupplementTheme
                }

                val registerVm: ManufacturerRegisterViewModel =
                    viewModel(factory = ManufacturerRegisterViewModel.factory(registerProduct))
                val transferVm: TransferViewModel =
                    viewModel(factory = TransferViewModel.factory(transferProduct))
                val historyVm: HistoryViewModel =
                    viewModel(factory = HistoryViewModel.factory(getHistory))

                val registerState by registerVm.state.collectAsStateWithLifecycle()
                val transferState by transferVm.state.collectAsStateWithLifecycle()
                val historyState by historyVm.state.collectAsStateWithLifecycle()

                LaunchedEffect(registerVm) {
                    registerVm.effects.collectLatest { effect ->
                        if (effect is ManufacturerRegisterUiEffect.ShowMessage) {
                            snackbarHostState.showSnackbar(effect.message)
                        }
                    }
                }
                LaunchedEffect(transferVm) {
                    transferVm.effects.collectLatest { effect ->
                        if (effect is TransferUiEffect.ShowMessage) {
                            snackbarHostState.showSnackbar(effect.message)
                        }
                    }
                }
                LaunchedEffect(historyVm) {
                    historyVm.effects.collectLatest { effect ->
                        if (effect is HistoryUiEffect.ShowMessage) {
                            snackbarHostState.showSnackbar(effect.message)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SupplementTopBar(
                            title = stringResource(
                                R.string.session_title,
                                session!!.role.name,
                                session!!.address.take(10),
                            ),
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            AppDestination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Text(item.name.take(1)) },
                                    label = { Text(item.name) },
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    when (destination) {
                        AppDestination.Register ->
                            ManufacturerRegisterScreen(
                                state = registerState,
                                onEvent = registerVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                        AppDestination.Transfer ->
                            TransferScreen(
                                state = transferState,
                                onEvent = transferVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                        AppDestination.History ->
                            HistoryScreen(
                                state = historyState,
                                onEvent = historyVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                    }
                }
            }
        }
    }
}

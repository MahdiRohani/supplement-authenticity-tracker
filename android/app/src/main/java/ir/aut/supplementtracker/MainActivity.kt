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
import ir.aut.supplementtracker.core.blockchain.Web3jChainVerifier
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.data.SessionStore
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.SupplementTopBar
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.core.domain.TransferProductUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
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
import ir.aut.supplementtracker.feature.verify.VerifyScreen
import ir.aut.supplementtracker.feature.verify.VerifyUiEffect
import ir.aut.supplementtracker.feature.verify.VerifyViewModel
import kotlinx.coroutines.flow.collectLatest

private enum class AppDestination {
    Verify,
    Register,
    Transfer,
    History,
    Login,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionStore = SessionStore(applicationContext)
        val repository = HttpProductRepository()
        val chainVerifier = Web3jChainVerifier()
        val registerProduct = RegisterProductUseCase(repository)
        val transferProduct = TransferProductUseCase(repository)
        val getHistory = GetOwnershipHistoryUseCase(repository)
        val verifyProduct = VerifyProductUseCase(repository, chainVerifier)

        setContent {
            SupplementTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var session by remember { mutableStateOf(sessionStore.read()) }
                var draftRole by remember {
                    mutableStateOf(session?.role ?: SupplyRole.Manufacturer)
                }
                var draftAddress by remember {
                    mutableStateOf(
                        session?.address ?: defaultSessionFor(SupplyRole.Manufacturer).address,
                    )
                }
                var destination by remember { mutableStateOf(AppDestination.Verify) }

                val verifyVm: VerifyViewModel =
                    viewModel(factory = VerifyViewModel.factory(verifyProduct))
                val verifyState by verifyVm.state.collectAsStateWithLifecycle()

                LaunchedEffect(verifyVm) {
                    verifyVm.effects.collectLatest { effect ->
                        if (effect is VerifyUiEffect.ShowMessage) {
                            snackbarHostState.showSnackbar(effect.message)
                        }
                    }
                }

                val destinations =
                    if (session == null) {
                        listOf(AppDestination.Verify, AppDestination.Login)
                    } else {
                        listOf(
                            AppDestination.Verify,
                            AppDestination.Register,
                            AppDestination.Transfer,
                            AppDestination.History,
                        )
                    }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SupplementTopBar(
                            title = if (session == null) {
                                stringResource(R.string.app_name)
                            } else {
                                stringResource(
                                    R.string.session_title,
                                    session!!.role.name,
                                    session!!.address.take(10),
                                )
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            destinations.forEach { item ->
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
                        AppDestination.Verify ->
                            VerifyScreen(
                                state = verifyState,
                                onEvent = verifyVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                        AppDestination.Login ->
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
                                    destination = AppDestination.Register
                                },
                                modifier = Modifier.padding(innerPadding),
                            )
                        AppDestination.Register -> {
                            val registerVm: ManufacturerRegisterViewModel =
                                viewModel(
                                    factory = ManufacturerRegisterViewModel.factory(registerProduct),
                                )
                            val registerState by registerVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(registerVm) {
                                registerVm.effects.collectLatest { effect ->
                                    if (effect is ManufacturerRegisterUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                            ManufacturerRegisterScreen(
                                state = registerState,
                                onEvent = registerVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        AppDestination.Transfer -> {
                            val transferVm: TransferViewModel =
                                viewModel(factory = TransferViewModel.factory(transferProduct))
                            val transferState by transferVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(transferVm) {
                                transferVm.effects.collectLatest { effect ->
                                    if (effect is TransferUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                            TransferScreen(
                                state = transferState,
                                onEvent = transferVm::onEvent,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        AppDestination.History -> {
                            val historyVm: HistoryViewModel =
                                viewModel(factory = HistoryViewModel.factory(getHistory))
                            val historyState by historyVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(historyVm) {
                                historyVm.effects.collectLatest { effect ->
                                    if (effect is HistoryUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
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
}

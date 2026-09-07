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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import ir.aut.supplementtracker.core.blockchain.BuildConfig as BlockchainBuildConfig
import ir.aut.supplementtracker.core.blockchain.ManagedKeyChainWriter
import ir.aut.supplementtracker.core.blockchain.Web3jChainVerifier
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.data.SessionStore
import ir.aut.supplementtracker.core.data.VerifyCacheStore
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.SupplementTopBar
import ir.aut.supplementtracker.core.domain.ConsumeProductUseCase
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.ListProductsUseCase
import ir.aut.supplementtracker.core.domain.RegisterBatchUseCase
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.core.domain.TransferProductUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.model.SupplyRole
import ir.aut.supplementtracker.core.model.UserSession
import ir.aut.supplementtracker.feature.consume.ConsumeScreen
import ir.aut.supplementtracker.feature.consume.ConsumeUiEffect
import ir.aut.supplementtracker.feature.consume.ConsumeViewModel
import ir.aut.supplementtracker.feature.history.HistoryScreen
import ir.aut.supplementtracker.feature.history.HistoryUiEffect
import ir.aut.supplementtracker.feature.history.HistoryViewModel
import ir.aut.supplementtracker.feature.manufacturerdashboard.ManufacturerDashboardScreen
import ir.aut.supplementtracker.feature.manufacturerdashboard.ManufacturerDashboardUiEffect
import ir.aut.supplementtracker.feature.manufacturerdashboard.ManufacturerDashboardViewModel
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterScreen
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterUiEffect
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterViewModel
import ir.aut.supplementtracker.feature.stock.StockMode
import ir.aut.supplementtracker.feature.stock.StockScreen
import ir.aut.supplementtracker.feature.stock.StockUiEffect
import ir.aut.supplementtracker.feature.stock.StockViewModel
import ir.aut.supplementtracker.feature.transfer.TransferScreen
import ir.aut.supplementtracker.feature.transfer.TransferUiEffect
import ir.aut.supplementtracker.feature.transfer.TransferViewModel
import ir.aut.supplementtracker.feature.verify.VerifyScreen
import ir.aut.supplementtracker.feature.verify.VerifyUiEffect
import ir.aut.supplementtracker.feature.verify.VerifyUiEvent
import ir.aut.supplementtracker.feature.verify.VerifyViewModel
import kotlinx.coroutines.flow.collectLatest

object AppRoutes {
    const val VERIFY = "verify"
    const val VERIFY_WITH_ID = "verify/{productId}"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val DASHBOARD = "batchDashboard"
    const val TRANSFER = "transfer"
    const val CONSUME = "consume"
    const val HISTORY = "history"
    const val STOCK = "stock"
}

data class NavDestination(
    val route: String,
    val labelRes: Int,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionStore = SessionStore(applicationContext)
        val verifyCacheStore = VerifyCacheStore(applicationContext)
        val repository = HttpProductRepository()
        val chainVerifier = Web3jChainVerifier()
        val chainWriter =
            if (BlockchainBuildConfig.SIGNING_MODE == "managed" &&
                BlockchainBuildConfig.MANAGED_PRIVATE_KEY.isNotBlank()
            ) {
                ManagedKeyChainWriter()
            } else {
                null
            }
        val registerProduct = RegisterProductUseCase(repository)
        val registerBatch = RegisterBatchUseCase(repository)
        val listProducts = ListProductsUseCase(repository)
        val transferProduct = TransferProductUseCase(repository)
        val consumeProduct = ConsumeProductUseCase(repository, chainWriter)
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
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val destinations = remember(session) { destinationsFor(session) }

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
                                val label = stringResource(item.labelRes)
                                val selected =
                                    currentRoute == item.route ||
                                        (item.route == AppRoutes.VERIFY &&
                                            currentRoute?.startsWith("verify") == true)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) },
                                    modifier = Modifier.semantics {
                                        contentDescription = label
                                    },
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.VERIFY,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(AppRoutes.VERIFY) {
                            VerifyRoute(
                                verifyProduct = verifyProduct,
                                verifyCacheStore = verifyCacheStore,
                                snackbarHostState = snackbarHostState,
                                productId = null,
                            )
                        }
                        composable(
                            route = AppRoutes.VERIFY_WITH_ID,
                            arguments = listOf(
                                navArgument("productId") { type = NavType.StringType },
                            ),
                            deepLinks = listOf(
                                navDeepLink {
                                    uriPattern =
                                        "https://supplementtracker.aut.ir/verify/{productId}"
                                },
                                navDeepLink {
                                    uriPattern = "supplementtracker://verify/{productId}"
                                },
                            ),
                        ) { entry ->
                            VerifyRoute(
                                verifyProduct = verifyProduct,
                                verifyCacheStore = verifyCacheStore,
                                snackbarHostState = snackbarHostState,
                                productId = entry.arguments?.getString("productId"),
                            )
                        }
                        composable(AppRoutes.LOGIN) {
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
                                    val home = homeRouteFor(next.role)
                                    navController.navigate(home) {
                                        popUpTo(AppRoutes.LOGIN) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable(AppRoutes.REGISTER) {
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
                            )
                        }
                        composable(AppRoutes.DASHBOARD) {
                            val dashboardVm: ManufacturerDashboardViewModel =
                                viewModel(
                                    factory = ManufacturerDashboardViewModel.factory(
                                        listProducts = listProducts,
                                        registerBatch = registerBatch,
                                        ownerAddress = session?.address,
                                    ),
                                )
                            val dashboardState by dashboardVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(dashboardVm) {
                                dashboardVm.effects.collectLatest { effect ->
                                    if (effect is ManufacturerDashboardUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                            ManufacturerDashboardScreen(
                                state = dashboardState,
                                onEvent = dashboardVm::onEvent,
                            )
                        }
                        composable(AppRoutes.TRANSFER) {
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
                            )
                        }
                        composable(AppRoutes.CONSUME) {
                            val consumeVm: ConsumeViewModel =
                                viewModel(factory = ConsumeViewModel.factory(consumeProduct))
                            val consumeState by consumeVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(consumeVm) {
                                consumeVm.effects.collectLatest { effect ->
                                    if (effect is ConsumeUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                            ConsumeScreen(
                                state = consumeState,
                                onEvent = consumeVm::onEvent,
                            )
                        }
                        composable(AppRoutes.HISTORY) {
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
                            )
                        }
                        composable(AppRoutes.STOCK) {
                            val mode =
                                when (session?.role) {
                                    SupplyRole.Pharmacy -> StockMode.Pharmacy
                                    else -> StockMode.Distributor
                                }
                            val stockVm: StockViewModel =
                                viewModel(
                                    factory = StockViewModel.factory(
                                        listProducts = listProducts,
                                        mode = mode,
                                        ownerAddress = session?.address.orEmpty(),
                                    ),
                                )
                            val stockState by stockVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(stockVm) {
                                stockVm.effects.collectLatest { effect ->
                                    if (effect is StockUiEffect.ShowMessage) {
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                            StockScreen(
                                state = stockState,
                                onEvent = stockVm::onEvent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifyRoute(
    verifyProduct: VerifyProductUseCase,
    verifyCacheStore: VerifyCacheStore,
    snackbarHostState: SnackbarHostState,
    productId: String?,
) {
    val verifyVm: VerifyViewModel =
        viewModel(factory = VerifyViewModel.factory(verifyProduct, verifyCacheStore))
    val verifyState by verifyVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(productId) {
        if (!productId.isNullOrBlank()) {
            verifyVm.onEvent(VerifyUiEvent.InputChanged(productId))
        }
    }
    LaunchedEffect(verifyVm) {
        verifyVm.effects.collectLatest { effect ->
            if (effect is VerifyUiEffect.ShowMessage) {
                snackbarHostState.showSnackbar(effect.message)
            }
        }
    }
    VerifyScreen(
        state = verifyState,
        onEvent = verifyVm::onEvent,
    )
}

private fun destinationsFor(session: UserSession?): List<NavDestination> {
    if (session == null) {
        return listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify),
            NavDestination(AppRoutes.LOGIN, R.string.nav_login),
        )
    }
    return when (session.role) {
        SupplyRole.Manufacturer -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify),
            NavDestination(AppRoutes.DASHBOARD, R.string.nav_dashboard),
            NavDestination(AppRoutes.REGISTER, R.string.nav_register),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history),
        )
        SupplyRole.Distributor -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history),
        )
        SupplyRole.Pharmacy -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock),
            NavDestination(AppRoutes.CONSUME, R.string.nav_consume),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history),
        )
        SupplyRole.Admin -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify),
            NavDestination(AppRoutes.DASHBOARD, R.string.nav_dashboard),
            NavDestination(AppRoutes.REGISTER, R.string.nav_register),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer),
            NavDestination(AppRoutes.CONSUME, R.string.nav_consume),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history),
        )
    }
}

private fun homeRouteFor(role: SupplyRole): String =
    when (role) {
        SupplyRole.Manufacturer, SupplyRole.Admin -> AppRoutes.DASHBOARD
        SupplyRole.Distributor, SupplyRole.Pharmacy -> AppRoutes.STOCK
    }

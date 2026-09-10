package ir.aut.supplementtracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
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
import ir.aut.supplementtracker.core.data.AnalyticsStore
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.data.SessionStore
import ir.aut.supplementtracker.core.data.VerifyCacheStore
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.SupplementTopBar
import ir.aut.supplementtracker.core.designsystem.localizeErrorMessage
import ir.aut.supplementtracker.core.domain.ConsumeProductUseCase
import ir.aut.supplementtracker.core.domain.DownloadBatchLabelsPdfUseCase
import ir.aut.supplementtracker.core.domain.GetFeatureFlagsUseCase
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.ListProductsUseCase
import ir.aut.supplementtracker.core.domain.RegisterBatchUseCase
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.core.domain.ReportCounterfeitUseCase
import ir.aut.supplementtracker.core.domain.TransferProductUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.model.FeatureFlags
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
import ir.aut.supplementtracker.feature.scan.ScanScreen
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
import java.io.File
import kotlinx.coroutines.flow.collectLatest

object AppRoutes {
    const val VERIFY = "verify"
    const val VERIFY_WITH_ID = "verify/{productId}"
    const val SCAN = "scan"
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
    val icon: ImageVector,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionStore = SessionStore(applicationContext)
        val verifyCacheStore = VerifyCacheStore(applicationContext)
        val analyticsStore = AnalyticsStore(applicationContext)
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
        val getFeatureFlags = GetFeatureFlagsUseCase(repository)
        val reportCounterfeit = ReportCounterfeitUseCase(repository)
        val downloadBatchLabelsPdf = DownloadBatchLabelsPdfUseCase(repository)

        setContent {
            SupplementTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var session by remember { mutableStateOf(sessionStore.read()) }
                var featureFlags by remember { mutableStateOf(FeatureFlags()) }
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

                LaunchedEffect(Unit) {
                    featureFlags = runCatching { getFeatureFlags() }.getOrElse { FeatureFlags() }
                }

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
                            actions = {
                                if (session != null) {
                                    val logoutCd = stringResource(R.string.action_logout)
                                    IconButton(
                                        onClick = {
                                            sessionStore.clear()
                                            session = null
                                            draftRole = SupplyRole.Manufacturer
                                            draftAddress =
                                                defaultSessionFor(SupplyRole.Manufacturer).address
                                            navController.navigate(AppRoutes.VERIFY) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    inclusive = true
                                                }
                                                launchSingleTop = true
                                            }
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = logoutCd
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Logout,
                                            contentDescription = logoutCd,
                                        )
                                    }
                                }
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
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = label,
                                        )
                                    },
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
                        composable(AppRoutes.VERIFY) { entry ->
                            val scannedProductId by entry.savedStateHandle
                                .getStateFlow<String?>("scannedProductId", null)
                                .collectAsStateWithLifecycle()
                            VerifyRoute(
                                verifyProduct = verifyProduct,
                                verifyCacheStore = verifyCacheStore,
                                reportCounterfeit = reportCounterfeit,
                                analyticsStore = analyticsStore,
                                featureFlags = featureFlags,
                                snackbarHostState = snackbarHostState,
                                productId = null,
                                onNavigateToScan = {
                                    navController.navigate(AppRoutes.SCAN)
                                },
                                scannedProductId = scannedProductId,
                                onScannedConsumed = {
                                    entry.savedStateHandle.remove<String>("scannedProductId")
                                },
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
                                reportCounterfeit = reportCounterfeit,
                                analyticsStore = analyticsStore,
                                featureFlags = featureFlags,
                                snackbarHostState = snackbarHostState,
                                productId = entry.arguments?.getString("productId"),
                                onNavigateToScan = {
                                    navController.navigate(AppRoutes.SCAN)
                                },
                                scannedProductId = null,
                                onScannedConsumed = {},
                            )
                        }
                        composable(AppRoutes.SCAN) {
                            ScanScreen(
                                onDetected = { productId ->
                                    if (featureFlags.analyticsEnabled) {
                                        analyticsStore.incrementScan()
                                    }
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("scannedProductId", productId)
                                    navController.popBackStack()
                                },
                                onClose = { navController.popBackStack() },
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
                            val context = LocalContext.current
                            val registerVm: ManufacturerRegisterViewModel =
                                viewModel(
                                    factory = ManufacturerRegisterViewModel.factory(registerProduct),
                                )
                            val registerState by registerVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(registerVm) {
                                registerVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is ManufacturerRegisterUiEffect.Registered ->
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    ir.aut.supplementtracker.feature.manufacturerregister.R.string.register_success,
                                                    effect.chainProductId,
                                                ),
                                            )
                                        is ManufacturerRegisterUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                    }
                                }
                            }
                            ManufacturerRegisterScreen(
                                state = registerState,
                                onEvent = registerVm::onEvent,
                            )
                        }
                        composable(AppRoutes.DASHBOARD) {
                            val context = LocalContext.current
                            val dashboardVm: ManufacturerDashboardViewModel =
                                viewModel(
                                    key = "dashboard-${featureFlags.labelsPdfEnabled}-${featureFlags.analyticsEnabled}-${analyticsStore.verifyCount()}-${analyticsStore.scanCount()}",
                                    factory = ManufacturerDashboardViewModel.factory(
                                        listProducts = listProducts,
                                        registerBatch = registerBatch,
                                        downloadBatchLabelsPdf = downloadBatchLabelsPdf,
                                        ownerAddress = session?.address,
                                        labelsPdfEnabled = featureFlags.labelsPdfEnabled,
                                        analyticsEnabled = featureFlags.analyticsEnabled,
                                        analyticsVerifyCount = analyticsStore.verifyCount(),
                                        analyticsScanCount = analyticsStore.scanCount(),
                                    ),
                                )
                            val dashboardState by dashboardVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(dashboardVm) {
                                dashboardVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is ManufacturerDashboardUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                        is ManufacturerDashboardUiEffect.BatchRegistered ->
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    ir.aut.supplementtracker.feature.manufacturerdashboard.R.string.dashboard_batch_registered,
                                                    effect.count,
                                                ),
                                            )
                                        is ManufacturerDashboardUiEffect.SharePdf -> {
                                            val file =
                                                File(
                                                    context.cacheDir,
                                                    "labels-${effect.batchCode}.pdf",
                                                )
                                            file.writeBytes(effect.bytes)
                                            val uri =
                                                FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file,
                                                )
                                            val share =
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/pdf"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                            context.startActivity(
                                                Intent.createChooser(share, null),
                                            )
                                        }
                                    }
                                }
                            }
                            ManufacturerDashboardScreen(
                                state = dashboardState,
                                onEvent = dashboardVm::onEvent,
                            )
                        }
                        composable(AppRoutes.TRANSFER) {
                            val context = LocalContext.current
                            val transferVm: TransferViewModel =
                                viewModel(factory = TransferViewModel.factory(transferProduct))
                            val transferState by transferVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(transferVm) {
                                transferVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is TransferUiEffect.Transferred ->
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    ir.aut.supplementtracker.feature.transfer.R.string.transfer_success,
                                                    effect.txHash,
                                                ),
                                            )
                                        is TransferUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                    }
                                }
                            }
                            TransferScreen(
                                state = transferState,
                                onEvent = transferVm::onEvent,
                            )
                        }
                        composable(AppRoutes.CONSUME) {
                            val context = LocalContext.current
                            val consumeVm: ConsumeViewModel =
                                viewModel(factory = ConsumeViewModel.factory(consumeProduct))
                            val consumeState by consumeVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(consumeVm) {
                                consumeVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is ConsumeUiEffect.Consumed ->
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    ir.aut.supplementtracker.feature.consume.R.string.consume_success,
                                                    effect.chainProductId,
                                                ),
                                            )
                                        is ConsumeUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                    }
                                }
                            }
                            ConsumeScreen(
                                state = consumeState,
                                onEvent = consumeVm::onEvent,
                            )
                        }
                        composable(AppRoutes.HISTORY) {
                            val context = LocalContext.current
                            val historyVm: HistoryViewModel =
                                viewModel(factory = HistoryViewModel.factory(getHistory))
                            val historyState by historyVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(historyVm) {
                                historyVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is HistoryUiEffect.Loaded ->
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    ir.aut.supplementtracker.feature.history.R.string.history_elapsed,
                                                    effect.elapsedMs.toInt(),
                                                ),
                                            )
                                        is HistoryUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                    }
                                }
                            }
                            HistoryScreen(
                                state = historyState,
                                onEvent = historyVm::onEvent,
                            )
                        }
                        composable(AppRoutes.STOCK) {
                            val context = LocalContext.current
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
                                        snackbarHostState.showSnackbar(
                                            localizeErrorMessage(context, effect.message)
                                                ?: effect.message,
                                        )
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
    reportCounterfeit: ReportCounterfeitUseCase,
    analyticsStore: AnalyticsStore,
    featureFlags: FeatureFlags,
    snackbarHostState: SnackbarHostState,
    productId: String?,
    onNavigateToScan: () -> Unit,
    scannedProductId: String?,
    onScannedConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val verifyVm: VerifyViewModel =
        viewModel(
            key = "verify-${featureFlags.reportsEnabled}-${featureFlags.scanEnabled}-${featureFlags.analyticsEnabled}",
            factory = VerifyViewModel.factory(
                verifyProduct = verifyProduct,
                verifyCacheStore = verifyCacheStore,
                reportCounterfeit = reportCounterfeit,
                analyticsStore = analyticsStore,
                analyticsEnabled = featureFlags.analyticsEnabled,
                reportsEnabled = featureFlags.reportsEnabled,
                scanEnabled = featureFlags.scanEnabled,
            ),
        )
    val verifyState by verifyVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(productId) {
        if (!productId.isNullOrBlank()) {
            verifyVm.onEvent(VerifyUiEvent.InputChanged(productId))
        }
    }
    LaunchedEffect(scannedProductId) {
        if (!scannedProductId.isNullOrBlank()) {
            verifyVm.onEvent(VerifyUiEvent.InputChanged(scannedProductId))
            onScannedConsumed()
        }
    }
    LaunchedEffect(verifyVm) {
        verifyVm.effects.collectLatest { effect ->
            when (effect) {
                is VerifyUiEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(
                        localizeErrorMessage(context, effect.message) ?: effect.message,
                    )
                VerifyUiEffect.ReportSubmitted ->
                    snackbarHostState.showSnackbar(
                        context.getString(ir.aut.supplementtracker.feature.verify.R.string.verify_report_submitted),
                    )
                VerifyUiEffect.NavigateToScan -> onNavigateToScan()
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
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify, Icons.Filled.Verified),
            NavDestination(AppRoutes.LOGIN, R.string.nav_login, Icons.Filled.Login),
        )
    }
    return when (session.role) {
        SupplyRole.Manufacturer -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify, Icons.Filled.Verified),
            NavDestination(AppRoutes.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dashboard),
            NavDestination(AppRoutes.REGISTER, R.string.nav_register, Icons.Filled.QrCodeScanner),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer, Icons.Filled.SwapHoriz),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history, Icons.AutoMirrored.Filled.List),
        )
        SupplyRole.Distributor -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify, Icons.Filled.Verified),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock, Icons.Filled.Inventory2),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer, Icons.Filled.SwapHoriz),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history, Icons.AutoMirrored.Filled.List),
        )
        SupplyRole.Pharmacy -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify, Icons.Filled.Verified),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock, Icons.Filled.Inventory2),
            NavDestination(AppRoutes.CONSUME, R.string.nav_consume, Icons.Filled.LocalPharmacy),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history, Icons.AutoMirrored.Filled.List),
        )
        SupplyRole.Admin -> listOf(
            NavDestination(AppRoutes.VERIFY, R.string.nav_verify, Icons.Filled.Verified),
            NavDestination(AppRoutes.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dashboard),
            NavDestination(AppRoutes.REGISTER, R.string.nav_register, Icons.Filled.QrCodeScanner),
            NavDestination(AppRoutes.STOCK, R.string.nav_stock, Icons.Filled.Inventory2),
            NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer, Icons.Filled.SwapHoriz),
            NavDestination(AppRoutes.CONSUME, R.string.nav_consume, Icons.Filled.LocalPharmacy),
            NavDestination(AppRoutes.HISTORY, R.string.nav_history, Icons.AutoMirrored.Filled.List),
        )
    }
}

private fun homeRouteFor(role: SupplyRole): String =
    when (role) {
        SupplyRole.Manufacturer, SupplyRole.Admin -> AppRoutes.DASHBOARD
        SupplyRole.Distributor, SupplyRole.Pharmacy -> AppRoutes.STOCK
    }

package ir.aut.supplementtracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import ir.aut.supplementtracker.core.blockchain.ConsumerKeyStore
import ir.aut.supplementtracker.core.blockchain.Eip712ConsumeSigner
import ir.aut.supplementtracker.core.blockchain.UnitMerkleVerifier
import ir.aut.supplementtracker.core.blockchain.Web3jChainAnchorReader
import ir.aut.supplementtracker.core.blockchain.Web3jChainVerifier
import ir.aut.supplementtracker.core.data.AnalyticsStore
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.data.HttpProtocolRepository
import ir.aut.supplementtracker.core.data.ScanContextStore
import ir.aut.supplementtracker.core.data.SessionStore
import ir.aut.supplementtracker.core.data.VerifyCacheStore
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.SupplementTopBar
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizeErrorMessage
import ir.aut.supplementtracker.core.domain.ConsumeUnitUseCase
import ir.aut.supplementtracker.core.domain.GetBatchDetailUseCase
import ir.aut.supplementtracker.core.domain.GetFeatureFlagsUseCase
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.GetUnitHistoryUseCase
import ir.aut.supplementtracker.core.domain.ListBatchesUseCase
import ir.aut.supplementtracker.core.domain.ListCustodySegmentsUseCase
import ir.aut.supplementtracker.core.domain.RegisterUnitBatchUseCase
import ir.aut.supplementtracker.core.domain.RenderBatchLabelsUseCase
import ir.aut.supplementtracker.core.domain.ReportCounterfeitUseCase
import ir.aut.supplementtracker.core.domain.TransferSegmentUseCase
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.domain.VerifyUnitUseCase
import ir.aut.supplementtracker.core.model.FeatureFlags
import ir.aut.supplementtracker.core.model.SupplyRole
import ir.aut.supplementtracker.core.model.UnitRef
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
import ir.aut.supplementtracker.feature.consume.ConsumeUiEvent
import ir.aut.supplementtracker.feature.scan.ScanMode
import ir.aut.supplementtracker.feature.scan.ScanScreen
import ir.aut.supplementtracker.feature.stock.StockMode
import ir.aut.supplementtracker.feature.stock.StockScreen
import ir.aut.supplementtracker.feature.stock.StockUiEffect
import ir.aut.supplementtracker.feature.stock.StockViewModel
import ir.aut.supplementtracker.feature.transfer.RecipientSuggestion
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
    const val UNIT = "u/{chainId}/{batchId}/{index}"
    const val SCAN = "scan"
    const val SCAN_PATTERN = "scan?mode={mode}"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val DASHBOARD = "batchDashboard"
    const val TRANSFER = "transfer"
    const val TRANSFER_PATTERN = "transfer?segment={segment}"
    const val CONSUME = "consume"
    const val CONSUME_PATTERN = "consume?unit={unit}"
    const val HISTORY = "history"
    const val STOCK = "stock"

    /** Default of the backend's `PUBLIC_VERIFY_BASE_URL`; printed on every open label. */
    const val PUBLIC_UNIT_URL = "https://supplementtracker.aut.ir/u/{chainId}/{batchId}/{index}"

    /** Shared by the scanner and whichever screen opened it. */
    const val SCANNED_CODE = "scannedCode"

    fun scan(mode: ScanMode): String = "$SCAN?mode=${mode.name}"

    fun consume(unit: UnitRef?): String =
        if (unit == null) CONSUME else "$CONSUME?unit=${Uri.encode(unit.path)}"

    fun transfer(segmentId: String?): String =
        if (segmentId == null) TRANSFER else "$TRANSFER?segment=${Uri.encode(segmentId)}"
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
        val scanContextStore = ScanContextStore(applicationContext)
        val consumerKeyStore = ConsumerKeyStore(applicationContext)

        // v1: legacy single-product labels still in circulation, flags and reports.
        val repository = HttpProductRepository()
        val getHistory = GetOwnershipHistoryUseCase(repository)
        val verifyProduct = VerifyProductUseCase(repository, Web3jChainVerifier())
        val getFeatureFlags = GetFeatureFlagsUseCase(repository)
        val reportCounterfeit = ReportCounterfeitUseCase(repository)

        // v2: Merkle batches, custody segments, gasless consumption, clone risk.
        val protocol = HttpProtocolRepository()
        val verifyUnit =
            VerifyUnitUseCase(
                repository = protocol,
                scanContext = scanContextStore,
                proofVerifier = UnitMerkleVerifier(),
                anchorReader = Web3jChainAnchorReader(),
            )
        val consumeUnit = ConsumeUnitUseCase(protocol, Eip712ConsumeSigner(), consumerKeyStore)
        val registerUnitBatch = RegisterUnitBatchUseCase(protocol)
        val renderBatchLabels = RenderBatchLabelsUseCase(protocol)
        val listBatches = ListBatchesUseCase(protocol)
        val getBatchDetail = GetBatchDetailUseCase(protocol)
        val listSegments = ListCustodySegmentsUseCase(protocol)
        val transferSegment = TransferSegmentUseCase(protocol)
        val getUnitHistory = GetUnitHistoryUseCase(protocol)
        val verifyDeps =
            VerifyDeps(
                verifyProduct = verifyProduct,
                verifyUnit = verifyUnit,
                verifyCacheStore = verifyCacheStore,
                reportCounterfeit = reportCounterfeit,
                analyticsStore = analyticsStore,
                scanContextStore = scanContextStore,
            )

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
                val currentRoute = navBackStackEntry?.destination?.route?.substringBefore('?')

                LaunchedEffect(Unit) {
                    featureFlags = runCatching { getFeatureFlags() }.getOrElse { FeatureFlags() }
                }

                val destinations = remember(session) { destinationsFor(session) }
                // The scanner draws edge-to-edge over the camera feed.
                val fullScreen = currentRoute == AppRoutes.SCAN

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = if (fullScreen) {
                        WindowInsets(0)
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    },
                    topBar = topBar@{
                        if (fullScreen) return@topBar
                        val current = session
                        SupplementTopBar(
                            title = current?.let { stringResource(it.role.labelRes) }
                                ?: stringResource(R.string.app_name),
                            subtitle = current?.address?.shortenMiddle()
                                ?: stringResource(R.string.app_tagline),
                            icon = current?.role?.icon ?: SupplementIcons.Verify,
                            actions = {
                                if (current != null) {
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
                                            imageVector = SupplementIcons.Logout,
                                            contentDescription = logoutCd,
                                        )
                                    }
                                }
                            },
                        )
                    },
                    bottomBar = bottomBar@{
                        if (fullScreen) return@bottomBar
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            destinations.forEach { item ->
                                val label = stringResource(item.labelRes)
                                val selected =
                                    currentRoute == item.route ||
                                        (item.route == AppRoutes.VERIFY &&
                                            (currentRoute?.startsWith("verify") == true ||
                                                currentRoute == AppRoutes.UNIT))
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
                                    label = { Text(label, maxLines = 1) },
                                    alwaysShowLabel = destinations.size <= 5,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
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
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    ) {
                        composable(AppRoutes.VERIFY) { entry ->
                            VerifyRoute(
                                entry = entry,
                                deps = verifyDeps,
                                featureFlags = featureFlags,
                                snackbarHostState = snackbarHostState,
                                initialCode = null,
                                onNavigateToScan = { navController.navigate(AppRoutes.scan(ScanMode.PublicLabel)) },
                                onNavigateToConsume = { navController.navigate(AppRoutes.consume(it)) },
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
                                entry = entry,
                                deps = verifyDeps,
                                featureFlags = featureFlags,
                                snackbarHostState = snackbarHostState,
                                initialCode = entry.arguments?.getString("productId"),
                                onNavigateToScan = { navController.navigate(AppRoutes.scan(ScanMode.PublicLabel)) },
                                onNavigateToConsume = { navController.navigate(AppRoutes.consume(it)) },
                            )
                        }
                        composable(
                            route = AppRoutes.UNIT,
                            arguments = listOf(
                                navArgument("chainId") { type = NavType.StringType },
                                navArgument("batchId") { type = NavType.StringType },
                                navArgument("index") { type = NavType.StringType },
                            ),
                            deepLinks = listOf(navDeepLink { uriPattern = AppRoutes.PUBLIC_UNIT_URL }),
                        ) { entry ->
                            val args = entry.arguments
                            val code = listOf("chainId", "batchId", "index")
                                .map { args?.getString(it).orEmpty() }
                                .joinToString("/")
                            VerifyRoute(
                                entry = entry,
                                deps = verifyDeps,
                                featureFlags = featureFlags,
                                snackbarHostState = snackbarHostState,
                                initialCode = code,
                                onNavigateToScan = { navController.navigate(AppRoutes.scan(ScanMode.PublicLabel)) },
                                onNavigateToConsume = { navController.navigate(AppRoutes.consume(it)) },
                            )
                        }
                        composable(
                            route = AppRoutes.SCAN_PATTERN,
                            arguments = listOf(
                                navArgument("mode") {
                                    type = NavType.StringType
                                    defaultValue = ScanMode.PublicLabel.name
                                },
                            ),
                        ) { entry ->
                            val mode = entry.arguments?.getString("mode")
                                ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() }
                                ?: ScanMode.PublicLabel
                            ScanScreen(
                                mode = mode,
                                onDetected = { raw ->
                                    if (featureFlags.analyticsEnabled) {
                                        analyticsStore.incrementScan()
                                    }
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set(AppRoutes.SCANNED_CODE, raw)
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
                                    val next = UserSession(role = draftRole, address = draftAddress.trim())
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
                            val resources = LocalResources.current
                            val registerVm: ManufacturerRegisterViewModel =
                                viewModel(
                                    key = "register-${session?.address}-${featureFlags.labelsPdfEnabled}",
                                    factory = ManufacturerRegisterViewModel.factory(
                                        registerBatch = registerUnitBatch,
                                        renderLabels = renderBatchLabels,
                                        manufacturerAddress = session?.address,
                                        labelsPdfEnabled = featureFlags.labelsPdfEnabled,
                                    ),
                                )
                            val registerState by registerVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(registerVm) {
                                registerVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is ManufacturerRegisterUiEffect.Registered ->
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    ir.aut.supplementtracker.feature.manufacturerregister.R.string.register_success,
                                                    effect.batchId,
                                                    effect.size,
                                                ),
                                            )
                                        is ManufacturerRegisterUiEffect.ShareLabels ->
                                            sharePdf(context, effect.bytes, effect.fileName)
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
                                    key = "dashboard-${session?.address}-${featureFlags.analyticsEnabled}-${analyticsStore.verifyCount()}-${analyticsStore.scanCount()}",
                                    factory = ManufacturerDashboardViewModel.factory(
                                        listBatches = listBatches,
                                        getBatchDetail = getBatchDetail,
                                        manufacturerAddress = session?.address?.takeIf {
                                            session?.role != SupplyRole.Admin
                                        },
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
                                        ManufacturerDashboardUiEffect.NavigateToRegister ->
                                            navController.navigate(AppRoutes.REGISTER) { launchSingleTop = true }
                                    }
                                }
                            }
                            ManufacturerDashboardScreen(
                                state = dashboardState,
                                onEvent = dashboardVm::onEvent,
                            )
                        }
                        composable(
                            route = AppRoutes.TRANSFER_PATTERN,
                            arguments = listOf(
                                navArgument("segment") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) { entry ->
                            val context = LocalContext.current
                            val resources = LocalResources.current
                            val segment = entry.arguments?.getString("segment")
                            val owner = session?.address.orEmpty()
                            val transferVm: TransferViewModel =
                                viewModel(
                                    key = "transfer-$owner-$segment",
                                    factory = TransferViewModel.factory(
                                        listSegments = listSegments,
                                        transferSegment = transferSegment,
                                        ownerAddress = owner,
                                        initialSegmentId = segment,
                                    ),
                                )
                            val transferState by transferVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(transferVm) {
                                transferVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is TransferUiEffect.Transferred ->
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    ir.aut.supplementtracker.feature.transfer.R.string.transfer_success,
                                                    effect.txHash.shortenMiddle(10, 6),
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
                            val suggestionRoles = listOf(SupplyRole.Distributor, SupplyRole.Pharmacy)
                            TransferScreen(
                                state = transferState,
                                onEvent = transferVm::onEvent,
                                recipientSuggestions = suggestionRoles
                                    .map { role ->
                                        RecipientSuggestion(
                                            label = stringResource(role.labelRes),
                                            address = defaultSessionFor(role).address,
                                            icon = role.icon,
                                        )
                                    }
                                    .filterNot { it.address.equals(session?.address, ignoreCase = true) },
                            )
                        }
                        composable(
                            route = AppRoutes.CONSUME_PATTERN,
                            arguments = listOf(
                                navArgument("unit") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) { entry ->
                            val context = LocalContext.current
                            val resources = LocalResources.current
                            val unitArg = entry.arguments?.getString("unit")
                            val expected = unitArg?.let(UnitRef::parse)
                            val consumeVm: ConsumeViewModel =
                                viewModel(
                                    key = "consume-${expected?.path}-${featureFlags.scanEnabled}",
                                    factory = ConsumeViewModel.factory(
                                        consumeUnit = consumeUnit,
                                        expected = expected,
                                        scanEnabled = featureFlags.scanEnabled,
                                    ),
                                )
                            val consumeState by consumeVm.state.collectAsStateWithLifecycle()
                            val scanned by entry.savedStateHandle
                                .getStateFlow<String?>(AppRoutes.SCANNED_CODE, null)
                                .collectAsStateWithLifecycle()
                            LaunchedEffect(scanned) {
                                scanned?.let {
                                    consumeVm.onEvent(ConsumeUiEvent.SecretChanged(it))
                                    entry.savedStateHandle.remove<String>(AppRoutes.SCANNED_CODE)
                                }
                            }
                            LaunchedEffect(consumeVm) {
                                consumeVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is ConsumeUiEffect.Consumed ->
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    ir.aut.supplementtracker.feature.consume.R.string.consume_success,
                                                    effect.unit.path,
                                                ),
                                            )
                                        ConsumeUiEffect.NavigateToScan ->
                                            navController.navigate(AppRoutes.scan(ScanMode.HiddenLabel))
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
                            val resources = LocalResources.current
                            val historyVm: HistoryViewModel =
                                viewModel(factory = HistoryViewModel.factory(getHistory, getUnitHistory))
                            val historyState by historyVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(historyVm) {
                                historyVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is HistoryUiEffect.Loaded ->
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
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
                            val owner = session?.address.orEmpty()
                            val stockVm: StockViewModel =
                                viewModel(
                                    key = "stock-$owner-$mode",
                                    factory = StockViewModel.factory(
                                        listSegments = listSegments,
                                        mode = mode,
                                        ownerAddress = owner,
                                    ),
                                )
                            val stockState by stockVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(stockVm) {
                                stockVm.effects.collectLatest { effect ->
                                    when (effect) {
                                        is StockUiEffect.ShowMessage ->
                                            snackbarHostState.showSnackbar(
                                                localizeErrorMessage(context, effect.message)
                                                    ?: effect.message,
                                            )
                                        is StockUiEffect.NavigateToTransfer ->
                                            navController.navigate(AppRoutes.transfer(effect.segmentId))
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

/** Everything the verify screens need; one instance shared by the plain, legacy and unit routes. */
private class VerifyDeps(
    val verifyProduct: VerifyProductUseCase,
    val verifyUnit: VerifyUnitUseCase,
    val verifyCacheStore: VerifyCacheStore,
    val reportCounterfeit: ReportCounterfeitUseCase,
    val analyticsStore: AnalyticsStore,
    val scanContextStore: ScanContextStore,
)

@Composable
private fun VerifyRoute(
    entry: NavBackStackEntry,
    deps: VerifyDeps,
    featureFlags: FeatureFlags,
    snackbarHostState: SnackbarHostState,
    initialCode: String?,
    onNavigateToScan: () -> Unit,
    onNavigateToConsume: (UnitRef) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val verifyVm: VerifyViewModel =
        viewModel(
            key = "verify-${featureFlags.reportsEnabled}-${featureFlags.scanEnabled}-${featureFlags.analyticsEnabled}",
            factory = VerifyViewModel.factory(
                verifyProduct = deps.verifyProduct,
                verifyUnit = deps.verifyUnit,
                verifyCacheStore = deps.verifyCacheStore,
                reportCounterfeit = deps.reportCounterfeit,
                analyticsStore = deps.analyticsStore,
                scanContextStore = deps.scanContextStore,
                analyticsEnabled = featureFlags.analyticsEnabled,
                reportsEnabled = featureFlags.reportsEnabled,
                scanEnabled = featureFlags.scanEnabled,
            ),
        )
    val verifyState by verifyVm.state.collectAsStateWithLifecycle()
    val scanned by entry.savedStateHandle
        .getStateFlow<String?>(AppRoutes.SCANNED_CODE, null)
        .collectAsStateWithLifecycle()
    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank()) {
            verifyVm.onEvent(VerifyUiEvent.InputChanged(initialCode))
            verifyVm.onEvent(VerifyUiEvent.Submit)
        }
    }
    LaunchedEffect(scanned) {
        scanned?.let {
            verifyVm.onEvent(VerifyUiEvent.InputChanged(it))
            verifyVm.onEvent(VerifyUiEvent.Submit)
            entry.savedStateHandle.remove<String>(AppRoutes.SCANNED_CODE)
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
                        resources.getString(ir.aut.supplementtracker.feature.verify.R.string.verify_report_submitted),
                    )
                VerifyUiEffect.NavigateToScan -> onNavigateToScan()
                is VerifyUiEffect.NavigateToConsume -> onNavigateToConsume(effect.unit)
            }
        }
    }
    VerifyScreen(
        state = verifyState,
        onEvent = verifyVm::onEvent,
    )
}

private fun sharePdf(context: Context, bytes: ByteArray, name: String) {
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val file = File(context.cacheDir, "$safeName.pdf")
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(share, null))
}

private fun destinationsFor(session: UserSession?): List<NavDestination> {
    val verify = NavDestination(AppRoutes.VERIFY, R.string.nav_verify, SupplementIcons.Verify)
    val dashboard = NavDestination(AppRoutes.DASHBOARD, R.string.nav_dashboard, SupplementIcons.Dashboard)
    val register = NavDestination(AppRoutes.REGISTER, R.string.nav_register, SupplementIcons.Register)
    val stock = NavDestination(AppRoutes.STOCK, R.string.nav_stock, SupplementIcons.Stock)
    val transfer = NavDestination(AppRoutes.TRANSFER, R.string.nav_transfer, SupplementIcons.Transfer)
    val consume = NavDestination(AppRoutes.CONSUME, R.string.nav_consume, SupplementIcons.Consume)
    val history = NavDestination(AppRoutes.HISTORY, R.string.nav_history, SupplementIcons.History)
    if (session == null) {
        // Buyers record consumption without an account.
        return listOf(verify, consume, NavDestination(AppRoutes.LOGIN, R.string.nav_login, SupplementIcons.Login))
    }
    return when (session.role) {
        SupplyRole.Manufacturer -> listOf(verify, dashboard, register, transfer, history)
        SupplyRole.Distributor -> listOf(verify, stock, transfer, history)
        SupplyRole.Pharmacy -> listOf(verify, stock, consume, history)
        SupplyRole.Admin -> listOf(verify, dashboard, register, stock, transfer, consume, history)
    }
}

private fun homeRouteFor(role: SupplyRole): String =
    when (role) {
        SupplyRole.Manufacturer, SupplyRole.Admin -> AppRoutes.DASHBOARD
        SupplyRole.Distributor, SupplyRole.Pharmacy -> AppRoutes.STOCK
    }

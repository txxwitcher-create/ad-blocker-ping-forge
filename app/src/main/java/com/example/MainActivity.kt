package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.UiEvent
import com.example.ui.components.ForgeAtmosphereBackground
import com.example.ui.gaming.GamingScreen
import com.example.ui.home.HomeScreen
import com.example.ui.protection.ProtectionScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.splash.OpeningAnimationScreen
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.IceCyan
import com.example.ui.theme.PingForgeTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

object Routes {
    const val ROUTE_HOME = "home"
    const val ROUTE_PROTECTION = "protection"
    const val ROUTE_GAMING = "gaming"
    const val ROUTE_SETTINGS = "settings"
}

data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val topDestinations = listOf(
    TopLevelDestination(Routes.ROUTE_HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(Routes.ROUTE_PROTECTION, "Protection", Icons.Filled.Shield, Icons.Outlined.Shield),
    TopLevelDestination(Routes.ROUTE_GAMING, "Gaming", Icons.Filled.Speed, Icons.Outlined.Speed),
    TopLevelDestination(Routes.ROUTE_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            PingForgeRoot(viewModel = viewModel)
        }
    }
}

@Composable
fun PingForgeRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showIntroSplash by viewModel.showIntroSplash.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Foreground VPN works regardless of notification permission result
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.RequestVpnPermission -> vpnPermissionLauncher.launch(event.intent)
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    PingForgeTheme(themeStyle = settings.themeStyle) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Routes.ROUTE_HOME

        val navigateToTab: (String) -> Unit = remember(navController) {
            { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        val onNavigateToProtection = remember(navigateToTab) { { navigateToTab(Routes.ROUTE_PROTECTION) } }
        val onNavigateToGaming = remember(navigateToTab) { { navigateToTab(Routes.ROUTE_GAMING) } }

        Box(modifier = Modifier.fillMaxSize()) {
            ForgeAtmosphereBackground(
                accentColor = if (currentRoute == Routes.ROUTE_GAMING) IceCyan else ElectricViolet
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useNavigationRail = maxWidth >= 720.dp

                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = {
                            SnackbarHost(hostState = snackbarHostState) { data ->
                                Snackbar(
                                    snackbarData = data,
                                    containerColor = CharcoalSurface,
                                    contentColor = TextPrimary,
                                    actionColor = IceCyan
                                )
                            }
                        },
                        bottomBar = {
                            if (!useNavigationRail) {
                                NavigationBar(
                                    containerColor = CharcoalSurface.copy(alpha = 0.94f),
                                    contentColor = TextPrimary,
                                    tonalElevation = 0.dp,
                                    modifier = Modifier.navigationBarsPadding()
                                ) {
                                    topDestinations.forEach { dest ->
                                        val selected = currentRoute == dest.route
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { navigateToTab(dest.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                    contentDescription = dest.label
                                                )
                                            },
                                            label = { Text(dest.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = IceCyan,
                                                selectedTextColor = IceCyan,
                                                indicatorColor = ElectricViolet.copy(alpha = 0.22f),
                                                unselectedIconColor = TextMuted,
                                                unselectedTextColor = TextMuted
                                            ),
                                            modifier = Modifier.testTag("nav_${dest.route}")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .statusBarsPadding()
                        ) {
                            if (useNavigationRail) {
                                NavigationRail(
                                    containerColor = CharcoalSurface.copy(alpha = 0.90f),
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    topDestinations.forEach { dest ->
                                        val selected = currentRoute == dest.route
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateToTab(dest.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                    contentDescription = dest.label
                                                )
                                            },
                                            label = { Text(dest.label) },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = IceCyan,
                                                selectedTextColor = IceCyan,
                                                indicatorColor = ElectricViolet.copy(alpha = 0.22f),
                                                unselectedIconColor = TextMuted,
                                                unselectedTextColor = TextMuted
                                            ),
                                            modifier = Modifier.testTag("rail_${dest.route}")
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = Routes.ROUTE_HOME,
                                modifier = Modifier.weight(1f)
                            ) {
                                composable(Routes.ROUTE_HOME) {
                                    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
                                    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
                                    val activeResolver by viewModel.activeResolver.collectAsStateWithLifecycle()
                                    val counters by viewModel.counters.collectAsStateWithLifecycle()
                                    val blocklistStatus by viewModel.blocklistStatus.collectAsStateWithLifecycle()
                                    HomeScreen(
                                        vpnState = vpnState,
                                        settings = settings,
                                        networkStatus = networkStatus,
                                        activeResolver = activeResolver,
                                        counters = counters,
                                        blocklistStatus = blocklistStatus,
                                        onToggleConnect = viewModel::toggleVpnConnection,
                                        onSelectMode = viewModel::setOperatingMode,
                                        onNavigateToProtection = onNavigateToProtection,
                                        onNavigateToGaming = onNavigateToGaming
                                    )
                                }

                                composable(Routes.ROUTE_PROTECTION) {
                                    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
                                    val blocklistStatus by viewModel.blocklistStatus.collectAsStateWithLifecycle()
                                    val counters by viewModel.counters.collectAsStateWithLifecycle()
                                    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
                                    val domainTestResult by viewModel.domainTestResult.collectAsStateWithLifecycle()
                                    ProtectionScreen(
                                        vpnState = vpnState,
                                        settings = settings,
                                        blocklistStatus = blocklistStatus,
                                        counters = counters,
                                        recentLogs = recentLogs,
                                        domainTestResult = domainTestResult,
                                        onToggleCategory = viewModel::setCategoryEnabled,
                                        onUpdateBlocklists = viewModel::updateBlocklists,
                                        onAddAllowlist = viewModel::addAllowlistDomain,
                                        onRemoveAllowlist = viewModel::removeAllowlistDomain,
                                        onAddCustomBlock = viewModel::addCustomBlockDomain,
                                        onRemoveCustomBlock = viewModel::removeCustomBlockDomain,
                                        onTestDomain = viewModel::testDomainRule
                                    )
                                }

                                composable(Routes.ROUTE_GAMING) {
                                    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
                                    val activeResolver by viewModel.activeResolver.collectAsStateWithLifecycle()
                                    val benchmarkResults by viewModel.benchmarkResults.collectAsStateWithLifecycle()
                                    val benchmarkProgress by viewModel.benchmarkProgress.collectAsStateWithLifecycle()
                                    GamingScreen(
                                        settings = settings,
                                        networkStatus = networkStatus,
                                        activeResolver = activeResolver,
                                        benchmarkResults = benchmarkResults,
                                        benchmarkProgress = benchmarkProgress,
                                        onRunBenchmark = viewModel::runPingForgeBenchmark,
                                        onSelectResolver = viewModel::selectResolver,
                                        onToggleAutoSelect = viewModel::setAutoSelectFastest,
                                        onSelectMode = viewModel::setOperatingMode,
                                        onSaveCustomDns = viewModel::saveCustomDns
                                    )
                                }

                                composable(Routes.ROUTE_SETTINGS) {
                                    val activeResolver by viewModel.activeResolver.collectAsStateWithLifecycle()
                                    val counters by viewModel.counters.collectAsStateWithLifecycle()
                                    val blocklistStatus by viewModel.blocklistStatus.collectAsStateWithLifecycle()
                                    SettingsScreen(
                                        settings = settings,
                                        activeResolver = activeResolver,
                                        counters = counters,
                                        blocklistStatus = blocklistStatus,
                                        onToggleCategory = viewModel::setCategoryEnabled,
                                        onSelectResolver = viewModel::selectResolver,
                                        onToggleAutoSelect = viewModel::setAutoSelectFastest,
                                        onSaveCustomDns = viewModel::saveCustomDns,
                                        onTogglePreferDoH = viewModel::setPreferDoH,
                                        onToggleDnsCache = viewModel::setDnsCacheEnabled,
                                        onChangeCacheTtl = viewModel::setCacheTtlSeconds,
                                        onToggleAutoUpdateBlocklists = viewModel::setAutoUpdateBlocklists,
                                        onChangeUpdateIntervalHours = viewModel::setUpdateIntervalHours,
                                        onUpdateBlocklistsNow = viewModel::updateBlocklists,
                                        onToggleRebenchmarkOnNetwork = viewModel::setRebenchmarkOnNetworkChange,
                                        onToggleShowQueryLogs = viewModel::setShowQueryLogs,
                                        onChangeThemeStyle = viewModel::setThemeStyle,
                                        onToggleReduceMotion = viewModel::setReduceMotion,
                                        onReplayIntro = viewModel::replayIntroSplash,
                                        onAddAllowlist = viewModel::addAllowlistDomain,
                                        onRemoveAllowlist = viewModel::removeAllowlistDomain,
                                        onAddCustomBlock = viewModel::addCustomBlockDomain,
                                        onRemoveCustomBlock = viewModel::removeCustomBlockDomain,
                                        onClearStatsAndLogs = viewModel::clearQueryLogsAndStats,
                                        onResetConfiguration = viewModel::resetAllConfiguration
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Prevent 1-frame Home flash while DataStore loads onboardingSeen
            if (showIntroSplash == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(com.example.ui.theme.ObsidianCanvas)
                )
            }

            // Short cinematic first-launch opening animation overlay
            AnimatedVisibility(
                visible = showIntroSplash == true,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OpeningAnimationScreen(
                    reduceMotion = settings.reduceMotion,
                    onFinished = viewModel::finishIntroSplash
                )
            }
        }
    }
}

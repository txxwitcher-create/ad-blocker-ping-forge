package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.TelemetryRepository
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsResolver
import com.example.domain.model.ProtectionCategory
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnStatus
import com.example.engine.dns.DnsCache
import com.example.engine.dns.UpstreamDnsClient
import com.example.engine.filter.BlocklistRepository
import com.example.engine.filter.DomainFilterEngine
import com.example.engine.pingforge.PingForgeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Central application dependency container & VPN state coordinator.
 * Keeps UI, DataStore, Room, FilterEngine, DNS Cache, Ping Forge, and VpnService in exact sync.
 */
class AppContainer(val appContext: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository = SettingsRepository(appContext)
    val database = AppDatabase.getInstance(appContext)
    val telemetryRepository = TelemetryRepository(database)

    val filterEngine = DomainFilterEngine()
    val dnsCache = DnsCache()
    val upstreamDnsClient = UpstreamDnsClient()
    val blocklistRepository = BlocklistRepository(appContext, filterEngine, settingsRepository)
    val pingForgeEngine = PingForgeEngine(upstreamDnsClient, settingsRepository, telemetryRepository)
    val networkMonitor = NetworkMonitor(appContext)

    private val _vpnState = MutableStateFlow(VpnConnectionState())
    val vpnState: StateFlow<VpnConnectionState> = _vpnState.asStateFlow()

    @Volatile
    var latestSettings: AppSettings = AppSettings()
        private set

    private val lastToggleActionMillis = AtomicLong(0L)

    init {
        // Initialize blocklists on IO thread
        scope.launch {
            blocklistRepository.initializeListsIfNeeded()
        }

        // Keep filter engine & DNS cache synchronized with DataStore changes without dropping VPN
        settingsRepository.settingsFlow
            .distinctUntilChanged()
            .onEach { settings ->
                val previous = latestSettings
                latestSettings = settings
                filterEngine.updateActiveConfig(settings.selectedMode, settings.enabledCategories)
                filterEngine.updateUserLists(settings.allowlistDomains, settings.customBlocklistDomains)

                if (previous.selectedResolverId != settings.selectedResolverId ||
                    previous.resolvedAutomaticId != settings.resolvedAutomaticId ||
                    previous.customDnsPrimary != settings.customDnsPrimary ||
                    previous.preferDoH != settings.preferDoH ||
                    previous.enableDnsCache != settings.enableDnsCache
                ) {
                    dnsCache.clear()
                }

                val activeResolver = SettingsRepository.resolveActiveResolver(settings)
                _vpnState.value = _vpnState.value.copy(
                    activeResolverName = activeResolver.name,
                    activeResolverAddress = activeResolver.primaryIp
                )
            }
            .launchIn(scope)

        // Monitor network changes & auto-rebenchmark Ping Forge on Wi-Fi <-> Cellular transitions
        networkMonitor.startMonitoring { transport, isConnected ->
            dnsCache.clear()
            if (isConnected && latestSettings.rebenchmarkOnNetworkChange && !pingForgeEngine.progress.value.isRunning) {
                scope.launch {
                    pingForgeEngine.runFullBenchmark(transport, isNetworkConnected = true)
                }
            }
        }
    }

    fun updateVpnState(newState: VpnConnectionState) {
        _vpnState.value = newState
        networkMonitor.setSelfVpnActive(
            newState.status == VpnStatus.CONNECTED || newState.status == VpnStatus.CONNECTING
        )
    }

    fun reportVpnError(errorType: VpnErrorType, message: String) {
        val resolver = SettingsRepository.resolveActiveResolver(latestSettings)
        updateVpnState(
            VpnConnectionState(
                status = VpnStatus.ERROR,
                errorType = errorType,
                errorMessage = message,
                connectedSinceMillis = null,
                activeResolverName = resolver.name,
                activeResolverAddress = resolver.primaryIp
            )
        )
    }

    /**
     * Prepares VPN permission intent if not granted; returns null if already authorized.
     */
    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(appContext)
    }

    fun startVpnService() {
        val now = System.currentTimeMillis()
        if (now - lastToggleActionMillis.get() < 400L) return
        lastToggleActionMillis.set(now)

        val net = networkMonitor.refreshNow()
        if (net.hasOtherVpnActive) {
            reportVpnError(
                VpnErrorType.OTHER_VPN_ACTIVE,
                "Another VPN app is currently active. Android allows only one active VPN tunnel at a time—disconnect the other VPN first."
            )
            return
        }

        val resolver = SettingsRepository.resolveActiveResolver(latestSettings)
        updateVpnState(
            VpnConnectionState(
                status = VpnStatus.CONNECTING,
                activeResolverName = resolver.name,
                activeResolverAddress = resolver.primaryIp
            )
        )

        val intent = Intent(appContext, PingForgeVpnService::class.java).apply {
            action = PingForgeVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopVpnService() {
        val now = System.currentTimeMillis()
        if (now - lastToggleActionMillis.get() < 300L) return
        lastToggleActionMillis.set(now)

        val intent = Intent(appContext, PingForgeVpnService::class.java).apply {
            action = PingForgeVpnService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    fun getActiveResolver(): DnsResolver = SettingsRepository.resolveActiveResolver(latestSettings)

    fun getFallbackResolvers(primary: DnsResolver): List<DnsResolver> {
        return BuiltInResolvers.defaultResolvers.filter { it.id != primary.id }
    }
}

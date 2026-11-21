package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PingForgeApplication
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeStyle
import com.example.domain.model.BenchmarkResult
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsCounters
import com.example.domain.model.DnsQueryRecord
import com.example.domain.model.DnsResolver
import com.example.domain.model.NetworkStatus
import com.example.domain.model.ProtectionCategory
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnStatus
import com.example.engine.filter.FilterDecision
import com.example.engine.filter.ReversedDomainTrie
import com.example.engine.pingforge.BenchmarkProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class RequestVpnPermission(val intent: Intent) : UiEvent()
    data class ShowMessage(val message: String, val isError: Boolean = false) : UiEvent()
}

data class DomainTestResult(
    val input: String,
    val decision: FilterDecision
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PingForgeApplication).container
    private val settingsRepo = container.settingsRepository
    private val telemetryRepo = container.telemetryRepository
    private val blocklistRepo = container.blocklistRepository
    private val pingForgeEngine = container.pingForgeEngine

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings()
    )

    val vpnState: StateFlow<VpnConnectionState> = container.vpnState

    val networkStatus: StateFlow<NetworkStatus> = container.networkMonitor.networkStatus

    val counters: StateFlow<DnsCounters> = settingsRepo.countersFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DnsCounters()
    )

    val blocklistStatus: StateFlow<BlocklistStatus> = blocklistRepo.status

    val recentLogs: StateFlow<List<DnsQueryRecord>> = telemetryRepo.observeRecentLogs(100).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val benchmarkResults: StateFlow<List<BenchmarkResult>> = telemetryRepo.observeBenchmarks().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val benchmarkProgress: StateFlow<BenchmarkProgress> = pingForgeEngine.progress

    val activeResolver: StateFlow<DnsResolver> = settings.combine(vpnState) { s, _ ->
        SettingsRepository.resolveActiveResolver(s)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        BuiltInResolvers.defaultResolvers.first()
    )

    private val _domainTestResult = MutableStateFlow<DomainTestResult?>(null)
    val domainTestResult: StateFlow<DomainTestResult?> = _domainTestResult.asStateFlow()

    private val _showIntroSplash = MutableStateFlow<Boolean?>(null)
    val showIntroSplash: StateFlow<Boolean?> = _showIntroSplash.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settingsRepo.getCurrentSettings()
            _showIntroSplash.value = !current.onboardingSeen
        }
    }

    fun finishIntroSplash() {
        _showIntroSplash.value = false
        viewModelScope.launch {
            settingsRepo.setOnboardingSeen(true)
        }
    }

    fun replayIntroSplash() {
        _showIntroSplash.value = true
    }

    fun toggleVpnConnection() {
        val currentStatus = vpnState.value.status
        when (currentStatus) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> {
                container.stopVpnService()
            }
            VpnStatus.DISCONNECTED, VpnStatus.ERROR -> {
                val net = container.networkMonitor.refreshNow()
                if (net.hasOtherVpnActive) {
                    container.reportVpnError(
                        VpnErrorType.OTHER_VPN_ACTIVE,
                        "Another VPN is currently active. Disconnect the other VPN before starting Ping Forge."
                    )
                    _events.tryEmit(
                        UiEvent.ShowMessage(
                            "Another VPN is active. Only one VPN tunnel can run at a time on Android.",
                            isError = true
                        )
                    )
                    return
                }

                val prepareIntent = container.prepareVpnIntent()
                if (prepareIntent != null) {
                    _events.tryEmit(UiEvent.RequestVpnPermission(prepareIntent))
                } else {
                    container.startVpnService()
                }
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            container.startVpnService()
        } else {
            container.reportVpnError(
                VpnErrorType.PERMISSION_DENIED,
                "VPN permission was denied. Tap Connect and accept the system VPN request to enable device-wide DNS protection."
            )
            _events.tryEmit(
                UiEvent.ShowMessage("VPN permission denied by user.", isError = true)
            )
        }
    }

    fun setOperatingMode(mode: AppMode) {
        viewModelScope.launch {
            settingsRepo.setMode(mode)
            _events.tryEmit(UiEvent.ShowMessage("Switched to ${mode.displayName} Mode"))
        }
    }

    fun setCategoryEnabled(category: ProtectionCategory, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setCategoryEnabled(category, enabled)
        }
    }

    fun updateBlocklists() {
        if (blocklistStatus.value.isUpdating) return
        viewModelScope.launch {
            val result = blocklistRepo.updateBlocklistsFromNetwork()
            result.onSuccess { status ->
                val warn = status.lastError
                if (warn != null) {
                    _events.tryEmit(UiEvent.ShowMessage(warn, isError = false))
                } else {
                    _events.tryEmit(
                        UiEvent.ShowMessage("Blocklists updated (${status.totalDomainsLoaded} domains active)")
                    )
                }
            }.onFailure { err ->
                _events.tryEmit(
                    UiEvent.ShowMessage(err.message ?: "Blocklist update failed", isError = true)
                )
            }
        }
    }

    fun runPingForgeBenchmark() {
        if (benchmarkProgress.value.isRunning) return
        viewModelScope.launch {
            val net = container.networkMonitor.refreshNow()
            val result = pingForgeEngine.runFullBenchmark(
                transportType = net.transportType,
                isNetworkConnected = net.isConnected
            )
            result.onSuccess { list ->
                val best = com.example.engine.pingforge.PingForgeEngine.pickRecommended(list)
                if (best != null && best.medianMs != null) {
                    _events.tryEmit(
                        UiEvent.ShowMessage(
                            "Benchmark complete • Fastest reliable: ${best.resolverName} (${best.medianMs.toInt()} ms)"
                        )
                    )
                } else {
                    _events.tryEmit(
                        UiEvent.ShowMessage("Benchmark finished with resolver timeouts.", isError = true)
                    )
                }
            }.onFailure { err ->
                _events.tryEmit(
                    UiEvent.ShowMessage(err.message ?: "Benchmark failed", isError = true)
                )
            }
        }
    }

    fun selectResolver(resolverId: String) {
        viewModelScope.launch {
            settingsRepo.setSelectedResolver(resolverId)
            val label = if (resolverId == BuiltInResolvers.AUTOMATIC_RESOLVER_ID) {
                "Automatic fastest resolver"
            } else {
                BuiltInResolvers.defaultResolvers.firstOrNull { it.id == resolverId }?.name
                    ?: if (resolverId == BuiltInResolvers.CUSTOM_RESOLVER_ID) "Custom DNS" else resolverId
            }
            _events.tryEmit(UiEvent.ShowMessage("DNS resolver set to $label"))
        }
    }

    fun setAutoSelectFastest(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setAutoSelectFastest(enabled)
        }
    }

    fun saveCustomDns(primary: String, secondary: String, dohUrl: String): Boolean {
        val cleanPrimary = primary.trim()
        val cleanSecondary = secondary.trim()
        val cleanDoh = dohUrl.trim()

        if (!isValidIpv4(cleanPrimary)) {
            _events.tryEmit(
                UiEvent.ShowMessage("Enter a valid primary IPv4 DNS address (e.g., 1.1.1.1).", isError = true)
            )
            return false
        }
        if (cleanSecondary.isNotEmpty() && !isValidIpv4(cleanSecondary)) {
            _events.tryEmit(
                UiEvent.ShowMessage("Secondary DNS must be a valid IPv4 address or left blank.", isError = true)
            )
            return false
        }
        if (cleanDoh.isNotEmpty() && !cleanDoh.startsWith("https://")) {
            _events.tryEmit(
                UiEvent.ShowMessage("DoH endpoint URL must start with https://", isError = true)
            )
            return false
        }

        viewModelScope.launch {
            settingsRepo.setCustomDns(cleanPrimary, cleanSecondary, cleanDoh, selectNow = true)
            _events.tryEmit(UiEvent.ShowMessage("Custom DNS ($cleanPrimary) applied"))
        }
        return true
    }

    fun addAllowlistDomain(raw: String): Boolean {
        val normalized = ReversedDomainTrie.normalizeDomain(raw)
        if (normalized == null) {
            _events.tryEmit(
                UiEvent.ShowMessage("Invalid domain format (e.g., example.com).", isError = true)
            )
            return false
        }
        viewModelScope.launch {
            settingsRepo.addAllowlistDomain(normalized)
            _events.tryEmit(UiEvent.ShowMessage("Allowlisted $normalized (takes precedence over blocks)"))
        }
        return true
    }

    fun removeAllowlistDomain(domain: String) {
        viewModelScope.launch {
            settingsRepo.removeAllowlistDomain(domain)
        }
    }

    fun addCustomBlockDomain(raw: String): Boolean {
        val normalized = ReversedDomainTrie.normalizeDomain(raw)
        if (normalized == null) {
            _events.tryEmit(
                UiEvent.ShowMessage("Invalid domain format (e.g., tracker.example.com).", isError = true)
            )
            return false
        }
        viewModelScope.launch {
            settingsRepo.addCustomBlockDomain(normalized)
            _events.tryEmit(UiEvent.ShowMessage("Added $normalized to Custom Blocklist"))
        }
        return true
    }

    fun removeCustomBlockDomain(domain: String) {
        viewModelScope.launch {
            settingsRepo.removeCustomBlockDomain(domain)
        }
    }

    fun testDomainRule(raw: String) {
        val decision = container.filterEngine.evaluate(raw)
        _domainTestResult.value = DomainTestResult(raw.trim(), decision)
    }

    fun setPreferDoH(prefer: Boolean) {
        viewModelScope.launch { settingsRepo.setPreferDoH(prefer) }
    }

    fun setDnsCacheEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDnsCacheEnabled(enabled) }
    }

    fun setCacheTtlSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setCacheTtlSeconds(seconds) }
    }

    fun setAutoUpdateBlocklists(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoUpdateBlocklists(enabled) }
    }

    fun setUpdateIntervalHours(hours: Int) {
        viewModelScope.launch { settingsRepo.setUpdateIntervalHours(hours) }
    }

    fun setRebenchmarkOnNetworkChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setRebenchmarkOnNetworkChange(enabled) }
    }

    fun setShowQueryLogs(show: Boolean) {
        viewModelScope.launch { settingsRepo.setShowQueryLogs(show) }
    }

    fun setThemeStyle(style: AppThemeStyle) {
        viewModelScope.launch { settingsRepo.setThemeStyle(style) }
    }

    fun setReduceMotion(reduce: Boolean) {
        viewModelScope.launch { settingsRepo.setReduceMotion(reduce) }
    }

    fun clearQueryLogsAndStats() {
        viewModelScope.launch {
            telemetryRepo.clearLogs()
            settingsRepo.clearCounters()
            container.dnsCache.clear()
            _events.tryEmit(UiEvent.ShowMessage("DNS statistics, logs, and cache cleared"))
        }
    }

    fun resetAllConfiguration() {
        viewModelScope.launch {
            settingsRepo.resetAllToDefaults()
            telemetryRepo.clearLogs()
            telemetryRepo.clearBenchmarks()
            container.dnsCache.clear()
            blocklistRepo.clearDownloadedCachesAndReload()
            _events.tryEmit(UiEvent.ShowMessage("All settings and custom rules reset to defaults"))
        }
    }

    companion object {
        fun isValidIpv4(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return@all false
                val num = part.toIntOrNull() ?: return@all false
                num in 0..255
            }
        }
    }
}

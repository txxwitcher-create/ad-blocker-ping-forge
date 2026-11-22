package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeStyle
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsCounters
import com.example.domain.model.DnsResolver
import com.example.domain.model.ProtectionCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pingForgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "pingforge_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SELECTED_MODE = stringPreferencesKey("selected_mode")
        val ENABLED_CATEGORIES = stringSetPreferencesKey("enabled_categories")
        val SELECTED_RESOLVER_ID = stringPreferencesKey("selected_resolver_id")
        val RESOLVED_AUTOMATIC_ID = stringPreferencesKey("resolved_automatic_id")
        val AUTO_SELECT_FASTEST = booleanPreferencesKey("auto_select_fastest")
        val PREFER_DOH = booleanPreferencesKey("prefer_doh")
        val CUSTOM_DNS_PRIMARY = stringPreferencesKey("custom_dns_primary")
        val CUSTOM_DNS_SECONDARY = stringPreferencesKey("custom_dns_secondary")
        val CUSTOM_DNS_DOH_URL = stringPreferencesKey("custom_dns_doh_url")
        val ENABLE_DNS_CACHE = booleanPreferencesKey("enable_dns_cache")
        val CACHE_TTL_SECONDS = intPreferencesKey("cache_ttl_seconds")
        val AUTO_UPDATE_BLOCKLISTS = booleanPreferencesKey("auto_update_blocklists")
        val UPDATE_INTERVAL_HOURS = intPreferencesKey("update_interval_hours")
        val REBENCHMARK_ON_NETWORK_CHANGE = booleanPreferencesKey("rebenchmark_on_network_change")
        val SHOW_QUERY_LOGS = booleanPreferencesKey("show_query_logs")
        val THEME_STYLE = stringPreferencesKey("theme_style")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val ALLOWLIST_DOMAINS = stringSetPreferencesKey("allowlist_domains")
        val CUSTOM_BLOCKLIST_DOMAINS = stringSetPreferencesKey("custom_blocklist_domains")

        val TOTAL_QUERIES = longPreferencesKey("stat_total_queries")
        val BLOCKED_QUERIES = longPreferencesKey("stat_blocked_queries")
        val CACHED_QUERIES = longPreferencesKey("stat_cached_queries")
        val FAILED_QUERIES = longPreferencesKey("stat_failed_queries")
        val LAST_BLOCKLIST_UPDATE = longPreferencesKey("last_blocklist_update")
    }

    private val defaultSettings = AppSettings()

    val settingsFlow: Flow<AppSettings> = context.pingForgeDataStore.data.map { prefs ->
        val mode = prefs[Keys.SELECTED_MODE]?.let {
            runCatching { AppMode.valueOf(it) }.getOrNull()
        } ?: defaultSettings.selectedMode

        val categories = prefs[Keys.ENABLED_CATEGORIES]?.mapNotNull { id ->
            ProtectionCategory.entries.firstOrNull { it.id == id }
        }?.toSet() ?: defaultSettings.enabledCategories

        val themeStyle = prefs[Keys.THEME_STYLE]?.let {
            runCatching { AppThemeStyle.valueOf(it) }.getOrNull()
        } ?: defaultSettings.themeStyle

        AppSettings(
            selectedMode = mode,
            enabledCategories = categories,
            selectedResolverId = prefs[Keys.SELECTED_RESOLVER_ID] ?: defaultSettings.selectedResolverId,
            resolvedAutomaticId = prefs[Keys.RESOLVED_AUTOMATIC_ID] ?: defaultSettings.resolvedAutomaticId,
            autoSelectFastestResolver = prefs[Keys.AUTO_SELECT_FASTEST] ?: defaultSettings.autoSelectFastestResolver,
            preferDoH = prefs[Keys.PREFER_DOH] ?: defaultSettings.preferDoH,
            customDnsPrimary = prefs[Keys.CUSTOM_DNS_PRIMARY] ?: defaultSettings.customDnsPrimary,
            customDnsSecondary = prefs[Keys.CUSTOM_DNS_SECONDARY] ?: defaultSettings.customDnsSecondary,
            customDnsDohUrl = prefs[Keys.CUSTOM_DNS_DOH_URL] ?: defaultSettings.customDnsDohUrl,
            enableDnsCache = prefs[Keys.ENABLE_DNS_CACHE] ?: defaultSettings.enableDnsCache,
            cacheTtlSeconds = prefs[Keys.CACHE_TTL_SECONDS] ?: defaultSettings.cacheTtlSeconds,
            autoUpdateBlocklists = prefs[Keys.AUTO_UPDATE_BLOCKLISTS] ?: defaultSettings.autoUpdateBlocklists,
            updateIntervalHours = prefs[Keys.UPDATE_INTERVAL_HOURS] ?: defaultSettings.updateIntervalHours,
            rebenchmarkOnNetworkChange = prefs[Keys.REBENCHMARK_ON_NETWORK_CHANGE]
                ?: defaultSettings.rebenchmarkOnNetworkChange,
            showQueryLogs = prefs[Keys.SHOW_QUERY_LOGS] ?: defaultSettings.showQueryLogs,
            themeStyle = themeStyle,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: defaultSettings.reduceMotion,
            onboardingSeen = prefs[Keys.ONBOARDING_SEEN] ?: defaultSettings.onboardingSeen,
            allowlistDomains = prefs[Keys.ALLOWLIST_DOMAINS] ?: defaultSettings.allowlistDomains,
            customBlocklistDomains = prefs[Keys.CUSTOM_BLOCKLIST_DOMAINS] ?: defaultSettings.customBlocklistDomains
        )
    }

    val countersFlow: Flow<DnsCounters> = context.pingForgeDataStore.data.map { prefs ->
        DnsCounters(
            totalQueries = prefs[Keys.TOTAL_QUERIES] ?: 0L,
            blockedQueries = prefs[Keys.BLOCKED_QUERIES] ?: 0L,
            cachedQueries = prefs[Keys.CACHED_QUERIES] ?: 0L,
            failedQueries = prefs[Keys.FAILED_QUERIES] ?: 0L
        )
    }

    val lastBlocklistUpdateFlow: Flow<Long> = context.pingForgeDataStore.data.map { prefs ->
        prefs[Keys.LAST_BLOCKLIST_UPDATE] ?: 0L
    }

    suspend fun getCurrentSettings(): AppSettings = settingsFlow.first()

    suspend fun setMode(mode: AppMode) {
        context.pingForgeDataStore.edit { it[Keys.SELECTED_MODE] = mode.name }
    }

    suspend fun setCategoryEnabled(category: ProtectionCategory, enabled: Boolean) {
        context.pingForgeDataStore.edit { prefs ->
            val current = prefs[Keys.ENABLED_CATEGORIES]?.mapNotNull { id ->
                ProtectionCategory.entries.firstOrNull { it.id == id }
            }?.toMutableSet() ?: defaultSettings.enabledCategories.toMutableSet()

            if (enabled) current.add(category) else current.remove(category)
            prefs[Keys.ENABLED_CATEGORIES] = current.map { it.id }.toSet()
        }
    }

    suspend fun setSelectedResolver(resolverId: String) {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.SELECTED_RESOLVER_ID] = resolverId
            prefs[Keys.AUTO_SELECT_FASTEST] = (resolverId == BuiltInResolvers.AUTOMATIC_RESOLVER_ID)
        }
    }

    suspend fun setResolvedAutomaticId(resolverId: String) {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.RESOLVED_AUTOMATIC_ID] = resolverId
        }
    }

    suspend fun setAutoSelectFastest(enabled: Boolean) {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.AUTO_SELECT_FASTEST] = enabled
            if (enabled) {
                prefs[Keys.SELECTED_RESOLVER_ID] = BuiltInResolvers.AUTOMATIC_RESOLVER_ID
            } else if (prefs[Keys.SELECTED_RESOLVER_ID] == BuiltInResolvers.AUTOMATIC_RESOLVER_ID) {
                prefs[Keys.SELECTED_RESOLVER_ID] = prefs[Keys.RESOLVED_AUTOMATIC_ID] ?: "cloudflare"
            }
        }
    }

    suspend fun setPreferDoH(prefer: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.PREFER_DOH] = prefer }
    }

    suspend fun setCustomDns(primaryIp: String, secondaryIp: String, dohUrl: String, selectNow: Boolean = true) {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_DNS_PRIMARY] = primaryIp.trim()
            prefs[Keys.CUSTOM_DNS_SECONDARY] = secondaryIp.trim()
            prefs[Keys.CUSTOM_DNS_DOH_URL] = dohUrl.trim()
            if (selectNow) {
                prefs[Keys.SELECTED_RESOLVER_ID] = BuiltInResolvers.CUSTOM_RESOLVER_ID
                prefs[Keys.AUTO_SELECT_FASTEST] = false
            }
        }
    }

    suspend fun setDnsCacheEnabled(enabled: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.ENABLE_DNS_CACHE] = enabled }
    }

    suspend fun setCacheTtlSeconds(seconds: Int) {
        context.pingForgeDataStore.edit { it[Keys.CACHE_TTL_SECONDS] = seconds.coerceIn(30, 3600) }
    }

    suspend fun setAutoUpdateBlocklists(enabled: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.AUTO_UPDATE_BLOCKLISTS] = enabled }
    }

    suspend fun setUpdateIntervalHours(hours: Int) {
        context.pingForgeDataStore.edit { it[Keys.UPDATE_INTERVAL_HOURS] = hours }
    }

    suspend fun setRebenchmarkOnNetworkChange(enabled: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.REBENCHMARK_ON_NETWORK_CHANGE] = enabled }
    }

    suspend fun setShowQueryLogs(show: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.SHOW_QUERY_LOGS] = show }
    }

    suspend fun setThemeStyle(style: AppThemeStyle) {
        context.pingForgeDataStore.edit { it[Keys.THEME_STYLE] = style.name }
    }

    suspend fun setReduceMotion(reduce: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.REDUCE_MOTION] = reduce }
    }

    suspend fun setOnboardingSeen(seen: Boolean) {
        context.pingForgeDataStore.edit { it[Keys.ONBOARDING_SEEN] = seen }
    }

    suspend fun addAllowlistDomain(domain: String) {
        context.pingForgeDataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWLIST_DOMAINS]?.toMutableSet() ?: mutableSetOf()
            current.add(domain)
            prefs[Keys.ALLOWLIST_DOMAINS] = current
        }
    }

    suspend fun removeAllowlistDomain(domain: String) {
        context.pingForgeDataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWLIST_DOMAINS]?.toMutableSet() ?: mutableSetOf()
            current.remove(domain)
            prefs[Keys.ALLOWLIST_DOMAINS] = current
        }
    }

    suspend fun addCustomBlockDomain(domain: String) {
        context.pingForgeDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_BLOCKLIST_DOMAINS]?.toMutableSet() ?: mutableSetOf()
            current.add(domain)
            prefs[Keys.CUSTOM_BLOCKLIST_DOMAINS] = current
        }
    }

    suspend fun removeCustomBlockDomain(domain: String) {
        context.pingForgeDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_BLOCKLIST_DOMAINS]?.toMutableSet() ?: mutableSetOf()
            current.remove(domain)
            prefs[Keys.CUSTOM_BLOCKLIST_DOMAINS] = current
        }
    }

    suspend fun incrementCounters(totalDelta: Long = 1L, blockedDelta: Long = 0L, cachedDelta: Long = 0L, failedDelta: Long = 0L) {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.TOTAL_QUERIES] = (prefs[Keys.TOTAL_QUERIES] ?: 0L) + totalDelta
            if (blockedDelta > 0L) prefs[Keys.BLOCKED_QUERIES] = (prefs[Keys.BLOCKED_QUERIES] ?: 0L) + blockedDelta
            if (cachedDelta > 0L) prefs[Keys.CACHED_QUERIES] = (prefs[Keys.CACHED_QUERIES] ?: 0L) + cachedDelta
            if (failedDelta > 0L) prefs[Keys.FAILED_QUERIES] = (prefs[Keys.FAILED_QUERIES] ?: 0L) + failedDelta
        }
    }

    suspend fun clearCounters() {
        context.pingForgeDataStore.edit { prefs ->
            prefs[Keys.TOTAL_QUERIES] = 0L
            prefs[Keys.BLOCKED_QUERIES] = 0L
            prefs[Keys.CACHED_QUERIES] = 0L
            prefs[Keys.FAILED_QUERIES] = 0L
        }
    }

    suspend fun setLastBlocklistUpdate(timestampMillis: Long) {
        context.pingForgeDataStore.edit { it[Keys.LAST_BLOCKLIST_UPDATE] = timestampMillis }
    }

    suspend fun resetAllToDefaults() {
        context.pingForgeDataStore.edit { prefs ->
            val keepOnboarding = prefs[Keys.ONBOARDING_SEEN] ?: true
            prefs.clear()
            prefs[Keys.ONBOARDING_SEEN] = keepOnboarding
        }
    }

    companion object {
        fun resolveActiveResolver(settings: AppSettings): DnsResolver {
            val targetId = if (settings.selectedResolverId == BuiltInResolvers.AUTOMATIC_RESOLVER_ID ||
                settings.autoSelectFastestResolver
            ) {
                settings.resolvedAutomaticId
            } else {
                settings.selectedResolverId
            }

            if (targetId == BuiltInResolvers.CUSTOM_RESOLVER_ID &&
                settings.customDnsPrimary.isNotBlank()
            ) {
                return DnsResolver(
                    id = BuiltInResolvers.CUSTOM_RESOLVER_ID,
                    name = "Custom DNS",
                    providerTag = "${settings.customDnsPrimary} • User Configured",
                    primaryIp = settings.customDnsPrimary,
                    secondaryIp = settings.customDnsSecondary.ifBlank { settings.customDnsPrimary },
                    dohUrl = settings.customDnsDohUrl.takeIf { it.isNotBlank() },
                    description = "Custom user-defined DNS resolver",
                    supportsDoH = settings.customDnsDohUrl.isNotBlank(),
                    isCustom = true
                )
            }

            return BuiltInResolvers.defaultResolvers.firstOrNull { it.id == targetId }
                ?: BuiltInResolvers.defaultResolvers.first()
        }
    }
}

package com.example.domain.model

enum class AppMode(
    val displayName: String,
    val subtitle: String
) {
    PROTECTION(
        displayName = "Protection",
        subtitle = "Full multi-category DNS blocking for ads, trackers, adult, gambling, malware & phishing"
    ),
    GAMING(
        displayName = "Gaming",
        subtitle = "Ping Forge low-latency DNS resolution, aggressive caching & resolver stability"
    ),
    BALANCED(
        displayName = "Balanced",
        subtitle = "Active threat & ad blocking combined with Ping Forge optimal resolver routing"
    )
}

enum class ProtectionCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val bitMask: Int,
    val assetFileName: String
) {
    ADS(
        id = "ads",
        displayName = "Advertising Domains",
        description = "Blocks ad networks, mobile ad SDKs, banners, and pop-under trackers",
        bitMask = 1 shl 0,
        assetFileName = "blocklists/ads.txt"
    ),
    TRACKERS(
        id = "trackers",
        displayName = "Tracking & Analytics",
        description = "Blocks telemetry, behavioral profiling, and cross-app analytics beacons",
        bitMask = 1 shl 1,
        assetFileName = "blocklists/trackers.txt"
    ),
    ADULT(
        id = "adult",
        displayName = "Adult Websites",
        description = "Filters adult and explicit content domains at the DNS level",
        bitMask = 1 shl 2,
        assetFileName = "blocklists/adult.txt"
    ),
    GAMBLING(
        id = "gambling",
        displayName = "Gambling & Betting",
        description = "Blocks online casinos, sportsbooks, betting exchanges, and lottery portals",
        bitMask = 1 shl 3,
        assetFileName = "blocklists/gambling.txt"
    ),
    MALWARE(
        id = "malware",
        displayName = "Known Malware",
        description = "Blocks known C2 nodes, cryptominers, exploit kits, and malicious droppers",
        bitMask = 1 shl 4,
        assetFileName = "blocklists/malware.txt"
    ),
    PHISHING(
        id = "phishing",
        displayName = "Phishing & Scams",
        description = "Blocks credential harvesting portals, fake login pages, and typosquat domains",
        bitMask = 1 shl 5,
        assetFileName = "blocklists/phishing.txt"
    )
}

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class VpnErrorType {
    PERMISSION_DENIED,
    OTHER_VPN_ACTIVE,
    SERVICE_REVOKED,
    NO_NETWORK,
    INTERFACE_FAILED,
    RESOLVER_UNREACHABLE
}

data class VpnConnectionState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val errorType: VpnErrorType? = null,
    val errorMessage: String? = null,
    val connectedSinceMillis: Long? = null,
    val activeResolverName: String = "Cloudflare",
    val activeResolverAddress: String = "1.1.1.1"
)

enum class TransportType(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile Data"),
    ETHERNET("Ethernet"),
    OTHER("Network"),
    NONE("Offline")
}

data class NetworkStatus(
    val isConnected: Boolean = true,
    val transportType: TransportType = TransportType.WIFI,
    val hasOtherVpnActive: Boolean = false,
    val interfaceSummary: String = "Wi-Fi",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class DnsResolver(
    val id: String,
    val name: String,
    val providerTag: String,
    val primaryIp: String,
    val secondaryIp: String,
    val dohUrl: String?,
    val description: String,
    val supportsDoH: Boolean,
    val isCustom: Boolean = false
)

object BuiltInResolvers {
    const val CUSTOM_RESOLVER_ID = "custom"
    const val AUTOMATIC_RESOLVER_ID = "auto"

    val defaultResolvers: List<DnsResolver> = listOf(
        DnsResolver(
            id = "cloudflare",
            name = "Cloudflare",
            providerTag = "1.1.1.1 • Anycast",
            primaryIp = "1.1.1.1",
            secondaryIp = "1.0.0.1",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            description = "Ultra-fast global anycast DNS with strong privacy guarantees",
            supportsDoH = true
        ),
        DnsResolver(
            id = "cloudflare_sec",
            name = "Cloudflare Security",
            providerTag = "1.1.1.2 • Malware Filter",
            primaryIp = "1.1.1.2",
            secondaryIp = "1.0.0.2",
            dohUrl = "https://security.cloudflare-dns.com/dns-query",
            description = "Cloudflare DNS with upstream malware & phishing filtering",
            supportsDoH = true
        ),
        DnsResolver(
            id = "google",
            name = "Google Public DNS",
            providerTag = "8.8.8.8 • Global",
            primaryIp = "8.8.8.8",
            secondaryIp = "8.8.4.4",
            dohUrl = "https://dns.google/dns-query",
            description = "High-availability global anycast resolver by Google",
            supportsDoH = true
        ),
        DnsResolver(
            id = "quad9",
            name = "Quad9",
            providerTag = "9.9.9.9 • Threat Intel",
            primaryIp = "9.9.9.9",
            secondaryIp = "149.112.112.112",
            dohUrl = "https://dns.quad9.net/dns-query",
            description = "Security-focused DNS backed by global threat intelligence feeds",
            supportsDoH = true
        ),
        DnsResolver(
            id = "adguard",
            name = "AdGuard DNS",
            providerTag = "94.140.14.14 • Ad/Tracker",
            primaryIp = "94.140.14.14",
            secondaryIp = "94.140.15.15",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            description = "Privacy-oriented DNS with upstream ad and tracker filtering",
            supportsDoH = true
        ),
        DnsResolver(
            id = "opendns",
            name = "Cisco OpenDNS",
            providerTag = "208.67.222.222 • Reliable",
            primaryIp = "208.67.222.222",
            secondaryIp = "208.67.220.220",
            dohUrl = "https://doh.opendns.com/dns-query",
            description = "Long-standing enterprise-grade anycast DNS infrastructure",
            supportsDoH = true
        )
    )
}

data class BenchmarkResult(
    val resolverId: String,
    val resolverName: String,
    val resolverAddress: String,
    val medianMs: Double?,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val successCount: Int,
    val totalCount: Int,
    val reliabilityPercent: Int,
    val failedQueries: Int,
    val lastError: String? = null,
    val measuredAtMillis: Long = System.currentTimeMillis(),
    val transportLabel: String = "Unknown"
)

enum class QueryAction {
    ALLOWED,
    BLOCKED,
    CACHED,
    ALLOWLISTED,
    FAILED
}

data class DnsQueryRecord(
    val id: Long = 0,
    val domain: String,
    val category: ProtectionCategory? = null,
    val isCustomRule: Boolean = false,
    val action: QueryAction,
    val latencyMs: Long,
    val resolverName: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DnsCounters(
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val cachedQueries: Long = 0,
    val failedQueries: Long = 0
)

data class BlocklistStatus(
    val lastUpdatedMillis: Long = 0L,
    val isUpdating: Boolean = false,
    val totalDomainsLoaded: Int = 0,
    val categoryCounts: Map<ProtectionCategory, Int> = emptyMap(),
    val lastError: String? = null,
    val usingDownloadedLists: Boolean = false
)

enum class AppThemeStyle(val displayName: String, val subtitle: String) {
    OBSIDIAN_GLASS("Obsidian Glass", "Deep charcoal with electric violet & cyan glow"),
    AMOLED_BLACK("AMOLED Pitch Black", "Pure #000000 canvas for OLED power efficiency"),
    HIGH_CONTRAST("High Contrast Dark", "Elevated surface borders and crisp legibility")
}

data class AppSettings(
    val selectedMode: AppMode = AppMode.BALANCED,
    val enabledCategories: Set<ProtectionCategory> = setOf(
        ProtectionCategory.ADS,
        ProtectionCategory.TRACKERS,
        ProtectionCategory.MALWARE,
        ProtectionCategory.PHISHING
    ),
    val selectedResolverId: String = BuiltInResolvers.AUTOMATIC_RESOLVER_ID,
    val resolvedAutomaticId: String = "cloudflare",
    val autoSelectFastestResolver: Boolean = true,
    val preferDoH: Boolean = true,
    val customDnsPrimary: String = "",
    val customDnsSecondary: String = "",
    val customDnsDohUrl: String = "",
    val enableDnsCache: Boolean = true,
    val cacheTtlSeconds: Int = 300,
    val autoUpdateBlocklists: Boolean = true,
    val updateIntervalHours: Int = 24,
    val rebenchmarkOnNetworkChange: Boolean = true,
    val showQueryLogs: Boolean = true,
    val themeStyle: AppThemeStyle = AppThemeStyle.OBSIDIAN_GLASS,
    val reduceMotion: Boolean = false,
    val onboardingSeen: Boolean = false,
    val allowlistDomains: Set<String> = emptySet(),
    val customBlocklistDomains: Set<String> = emptySet()
)

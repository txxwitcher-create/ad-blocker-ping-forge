package com.example.engine.pingforge

import com.example.data.local.TelemetryRepository
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.BenchmarkResult
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsResolver
import com.example.domain.model.TransportType
import com.example.engine.dns.DnsPacket
import com.example.engine.dns.UpstreamDnsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class BenchmarkProgress(
    val isRunning: Boolean = false,
    val currentResolverName: String = "",
    val completedResolvers: Int = 0,
    val totalResolvers: Int = 0,
    val lastError: String? = null
)

/**
 * Ping Forge — Real DNS Resolver Benchmarking & Optimization Engine.
 * Measures actual UDP/DoH DNS lookup round-trip times across multiple test domains,
 * tracks query failures/timeouts, calculates reliability %, and recommends the fastest
 * reliable resolver without inventing fake ping/latency numbers.
 */
class PingForgeEngine(
    private val upstreamClient: UpstreamDnsClient,
    private val settingsRepository: SettingsRepository,
    private val telemetryRepository: TelemetryRepository,
    private val dohProbe: (ByteArray, String) -> Pair<ByteArray, Long>? = { query, url ->
        upstreamClient.queryDoH(query, url)
    },
    private val udpProbe: (ByteArray, String, Int) -> Pair<ByteArray, Long>? = { query, ip, timeoutMs ->
        upstreamClient.queryUdp(query, ip, timeoutMs = timeoutMs)
    }
) {
    private val _progress = MutableStateFlow(BenchmarkProgress())
    val progress: StateFlow<BenchmarkProgress> = _progress.asStateFlow()

    private val txIdGenerator = AtomicInteger(1000)

    @Volatile
    private var rankedResolverIds: List<String> = emptyList()

    @Volatile
    private var reliabilityByResolverId: Map<String, Int> = emptyMap()

    private val probeDomains = listOf(
        "example.com",
        "cloudflare.com",
        "google.com",
        "wikipedia.org",
        "github.com"
    )

    private val secondaryProbeDomains = listOf(
        "example.com",
        "cloudflare.com",
        "google.com"
    )

    suspend fun runFullBenchmark(
        transportType: TransportType,
        isNetworkConnected: Boolean
    ): Result<List<BenchmarkResult>> = withContext(Dispatchers.IO) {
        if (!isNetworkConnected) {
            val msg = "No active network connection. Connect to Wi-Fi or Mobile Data to benchmark DNS resolvers."
            _progress.value = BenchmarkProgress(isRunning = false, lastError = msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val settings = settingsRepository.getCurrentSettings()
        val resolversToTest = buildList {
            addAll(BuiltInResolvers.defaultResolvers)
            if (settings.customDnsPrimary.isNotBlank()) {
                add(
                    DnsResolver(
                        id = BuiltInResolvers.CUSTOM_RESOLVER_ID,
                        name = "Custom DNS",
                        providerTag = settings.customDnsPrimary,
                        primaryIp = settings.customDnsPrimary,
                        secondaryIp = settings.customDnsSecondary.ifBlank { settings.customDnsPrimary },
                        dohUrl = settings.customDnsDohUrl.takeIf { it.isNotBlank() },
                        description = "User configured DNS",
                        supportsDoH = settings.customDnsDohUrl.isNotBlank(),
                        isCustom = true
                    )
                )
            }
        }

        _progress.value = BenchmarkProgress(
            isRunning = true,
            currentResolverName = resolversToTest.firstOrNull()?.name ?: "",
            completedResolvers = 0,
            totalResolvers = resolversToTest.size
        )

        val results = mutableListOf<BenchmarkResult>()
        for ((index, resolver) in resolversToTest.withIndex()) {
            _progress.value = BenchmarkProgress(
                isRunning = true,
                currentResolverName = resolver.name,
                completedResolvers = index,
                totalResolvers = resolversToTest.size
            )
            val result = benchmarkSingleResolver(
                resolver = resolver,
                preferDoH = settings.preferDoH,
                transportLabel = transportType.label
            )
            results.add(result)
        }

        val sorted = rankResults(results)
        recordBenchmarkRanking(sorted)
        telemetryRepository.saveBenchmarks(sorted)

        // Only auto-select a resolver that meets the strict reliability threshold (>= 80%)
        val bestReliable = pickRecommended(sorted)
        if (bestReliable != null) {
            settingsRepository.setResolvedAutomaticId(bestReliable.resolverId)
        }

        val errorMsg = when {
            sorted.all { it.successCount == 0 } -> "All DNS benchmark queries failed or timed out."
            bestReliable == null -> "No DNS resolver met the $MIN_RELIABLE_PERCENT% reliability threshold."
            else -> null
        }

        _progress.value = BenchmarkProgress(
            isRunning = false,
            completedResolvers = resolversToTest.size,
            totalResolvers = resolversToTest.size,
            lastError = errorMsg
        )

        return@withContext Result.success(sorted)
    }

    /**
     * Returns fallback resolvers ordered by the latest measured benchmark performance
     * (reliable fastest resolvers first, excluding dead 0%-reliable resolvers when better options exist).
     */
    fun getRankedFallbackResolvers(
        primary: DnsResolver,
        availableResolvers: List<DnsResolver> = BuiltInResolvers.defaultResolvers
    ): List<DnsResolver> {
        val candidates = availableResolvers.filter { it.id != primary.id }
        val ranking = rankedResolverIds
        if (ranking.isEmpty()) return candidates

        val reliability = reliabilityByResolverId
        val byId = candidates.associateBy { it.id }
        val reliableRanked = mutableListOf<DnsResolver>()
        val degradedRanked = mutableListOf<DnsResolver>()
        val deadRanked = mutableListOf<DnsResolver>()

        for (id in ranking) {
            val resolver = byId[id] ?: continue
            val rel = reliability[id] ?: 0
            when {
                rel >= MIN_RELIABLE_PERCENT -> reliableRanked.add(resolver)
                rel > 0 -> degradedRanked.add(resolver)
                else -> deadRanked.add(resolver)
            }
        }

        val rankedSet = ranking.toSet()
        val unbenchmarked = candidates.filter { it.id !in rankedSet }
        val nonDead = reliableRanked + unbenchmarked + degradedRanked
        return if (nonDead.isNotEmpty()) nonDead else (nonDead + deadRanked)
    }

    internal fun recordBenchmarkRanking(sortedResults: List<BenchmarkResult>) {
        rankedResolverIds = sortedResults.map { it.resolverId }
        reliabilityByResolverId = sortedResults.associate { it.resolverId to it.reliabilityPercent }
    }

    internal fun benchmarkSingleResolver(
        resolver: DnsResolver,
        preferDoH: Boolean,
        transportLabel: String
    ): BenchmarkResult {
        val useDoH = preferDoH && resolver.supportsDoH && !resolver.dohUrl.isNullOrBlank()
        val latencies = mutableListOf<Long>()
        var primarySuccess = 0
        var primaryFailures = 0
        var secondarySuccess = 0
        var secondaryFailures = 0
        var lastError: String? = null

        if (useDoH) {
            val dohUrl = resolver.dohUrl!!
            // Strictly benchmark DoH only — never silently fall back to UDP and report it as DoH
            for (domain in probeDomains) {
                val txId = txIdGenerator.incrementAndGet() and 0xFFFF
                val query = DnsPacket.buildQueryPacket(domain, txId, DnsPacket.TYPE_A)
                val measurement = dohProbe(query, dohUrl)
                if (measurement != null) {
                    latencies.add(measurement.second)
                    primarySuccess++
                } else {
                    primaryFailures++
                    lastError = "DoH query timeout / unreachable ($dohUrl)"
                }
            }
        } else {
            // Strictly benchmark UDP (with RFC 1035 TC->TCP handled inside queryUdp) across primary & secondary IPs
            for (domain in probeDomains) {
                val txId = txIdGenerator.incrementAndGet() and 0xFFFF
                val query = DnsPacket.buildQueryPacket(domain, txId, DnsPacket.TYPE_A)
                val measurement = udpProbe(query, resolver.primaryIp, 2000)
                if (measurement != null) {
                    latencies.add(measurement.second)
                    primarySuccess++
                } else {
                    primaryFailures++
                    lastError = "Primary UDP timeout / unreachable (${resolver.primaryIp})"
                }
            }

            val hasDistinctSecondary = resolver.secondaryIp.isNotBlank() &&
                resolver.secondaryIp != resolver.primaryIp
            if (hasDistinctSecondary) {
                for (domain in secondaryProbeDomains) {
                    val txId = txIdGenerator.incrementAndGet() and 0xFFFF
                    val query = DnsPacket.buildQueryPacket(domain, txId, DnsPacket.TYPE_A)
                    val measurement = udpProbe(query, resolver.secondaryIp, 2000)
                    if (measurement != null) {
                        latencies.add(measurement.second)
                        secondarySuccess++
                    } else {
                        secondaryFailures++
                        if (lastError == null) {
                            lastError = "Secondary UDP timeout / unreachable (${resolver.secondaryIp})"
                        }
                    }
                }
            }
        }

        val total = primarySuccess + primaryFailures + secondarySuccess + secondaryFailures
        val success = primarySuccess + secondarySuccess
        val failures = primaryFailures + secondaryFailures
        val reliability = if (total == 0 || primarySuccess == 0) {
            0
        } else {
            ((success.toDouble() / total.toDouble()) * 100.0).toInt()
        }
        val sortedLat = latencies.sorted()

        val median = if (sortedLat.isEmpty()) null else {
            val mid = sortedLat.size / 2
            if (sortedLat.size % 2 == 1) sortedLat[mid].toDouble()
            else (sortedLat[mid - 1] + sortedLat[mid]) / 2.0
        }
        val avg = if (sortedLat.isEmpty()) null else sortedLat.average()
        val min = sortedLat.firstOrNull()?.toDouble()
        val max = sortedLat.lastOrNull()?.toDouble()

        val addressLabel = if (useDoH) {
            val host = resolver.dohUrl?.removePrefix("https://")?.substringBefore('/') ?: resolver.primaryIp
            "DoH • $host"
        } else if (resolver.secondaryIp.isNotBlank() && resolver.secondaryIp != resolver.primaryIp) {
            "UDP • ${resolver.primaryIp} / ${resolver.secondaryIp}"
        } else {
            "UDP • ${resolver.primaryIp}"
        }

        return BenchmarkResult(
            resolverId = resolver.id,
            resolverName = resolver.name,
            resolverAddress = addressLabel,
            medianMs = median,
            avgMs = avg,
            minMs = min,
            maxMs = max,
            successCount = success,
            totalCount = total,
            reliabilityPercent = reliability,
            failedQueries = failures,
            lastError = if (success == total) null else lastError,
            measuredAtMillis = System.currentTimeMillis(),
            transportLabel = transportLabel
        )
    }

    companion object {
        const val MIN_RELIABLE_PERCENT = 80

        fun rankResults(results: List<BenchmarkResult>): List<BenchmarkResult> {
            return results.sortedWith(
                compareByDescending<BenchmarkResult> {
                    it.reliabilityPercent >= MIN_RELIABLE_PERCENT && it.successCount > 0 && it.medianMs != null
                }
                    .thenBy {
                        if (it.reliabilityPercent >= MIN_RELIABLE_PERCENT && it.medianMs != null) {
                            it.medianMs
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                    .thenByDescending { it.reliabilityPercent }
                    .thenBy { it.medianMs ?: Double.MAX_VALUE }
            )
        }

        fun pickRecommended(results: List<BenchmarkResult>): BenchmarkResult? {
            return rankResults(results)
                .firstOrNull {
                    it.reliabilityPercent >= MIN_RELIABLE_PERCENT &&
                        it.successCount > 0 &&
                        it.medianMs != null
                }
        }
    }
}

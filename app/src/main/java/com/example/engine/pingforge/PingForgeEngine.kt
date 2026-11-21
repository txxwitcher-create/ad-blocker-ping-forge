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
    private val telemetryRepository: TelemetryRepository
) {
    private val _progress = MutableStateFlow(BenchmarkProgress())
    val progress: StateFlow<BenchmarkProgress> = _progress.asStateFlow()

    private val txIdGenerator = AtomicInteger(1000)

    private val probeDomains = listOf(
        "example.com",
        "cloudflare.com",
        "google.com",
        "wikipedia.org",
        "github.com"
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

        // Rank by reliability (>= 80% first), then by lowest median response ms
        val sorted = results.sortedWith(
            compareByDescending<BenchmarkResult> { it.reliabilityPercent >= 80 }
                .thenByDescending { it.reliabilityPercent }
                .thenBy { it.medianMs ?: Double.MAX_VALUE }
        )

        telemetryRepository.saveBenchmarks(sorted)

        // If automatic selection is active, apply fastest reliable built-in resolver
        val bestReliable = sorted.firstOrNull { it.reliabilityPercent >= 60 && it.medianMs != null }
        if (bestReliable != null) {
            settingsRepository.setResolvedAutomaticId(bestReliable.resolverId)
        }

        _progress.value = BenchmarkProgress(
            isRunning = false,
            completedResolvers = resolversToTest.size,
            totalResolvers = resolversToTest.size,
            lastError = if (sorted.all { it.successCount == 0 }) {
                "All DNS benchmark queries failed or timed out."
            } else null
        )

        return@withContext Result.success(sorted)
    }

    private fun benchmarkSingleResolver(
        resolver: DnsResolver,
        preferDoH: Boolean,
        transportLabel: String
    ): BenchmarkResult {
        val latencies = mutableListOf<Long>()
        var failures = 0
        var lastError: String? = null

        for (domain in probeDomains) {
            val txId = txIdGenerator.incrementAndGet() and 0xFFFF
            val query = DnsPacket.buildQueryPacket(domain, txId, DnsPacket.TYPE_A)

            val measurement = if (preferDoH && resolver.supportsDoH && !resolver.dohUrl.isNullOrBlank()) {
                upstreamClient.queryDoH(query, resolver.dohUrl)
                    ?: upstreamClient.queryUdp(query, resolver.primaryIp, timeoutMs = 2000)
            } else {
                upstreamClient.queryUdp(query, resolver.primaryIp, timeoutMs = 2000)
            }

            if (measurement != null) {
                latencies.add(measurement.second)
            } else {
                failures++
                lastError = "Query timeout / unreachable (${resolver.primaryIp})"
            }
        }

        val total = probeDomains.size
        val success = latencies.size
        val reliability = ((success.toDouble() / total.toDouble()) * 100.0).toInt()
        val sortedLat = latencies.sorted()

        val median = if (sortedLat.isEmpty()) null else {
            val mid = sortedLat.size / 2
            if (sortedLat.size % 2 == 1) sortedLat[mid].toDouble()
            else (sortedLat[mid - 1] + sortedLat[mid]) / 2.0
        }
        val avg = if (sortedLat.isEmpty()) null else sortedLat.average()
        val min = sortedLat.firstOrNull()?.toDouble()
        val max = sortedLat.lastOrNull()?.toDouble()

        return BenchmarkResult(
            resolverId = resolver.id,
            resolverName = resolver.name,
            resolverAddress = if (preferDoH && resolver.supportsDoH) {
                resolver.dohUrl?.removePrefix("https://")?.substringBefore('/') ?: resolver.primaryIp
            } else {
                resolver.primaryIp
            },
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
        fun pickRecommended(results: List<BenchmarkResult>): BenchmarkResult? {
            return results
                .filter { it.successCount > 0 && it.medianMs != null }
                .sortedWith(
                    compareByDescending<BenchmarkResult> { it.reliabilityPercent >= 80 }
                        .thenByDescending { it.reliabilityPercent }
                        .thenBy { it.medianMs ?: Double.MAX_VALUE }
                )
                .firstOrNull()
        }
    }
}

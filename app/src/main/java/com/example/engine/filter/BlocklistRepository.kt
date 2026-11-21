package com.example.engine.filter

import android.content.Context
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.ProtectionCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages bundled and remotely updated real DNS blocklists per ProtectionCategory.
 * Builds a unified ReversedDomainTrie off the main thread and atomically publishes it
 * to DomainFilterEngine without interrupting active VPN connections.
 */
class BlocklistRepository(
    private val context: Context,
    private val filterEngine: DomainFilterEngine,
    private val settingsRepository: SettingsRepository
) {
    private val _status = MutableStateFlow(BlocklistStatus())
    val status: StateFlow<BlocklistStatus> = _status.asStateFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Curated public open-source DNS blocklist mirrors per category (hosts/domain format)
    private val remoteFeedUrls: Map<ProtectionCategory, List<String>> = mapOf(
        ProtectionCategory.ADS to listOf(
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
        ),
        ProtectionCategory.TRACKERS to listOf(
            "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt"
        ),
        ProtectionCategory.ADULT to listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
        ),
        ProtectionCategory.GAMBLING to listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts"
        ),
        ProtectionCategory.MALWARE to listOf(
            "https://urlhaus.abuse.ch/downloads/hostfile/"
        ),
        ProtectionCategory.PHISHING to listOf(
            "https://phishing.army/download/phishing_army_blocklist.txt"
        )
    )

    private val localOverridesDir: File
        get() = File(context.filesDir, "blocklists").apply { mkdirs() }

    suspend fun initializeListsIfNeeded() = withContext(Dispatchers.IO) {
        val lastUpdated = settingsRepository.lastBlocklistUpdateFlow.first()
        loadAllListsIntoEngine(lastUpdated)
    }

    suspend fun loadAllListsIntoEngine(lastUpdatedOverride: Long? = null) = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        val lastUpdated = lastUpdatedOverride ?: settingsRepository.lastBlocklistUpdateFlow.first()
        val trie = ReversedDomainTrie()
        val counts = mutableMapOf<ProtectionCategory, Int>()
        var hasDownloaded = false

        for (category in ProtectionCategory.entries) {
            var categoryCount = 0
            // 1. Load bundled asset baseline
            runCatching {
                context.assets.open(category.assetFileName).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (trie.insert(line, category.bitMask)) {
                            categoryCount++
                        }
                    }
                }
            }

            // 2. Layer cached downloaded list on top if present
            val cachedFile = File(localOverridesDir, "${category.id}.txt")
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                hasDownloaded = true
                runCatching {
                    cachedFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (trie.insert(line, category.bitMask)) {
                                categoryCount++
                            }
                        }
                    }
                }
            }
            counts[category] = categoryCount
        }

        filterEngine.updateActiveConfig(settings.selectedMode, settings.enabledCategories)
        filterEngine.publishCategorySnapshot(
            categoryTrie = trie,
            categoryCounts = counts,
            allowlistDomains = settings.allowlistDomains,
            customBlocklistDomains = settings.customBlocklistDomains
        )

        _status.value = _status.value.copy(
            lastUpdatedMillis = lastUpdated,
            isUpdating = false,
            totalDomainsLoaded = trie.uniqueDomainCount,
            categoryCounts = counts,
            usingDownloadedLists = hasDownloaded
        )
    }

    /**
     * Downloads fresh blocklists over HTTPS for all categories, validates parsed domains,
     * caches them locally, and atomically refreshes the active ReversedDomainTrie.
     */
    suspend fun updateBlocklistsFromNetwork(): Result<BlocklistStatus> = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(isUpdating = true, lastError = null)
        var succeededCategories = 0
        val errors = mutableListOf<String>()

        for (category in ProtectionCategory.entries) {
            val urls = remoteFeedUrls[category].orEmpty()
            var downloaded = false
            for (url in urls) {
                try {
                    val req = Request.Builder().url(url).get().build()
                    downloadClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            errors.add("${category.displayName}: HTTP ${resp.code}")
                            return@use
                        }
                        val body = resp.body ?: return@use
                        val tempFile = File(localOverridesDir, "${category.id}.tmp")
                        var validCount = 0
                        tempFile.bufferedWriter().use { writer ->
                            body.charStream().buffered().useLines { lines ->
                                lines.forEach { raw ->
                                    if (validCount < 50_000) {
                                        val normalized = ReversedDomainTrie.normalizeDomain(raw)
                                        if (normalized != null) {
                                            writer.appendLine(normalized)
                                            validCount++
                                        }
                                    }
                                }
                            }
                        }
                        if (validCount > 0) {
                            val targetFile = File(localOverridesDir, "${category.id}.txt")
                            if (targetFile.exists()) targetFile.delete()
                            tempFile.renameTo(targetFile)
                            downloaded = true
                            succeededCategories++
                        } else {
                            tempFile.delete()
                        }
                    }
                    if (downloaded) break
                } catch (e: Exception) {
                    errors.add("${category.displayName}: ${e.message ?: "Network error"}")
                }
            }
        }

        return@withContext if (succeededCategories > 0) {
            val now = System.currentTimeMillis()
            settingsRepository.setLastBlocklistUpdate(now)
            loadAllListsIntoEngine(now)
            val warning = if (succeededCategories < ProtectionCategory.entries.size) {
                "Updated $succeededCategories/${ProtectionCategory.entries.size} feeds (${errors.firstOrNull() ?: "partial"})"
            } else {
                null
            }
            val updatedStatus = _status.value.copy(lastError = warning, isUpdating = false)
            _status.value = updatedStatus
            Result.success(updatedStatus)
        } else {
            val msg = errors.firstOrNull() ?: "Unable to reach blocklist feeds. Check internet connectivity."
            _status.value = _status.value.copy(isUpdating = false, lastError = msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun clearDownloadedCachesAndReload() = withContext(Dispatchers.IO) {
        localOverridesDir.listFiles()?.forEach { it.delete() }
        settingsRepository.setLastBlocklistUpdate(0L)
        loadAllListsIntoEngine(0L)
    }
}

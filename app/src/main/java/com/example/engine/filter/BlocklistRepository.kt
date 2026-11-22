package com.example.engine.filter

import android.content.Context
import android.os.Build
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.ProtectionCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    private val updateMutex = Mutex()

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
     * replaces cached files safely/atomically without risking partial/deleted files,
     * and atomically refreshes the active ReversedDomainTrie.
     */
    suspend fun updateBlocklistsFromNetwork(): Result<BlocklistStatus> = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            _status.value = _status.value.copy(isUpdating = true, lastError = null)
            var succeededCategories = 0
            val errors = mutableListOf<String>()

            for (category in ProtectionCategory.entries) {
                val urls = remoteFeedUrls[category].orEmpty()
                var downloaded = false
                for (url in urls) {
                    val tempFile = File(localOverridesDir, "${category.id}.${System.nanoTime()}.tmp")
                    try {
                        val req = Request.Builder().url(url).get().build()
                        downloadClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                errors.add("${category.displayName}: HTTP ${resp.code}")
                                return@use
                            }
                            val body = resp.body ?: return@use
                            var validCount = 0
                            FileOutputStream(tempFile).use { fos ->
                                OutputStreamWriter(fos, Charsets.UTF_8).buffered().use { writer ->
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
                                    writer.flush()
                                }
                                runCatching { fos.fd.sync() }
                            }
                            if (validCount > 0) {
                                val targetFile = File(localOverridesDir, "${category.id}.txt")
                                if (replaceFileAtomically(tempFile, targetFile)) {
                                    downloaded = true
                                    succeededCategories++
                                } else {
                                    errors.add("${category.displayName}: failed to replace blocklist file safely")
                                }
                            } else {
                                errors.add("${category.displayName}: feed returned no valid domains")
                            }
                        }
                        if (downloaded) break
                    } catch (e: Exception) {
                        errors.add("${category.displayName}: ${e.message ?: "Network error"}")
                    } finally {
                        if (tempFile.exists()) {
                            runCatching { tempFile.delete() }
                        }
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
    }

    suspend fun clearDownloadedCachesAndReload() = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            localOverridesDir.listFiles()?.forEach { it.delete() }
            settingsRepository.setLastBlocklistUpdate(0L)
            loadAllListsIntoEngine(0L)
        }
    }

    companion object {
        private const val MIN_INTERVAL_HOURS = 1
        private const val MS_PER_HOUR = 3_600_000L

        /**
         * Safely replaces [targetFile] with [tempFile] without ever leaving [targetFile] missing
         * or corrupted if a move/copy fails. Uses atomic NIO move when supported, otherwise
         * performs a backup-and-rollback swap.
         */
        fun replaceFileAtomically(tempFile: File, targetFile: File): Boolean {
            if (!tempFile.exists() || !tempFile.isFile || tempFile.length() <= 0L) {
                return false
            }
            val parent = targetFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false
            }

            // Try POSIX/NIO atomic move first (API 26+ or JVM tests)
            val canTryNio = Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            if (canTryNio) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    return targetFile.exists() && targetFile.length() > 0L
                } catch (_: LinkageError) {
                    // Fall through to backup-and-rollback replacement
                } catch (_: Exception) {
                    // Atomic move unsupported across mounts or failed; fall through to backup-and-rollback
                }
            }

            val backupFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.bak")
            var backedUp = false
            try {
                if (targetFile.exists()) {
                    if (!targetFile.renameTo(backupFile)) {
                        return false
                    }
                    backedUp = true
                }

                val moved = tempFile.renameTo(targetFile) || run {
                    // Cross-directory fallback copy with sync
                    try {
                        tempFile.inputStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                                output.flush()
                                runCatching { output.fd.sync() }
                            }
                        }
                        tempFile.delete()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!moved || !targetFile.exists() || targetFile.length() <= 0L) {
                    runCatching { targetFile.delete() }
                    if (backedUp && backupFile.exists()) {
                        backupFile.renameTo(targetFile)
                    }
                    return false
                }

                if (backedUp && backupFile.exists()) {
                    runCatching { backupFile.delete() }
                }
                return true
            } catch (_: Exception) {
                runCatching { targetFile.delete() }
                if (backedUp && backupFile.exists()) {
                    runCatching { backupFile.renameTo(targetFile) }
                }
                return false
            }
        }

        fun isAutoUpdateDue(
            lastUpdatedMillis: Long,
            intervalHours: Int,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            return computeNextUpdateDelayMillis(lastUpdatedMillis, intervalHours, nowMillis) == 0L
        }

        fun computeNextUpdateDelayMillis(
            lastUpdatedMillis: Long,
            intervalHours: Int,
            nowMillis: Long = System.currentTimeMillis()
        ): Long {
            val safeHours = intervalHours.coerceAtLeast(MIN_INTERVAL_HOURS)
            if (lastUpdatedMillis <= 0L) return 0L
            val intervalMs = safeHours * MS_PER_HOUR
            val elapsed = (nowMillis - lastUpdatedMillis).coerceAtLeast(0L)
            return (intervalMs - elapsed).coerceAtLeast(0L)
        }
    }
}

package com.example.engine.filter

import com.example.domain.model.AppMode
import com.example.domain.model.ProtectionCategory
import java.util.concurrent.atomic.AtomicReference

sealed class FilterDecision {
    data class Allowlisted(val normalizedDomain: String) : FilterDecision()
    data class Blocked(
        val normalizedDomain: String,
        val category: ProtectionCategory?,
        val isCustomRule: Boolean
    ) : FilterDecision()

    data class Allowed(val normalizedDomain: String) : FilterDecision()
    data class InvalidDomain(val rawDomain: String) : FilterDecision()
}

data class FilterSnapshot(
    val categoryTrie: ReversedDomainTrie,
    val allowlistTrie: ReversedDomainTrie,
    val customBlockTrie: ReversedDomainTrie,
    val categoryCounts: Map<ProtectionCategory, Int>,
    val totalUniqueDomains: Int
)

/**
 * Thread-safe, lock-free read-path domain filtering engine.
 * Allowlist always takes precedence over both custom blocklist and category blocklists.
 */
class DomainFilterEngine {

    private val snapshotRef = AtomicReference(
        FilterSnapshot(
            categoryTrie = ReversedDomainTrie(),
            allowlistTrie = ReversedDomainTrie(),
            customBlockTrie = ReversedDomainTrie(),
            categoryCounts = emptyMap(),
            totalUniqueDomains = 0
        )
    )

    @Volatile
    var activeMode: AppMode = AppMode.BALANCED

    @Volatile
    var enabledCategories: Set<ProtectionCategory> = setOf(
        ProtectionCategory.ADS,
        ProtectionCategory.TRACKERS,
        ProtectionCategory.MALWARE,
        ProtectionCategory.PHISHING
    )

    fun publishCategorySnapshot(
        categoryTrie: ReversedDomainTrie,
        categoryCounts: Map<ProtectionCategory, Int>,
        allowlistDomains: Set<String>,
        customBlocklistDomains: Set<String>
    ) {
        val allowTrie = buildSimpleTrie(allowlistDomains)
        val customTrie = buildSimpleTrie(customBlocklistDomains, isCustom = true)
        snapshotRef.set(
            FilterSnapshot(
                categoryTrie = categoryTrie,
                allowlistTrie = allowTrie,
                customBlockTrie = customTrie,
                categoryCounts = categoryCounts,
                totalUniqueDomains = categoryTrie.uniqueDomainCount
            )
        )
    }

    fun updateUserLists(allowlistDomains: Set<String>, customBlocklistDomains: Set<String>) {
        val current = snapshotRef.get()
        val allowTrie = buildSimpleTrie(allowlistDomains)
        val customTrie = buildSimpleTrie(customBlocklistDomains, isCustom = true)
        snapshotRef.set(
            current.copy(
                allowlistTrie = allowTrie,
                customBlockTrie = customTrie
            )
        )
    }

    fun updateActiveConfig(mode: AppMode, categories: Set<ProtectionCategory>) {
        activeMode = mode
        enabledCategories = categories
    }

    fun getCategoryCounts(): Map<ProtectionCategory, Int> = snapshotRef.get().categoryCounts

    fun getTotalLoadedDomains(): Int = snapshotRef.get().totalUniqueDomains

    /**
     * Evaluates a domain against the current atomic snapshot and active mode/categories.
     * In GAMING mode, category blocking is suspended so Ping Forge prioritizes raw DNS resolution
     * unless user explicitly keeps allowlist/custom overrides? Wait: in Gaming Mode, the prompt says:
     * "Gaming Mode focuses on: DNS resolver benchmarking, Resolver reliability, Low-latency DNS resolution,
     * DNS caching, Stable resolver selection, Network reliability. Balanced Mode combines protection with DNS optimization."
     * So in Gaming Mode, category blocklists are bypassed (custom blocklist can still apply or be bypassed;
     * bypassing category blocklists in Gaming Mode ensures zero false positives on game CDNs/telemetry!).
     */
    fun evaluate(rawDomain: String): FilterDecision {
        val normalized = ReversedDomainTrie.normalizeDomain(rawDomain)
            ?: return FilterDecision.InvalidDomain(rawDomain)

        val snapshot = snapshotRef.get()

        // 1. Allowlist ALWAYS takes top precedence over all block rules.
        if (snapshot.allowlistTrie.match(normalized) != null) {
            return FilterDecision.Allowlisted(normalized)
        }

        // 2. Gaming Mode bypasses category blocklists to avoid blocking game telemetry/auth/CDN domains
        if (activeMode == AppMode.GAMING) {
            return FilterDecision.Allowed(normalized)
        }

        // 3. Custom blocklist applies when Protection or Balanced mode is active
        if (snapshot.customBlockTrie.match(normalized) != null) {
            return FilterDecision.Blocked(
                normalizedDomain = normalized,
                category = null,
                isCustomRule = true
            )
        }

        // 4. Category blocklists (only enabled categories are enforced!)
        val activeBitMask = enabledCategories.fold(0) { acc, cat -> acc or cat.bitMask }
        if (activeBitMask == 0) {
            return FilterDecision.Allowed(normalized)
        }

        val match = snapshot.categoryTrie.match(normalized) ?: return FilterDecision.Allowed(normalized)
        val effectiveMask = match.mask and activeBitMask
        if (effectiveMask == 0) {
            return FilterDecision.Allowed(normalized)
        }

        val matchedCategory = ProtectionCategory.entries.firstOrNull { (effectiveMask and it.bitMask) != 0 }
        return FilterDecision.Blocked(
            normalizedDomain = normalized,
            category = matchedCategory,
            isCustomRule = false
        )
    }

    private fun buildSimpleTrie(domains: Set<String>, isCustom: Boolean = false): ReversedDomainTrie {
        val trie = ReversedDomainTrie()
        for (d in domains) {
            trie.insert(d, mask = 1, isCustom = isCustom)
        }
        return trie
    }
}

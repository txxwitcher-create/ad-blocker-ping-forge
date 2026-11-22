package com.example.engine.dns

import java.util.Locale

/**
 * Thread-safe bounded LRU DNS cache respecting per-record TTL, expiration,
 * full query cache keys (qName, qType, qClass, resolverKey), and refusing to cache failed responses.
 */
class DnsCache(private val maxEntries: Int = 2048) {

    data class CacheKey(
        val domain: String,
        val qType: Int,
        val qClass: Int,
        val resolverKey: String
    )

    data class CacheHit(
        val responseBytes: ByteArray,
        val elapsedSeconds: Int
    )

    private data class CacheEntry(
        val responseBytes: ByteArray,
        val storedAtMillis: Long,
        val expiresAtMillis: Long
    )

    private val lock = Any()
    private val map = object : LinkedHashMap<CacheKey, CacheEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(
        domain: String,
        qType: Int,
        resolverId: String,
        nowMillis: Long = System.currentTimeMillis(),
        qClass: Int = DnsPacket.CLASS_IN
    ): ByteArray? {
        return getWithMetadata(domain, qType, qClass, resolverId, nowMillis)?.responseBytes
    }

    fun getWithMetadata(
        domain: String,
        qType: Int,
        qClass: Int,
        resolverKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CacheHit? {
        val key = CacheKey(normalizeKeyDomain(domain), qType, qClass, resolverKey)
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (nowMillis >= entry.expiresAtMillis) {
                map.remove(key)
                return null
            }
            val elapsedSeconds = ((nowMillis - entry.storedAtMillis) / 1000L).toInt().coerceAtLeast(0)
            return CacheHit(
                responseBytes = entry.responseBytes.copyOf(),
                elapsedSeconds = elapsedSeconds
            )
        }
    }

    fun put(
        domain: String,
        qType: Int,
        resolverId: String,
        responseBytes: ByteArray,
        ttlSeconds: Int,
        maxConfiguredTtlSeconds: Int,
        nowMillis: Long = System.currentTimeMillis(),
        qClass: Int = DnsPacket.CLASS_IN
    ): Boolean {
        val key = CacheKey(normalizeKeyDomain(domain), qType, qClass, resolverId)

        // Never cache malformed, failed/error, or truncated DNS responses (SERVFAIL, REFUSED, FORMERR, TC=1, etc.)
        if (!DnsPacket.isCacheableResponse(responseBytes)) {
            synchronized(lock) { map.remove(key) }
            return false
        }

        // Respect TTL=0 (RFC 1035: do not cache) and disabled TTLs
        if (ttlSeconds <= 0 || maxConfiguredTtlSeconds <= 0) {
            synchronized(lock) { map.remove(key) }
            return false
        }

        var effectiveTtl = ttlSeconds.coerceAtMost(maxConfiguredTtlSeconds)
        if (responseBytes.size >= 12 && DnsPacket.extractRcode(responseBytes) == DnsPacket.RCODE_NXDOMAIN) {
            effectiveTtl = effectiveTtl.coerceAtMost(60)
        }

        synchronized(lock) {
            map[key] = CacheEntry(
                responseBytes = responseBytes.copyOf(),
                storedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + effectiveTtl * 1000L
            )
        }
        return true
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    private fun normalizeKeyDomain(domain: String): String =
        domain.trim().trimEnd('.').lowercase(Locale.ROOT)
}

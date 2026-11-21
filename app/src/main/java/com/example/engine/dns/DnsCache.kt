package com.example.engine.dns

import java.util.Collections

/**
 * Thread-safe bounded LRU DNS cache respecting per-record TTL and instant invalidation.
 */
class DnsCache(private val maxEntries: Int = 2048) {

    data class CacheKey(val domain: String, val qType: Int, val resolverId: String)

    private data class CacheEntry(
        val responseBytes: ByteArray,
        val expiresAtMillis: Long
    )

    private val map = Collections.synchronizedMap(
        object : LinkedHashMap<CacheKey, CacheEntry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
                return size > maxEntries
            }
        }
    )

    fun get(domain: String, qType: Int, resolverId: String, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
        val key = CacheKey(domain.lowercase(), qType, resolverId)
        val entry = map[key] ?: return null
        if (nowMillis >= entry.expiresAtMillis) {
            map.remove(key)
            return null
        }
        return entry.responseBytes
    }

    fun put(
        domain: String,
        qType: Int,
        resolverId: String,
        responseBytes: ByteArray,
        ttlSeconds: Int,
        maxConfiguredTtlSeconds: Int,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val effectiveTtl = ttlSeconds.coerceIn(15, maxConfiguredTtlSeconds.coerceAtLeast(30))
        val key = CacheKey(domain.lowercase(), qType, resolverId)
        map[key] = CacheEntry(
            responseBytes = responseBytes.copyOf(),
            expiresAtMillis = nowMillis + effectiveTtl * 1000L
        )
    }

    fun clear() {
        map.clear()
    }

    fun size(): Int = map.size
}

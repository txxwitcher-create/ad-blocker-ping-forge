package com.example

import com.example.domain.model.AppMode
import com.example.domain.model.ProtectionCategory
import com.example.engine.dns.DnsCache
import com.example.engine.dns.DnsPacket
import com.example.engine.dns.Ipv4UdpPacketParser
import com.example.engine.filter.DomainFilterEngine
import com.example.engine.filter.FilterDecision
import com.example.engine.filter.ReversedDomainTrie
import com.example.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `trie normalizes hosts AdGuard URLs and rejects malformed domains`() {
        assertEquals("ads.example.com", ReversedDomainTrie.normalizeDomain("0.0.0.0 ads.example.com # comment"))
        assertEquals("tracker.net", ReversedDomainTrie.normalizeDomain("||tracker.net^"))
        assertEquals("doubleclick.net", ReversedDomainTrie.normalizeDomain("https://doubleclick.net/path?a=1"))
        assertEquals("sub.ad.io", ReversedDomainTrie.normalizeDomain("*.SUB.AD.IO."))

        assertNull(ReversedDomainTrie.normalizeDomain(""))
        assertNull(ReversedDomainTrie.normalizeDomain("# comment only"))
        assertNull(ReversedDomainTrie.normalizeDomain("192.168.1.1"))
        assertNull(ReversedDomainTrie.normalizeDomain("localhost"))
        assertNull(ReversedDomainTrie.normalizeDomain("invalid_domain_no_tld"))
        assertNull(ReversedDomainTrie.normalizeDomain("bad..domain.com"))
    }

    @Test
    fun `trie matches exact domain and arbitrary subdomains in O(labels)`() {
        val trie = ReversedDomainTrie()
        assertTrue(trie.insert("doubleclick.net", ProtectionCategory.ADS.bitMask))
        assertTrue(trie.insert("analytics.google.com", ProtectionCategory.TRACKERS.bitMask))

        val subMatch = trie.match("ad.g.doubleclick.net")
        assertNotNull(subMatch)
        assertEquals(ProtectionCategory.ADS.bitMask, subMatch!!.mask)

        assertNull(trie.match("google.com"))
        assertNotNull(trie.match("ssl.analytics.google.com"))
    }

    @Test
    fun `filter engine enforces allowlist precedence independent categories and gaming bypass`() {
        val engine = DomainFilterEngine()
        val trie = ReversedDomainTrie().apply {
            insert("doubleclick.net", ProtectionCategory.ADS.bitMask)
            insert("mixpanel.com", ProtectionCategory.TRACKERS.bitMask)
            insert("evil-c2.top", ProtectionCategory.MALWARE.bitMask)
        }
        engine.publishCategorySnapshot(
            categoryTrie = trie,
            categoryCounts = mapOf(
                ProtectionCategory.ADS to 1,
                ProtectionCategory.TRACKERS to 1,
                ProtectionCategory.MALWARE to 1
            ),
            allowlistDomains = setOf("sub.doubleclick.net"),
            customBlocklistDomains = setOf("customblocked.org")
        )

        engine.updateActiveConfig(
            mode = AppMode.PROTECTION,
            categories = setOf(ProtectionCategory.ADS, ProtectionCategory.MALWARE)
        )

        // Category enabled -> blocked
        val adsDecision = engine.evaluate("doubleclick.net")
        assertTrue(adsDecision is FilterDecision.Blocked)
        assertEquals(ProtectionCategory.ADS, (adsDecision as FilterDecision.Blocked).category)

        // Allowlist overrides category blocklist
        val allowDecision = engine.evaluate("api.sub.doubleclick.net")
        assertTrue(allowDecision is FilterDecision.Allowlisted)

        // Category disabled (TRACKERS not in enabledCategories) -> allowed
        val trackerDecision = engine.evaluate("api.mixpanel.com")
        assertTrue(trackerDecision is FilterDecision.Allowed)

        // Custom blocklist works in Protection Mode
        val customDecision = engine.evaluate("cdn.customblocked.org")
        assertTrue(customDecision is FilterDecision.Blocked)
        assertTrue((customDecision as FilterDecision.Blocked).isCustomRule)

        // Gaming Mode bypasses category blocklists
        engine.updateActiveConfig(mode = AppMode.GAMING, categories = setOf(ProtectionCategory.ADS))
        assertTrue(engine.evaluate("doubleclick.net") is FilterDecision.Allowed)
    }

    @Test
    fun `dns packet builds parses blocks and rewrites transaction ids accurately`() {
        val query = DnsPacket.buildQueryPacket("ads.example.com", transactionId = 0x1234, qType = DnsPacket.TYPE_A)
        val parsed = DnsPacket.parseQuestion(query)
        assertNotNull(parsed)
        assertEquals(0x1234, parsed!!.transactionId)
        assertEquals("ads.example.com", parsed.qName)
        assertEquals(DnsPacket.TYPE_A, parsed.qType)

        val blocked = DnsPacket.buildBlockedResponse(query, parsed)
        assertTrue(DnsPacket.isValidResponse(blocked, expectedTxId = 0x1234))
        // Last 4 bytes of A answer should be 0.0.0.0
        assertEquals(0, blocked[blocked.size - 1].toInt())
        assertEquals(0, blocked[blocked.size - 2].toInt())
        assertEquals(0, blocked[blocked.size - 3].toInt())
        assertEquals(0, blocked[blocked.size - 4].toInt())

        val rewritten = DnsPacket.rewriteTransactionId(blocked, 0xABCD)
        assertTrue(DnsPacket.isValidResponse(rewritten, expectedTxId = 0xABCD))
    }

    @Test
    fun `ipv4 udp parser parses DNS packet and produces valid response packet`() {
        val dnsQuery = DnsPacket.buildQueryPacket("example.com", transactionId = 0x4242)
        val totalLen = 20 + 8 + dnsQuery.size
        val raw = ByteArray(totalLen)
        raw[0] = 0x45.toByte()
        raw[2] = ((totalLen ushr 8) and 0xFF).toByte()
        raw[3] = (totalLen and 0xFF).toByte()
        raw[9] = 17.toByte() // UDP
        // Src IP 10.111.222.2, Dst IP 10.111.222.1
        raw[12] = 10; raw[13] = 111; raw[14] = 222.toByte(); raw[15] = 2
        raw[16] = 10; raw[17] = 111; raw[18] = 222.toByte(); raw[19] = 1
        // Src port 51234, Dst port 53
        raw[20] = (51234 ushr 8).toByte(); raw[21] = (51234 and 0xFF).toByte()
        raw[22] = 0; raw[23] = 53
        val udpLen = 8 + dnsQuery.size
        raw[24] = (udpLen ushr 8).toByte(); raw[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, raw, 28, dnsQuery.size)

        val parsed = Ipv4UdpPacketParser.parseUdpDnsPacket(raw, raw.size)
        assertNotNull(parsed)
        assertEquals(51234, parsed!!.srcPort)
        assertEquals(53, parsed.dstPort)

        val resp = Ipv4UdpPacketParser.buildUdpResponsePacket(parsed, dnsQuery)
        assertEquals(totalLen, resp.size)
        // Swapped src/dst IP
        assertEquals(1.toByte(), resp[15])
        assertEquals(2.toByte(), resp[19])
        // Swapped src/dst port
        assertEquals(53, ((resp[20].toInt() and 0xFF) shl 8) or (resp[21].toInt() and 0xFF))
        assertEquals(51234, ((resp[22].toInt() and 0xFF) shl 8) or (resp[23].toInt() and 0xFF))
    }

    @Test
    fun `dns cache respects ttl expiration and resolver isolation`() {
        val cache = DnsCache(maxEntries = 10)
        val payload = byteArrayOf(1, 2, 3, 4)
        cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", payload, ttlSeconds = 30, maxConfiguredTtlSeconds = 300, nowMillis = 1000L)

        assertNotNull(cache.get("example.com", DnsPacket.TYPE_A, "cloudflare", nowMillis = 10_000L))
        assertNull(cache.get("example.com", DnsPacket.TYPE_A, "google", nowMillis = 10_000L))
        assertNull(cache.get("example.com", DnsPacket.TYPE_A, "cloudflare", nowMillis = 35_000L))
    }

    @Test
    fun `ipv4 validation accepts valid addresses and rejects invalid ones`() {
        assertTrue(MainViewModel.isValidIpv4("1.1.1.1"))
        assertTrue(MainViewModel.isValidIpv4("94.140.14.14"))
        assertFalse(MainViewModel.isValidIpv4("256.1.1.1"))
        assertFalse(MainViewModel.isValidIpv4("1.1.1"))
        assertFalse(MainViewModel.isValidIpv4("dns.google"))
    }
}

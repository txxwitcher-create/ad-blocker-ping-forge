package com.example

import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.BenchmarkResult
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsResolver
import com.example.domain.model.ProtectionCategory
import com.example.engine.dns.DnsCache
import com.example.engine.dns.DnsPacket
import com.example.engine.dns.Ipv4UdpPacketParser
import com.example.engine.dns.TcpDnsSessionManager
import com.example.engine.dns.UpstreamDnsClient
import com.example.engine.filter.BlocklistRepository
import com.example.engine.filter.DomainFilterEngine
import com.example.engine.filter.FilterDecision
import com.example.engine.filter.ReversedDomainTrie
import com.example.engine.pingforge.PingForgeEngine
import com.example.ui.MainViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

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

        val adsDecision = engine.evaluate("doubleclick.net")
        assertTrue(adsDecision is FilterDecision.Blocked)
        assertEquals(ProtectionCategory.ADS, (adsDecision as FilterDecision.Blocked).category)

        val allowDecision = engine.evaluate("api.sub.doubleclick.net")
        assertTrue(allowDecision is FilterDecision.Allowlisted)

        val trackerDecision = engine.evaluate("api.mixpanel.com")
        assertTrue(trackerDecision is FilterDecision.Allowed)

        val customDecision = engine.evaluate("cdn.customblocked.org")
        assertTrue(customDecision is FilterDecision.Blocked)
        assertTrue((customDecision as FilterDecision.Blocked).isCustomRule)

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
        assertTrue(DnsPacket.isValidResponse(blocked, expectedTxId = 0x1234, expectedQueryPacket = query))
        assertEquals(0, blocked[blocked.size - 1].toInt())
        assertEquals(0, blocked[blocked.size - 2].toInt())
        assertEquals(0, blocked[blocked.size - 3].toInt())
        assertEquals(0, blocked[blocked.size - 4].toInt())

        val rewritten = DnsPacket.rewriteTransactionId(blocked, 0xABCD)
        assertTrue(DnsPacket.isValidResponse(rewritten, expectedTxId = 0xABCD))
    }

    @Test
    fun `issue 1 dns response validation checks txid qr flag and matching question`() {
        val query = DnsPacket.buildQueryPacket("example.com", transactionId = 0x2233, qType = DnsPacket.TYPE_A)
        val parsed = DnsPacket.parseQuestion(query)!!
        val validResp = DnsPacket.buildBlockedResponse(query, parsed)

        assertTrue(DnsPacket.isValidResponse(validResp, expectedTxId = 0x2233, expectedQueryPacket = query))
        // Wrong TXID rejected
        assertFalse(DnsPacket.isValidResponse(validResp, expectedTxId = 0x9999, expectedQueryPacket = query))
        // QR=0 (query packet instead of response) rejected
        assertFalse(DnsPacket.isValidResponse(query, expectedTxId = 0x2233, expectedQueryPacket = query))

        // Different QNAME with same TXID rejected
        val spoofQuery = DnsPacket.buildQueryPacket("evil.com", transactionId = 0x2233, qType = DnsPacket.TYPE_A)
        val spoofResp = DnsPacket.buildBlockedResponse(spoofQuery, DnsPacket.parseQuestion(spoofQuery)!!)
        assertFalse(DnsPacket.isValidResponse(spoofResp, expectedTxId = 0x2233, expectedQueryPacket = query))

        // Different QTYPE with same TXID and QNAME rejected
        val aaaaQuery = DnsPacket.buildQueryPacket("example.com", transactionId = 0x2233, qType = DnsPacket.TYPE_AAAA)
        val aaaaResp = DnsPacket.buildBlockedResponse(aaaaQuery, DnsPacket.parseQuestion(aaaaQuery)!!)
        assertFalse(DnsPacket.isValidResponse(aaaaResp, expectedTxId = 0x2233, expectedQueryPacket = query))
    }

    @Test
    fun `issue 2 multi-question dns packet parses all questions and blocks when second question is blocked`() {
        val multiQuery = buildMultiQuestionPacket(0x5566, listOf("safe.example.com" to DnsPacket.TYPE_A, "doubleclick.net" to DnsPacket.TYPE_A))
        val parsed = DnsPacket.parseAllQuestions(multiQuery)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.questions.size)
        assertEquals("safe.example.com", parsed.questions[0].qName)
        assertEquals("doubleclick.net", parsed.questions[1].qName)

        val engine = DomainFilterEngine()
        val trie = ReversedDomainTrie().apply { insert("doubleclick.net", ProtectionCategory.ADS.bitMask) }
        engine.publishCategorySnapshot(trie, mapOf(ProtectionCategory.ADS to 1), emptySet(), emptySet())
        engine.updateActiveConfig(AppMode.PROTECTION, setOf(ProtectionCategory.ADS))

        val anyBlocked = parsed.questions.any { engine.evaluate(it.qName) is FilterDecision.Blocked }
        assertTrue(anyBlocked)

        val blockedResp = DnsPacket.buildBlockedResponse(multiQuery, parsed)
        assertEquals(DnsPacket.RCODE_NXDOMAIN, DnsPacket.extractRcode(blockedResp))
        assertTrue(DnsPacket.isValidResponse(blockedResp, expectedTxId = 0x5566, expectedQueryPacket = multiQuery))
    }

    @Test
    fun `issue 3 tcp dns session manager handles handshake framing segmented response and fin`() {
        val manager = TcpDnsSessionManager()
        val clientIp = byteArrayOf(10, 111, 222.toByte(), 2)
        val serverIp = byteArrayOf(10, 111, 222.toByte(), 1)

        // 1. Client SYN
        val synRaw = Ipv4UdpPacketParser.buildTcpResponseSegment(
            ipVersion = 4,
            srcIp = clientIp,
            dstIp = serverIp,
            srcPort = 45000,
            dstPort = 53,
            seqNum = 1000L,
            ackNum = 0L,
            flags = Ipv4UdpPacketParser.TCP_SYN
        )
        val synSeg = Ipv4UdpPacketParser.parseTcpDnsSegment(synRaw, synRaw.size)
        assertNotNull(synSeg)
        val synRes = manager.handleIncomingSegment(synSeg!!)
        assertEquals(1, synRes.immediatePackets.size)
        val synAck = Ipv4UdpPacketParser.parseTcpDnsSegment(
            swapPortsTo53ForInspection(synRes.immediatePackets.first()),
            synRes.immediatePackets.first().size
        )
        assertNotNull(synAck)
        assertTrue((synAck!!.flags and Ipv4UdpPacketParser.TCP_SYN) != 0)
        assertTrue((synAck.flags and Ipv4UdpPacketParser.TCP_ACK) != 0)

        // 2. Client sends 2-byte length-prefixed DNS query split across 2 TCP segments
        val query = DnsPacket.buildQueryPacket("tcp.example.com", transactionId = 0x7788)
        val framed = ByteArray(2 + query.size)
        framed[0] = ((query.size ushr 8) and 0xFF).toByte()
        framed[1] = (query.size and 0xFF).toByte()
        System.arraycopy(query, 0, framed, 2, query.size)

        val part1 = framed.copyOfRange(0, 5)
        val part2 = framed.copyOfRange(5, framed.size)

        val seg1Raw = Ipv4UdpPacketParser.buildTcpResponseSegment(
            ipVersion = 4, srcIp = clientIp, dstIp = serverIp, srcPort = 45000, dstPort = 53,
            seqNum = 1001L, ackNum = synAck.seqNum + 1L, flags = Ipv4UdpPacketParser.TCP_ACK, payload = part1
        )
        val res1 = manager.handleIncomingSegment(Ipv4UdpPacketParser.parseTcpDnsSegment(seg1Raw, seg1Raw.size)!!)
        assertTrue(res1.completedDnsQueries.isEmpty())

        val seg2Raw = Ipv4UdpPacketParser.buildTcpResponseSegment(
            ipVersion = 4, srcIp = clientIp, dstIp = serverIp, srcPort = 45000, dstPort = 53,
            seqNum = 1001L + part1.size, ackNum = synAck.seqNum + 1L,
            flags = Ipv4UdpPacketParser.TCP_PSH or Ipv4UdpPacketParser.TCP_ACK, payload = part2
        )
        val res2 = manager.handleIncomingSegment(Ipv4UdpPacketParser.parseTcpDnsSegment(seg2Raw, seg2Raw.size)!!)
        assertEquals(1, res2.completedDnsQueries.size)
        val (sessionKey, reassembledQuery) = res2.completedDnsQueries.first()
        assertArrayEquals(query, reassembledQuery)

        // 3. Build multi-segment response (> 1380 bytes)
        val largeResponse = ByteArray(1600) { 0x11 }
        val respSegments = manager.buildDnsResponseSegments(sessionKey, largeResponse)
        assertEquals(2, respSegments.size)

        // 4. Truncated UDP response sets TC=1
        val trunc = DnsPacket.buildTruncatedResponse(query, DnsPacket.parseAllQuestions(query)!!)
        assertTrue(DnsPacket.isTruncated(trunc))
    }

    @Test
    fun `issue 4 ipv6 udp and tcp packets parse with extension headers and build valid responses`() {
        val dnsQuery = DnsPacket.buildQueryPacket("ipv6.example.com", transactionId = 0x3344, qType = DnsPacket.TYPE_AAAA)
        val srcIp6 = byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 2, 0x22, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        val dstIp6 = byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 2, 0x22, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

        // Build IPv6 packet with 8-byte Hop-by-Hop extension header (NextHeader=0 -> 17 UDP)
        val udpLen = 8 + dnsQuery.size
        val payloadLen = 8 + udpLen
        val raw = ByteArray(40 + payloadLen)
        raw[0] = 0x60.toByte()
        raw[4] = ((payloadLen ushr 8) and 0xFF).toByte()
        raw[5] = (payloadLen and 0xFF).toByte()
        raw[6] = 0 // Hop-by-Hop Options extension header
        raw[7] = 64
        System.arraycopy(srcIp6, 0, raw, 8, 16)
        System.arraycopy(dstIp6, 0, raw, 24, 16)
        // Extension header (8 bytes): nextHeader = 17 (UDP), hdrExtLen = 0
        raw[40] = 17.toByte()
        raw[41] = 0
        // UDP header at offset 48
        raw[48] = (54321 ushr 8).toByte(); raw[49] = (54321 and 0xFF).toByte()
        raw[50] = 0; raw[51] = 53
        raw[52] = ((udpLen ushr 8) and 0xFF).toByte(); raw[53] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, raw, 56, dnsQuery.size)

        val parsed = Ipv4UdpPacketParser.parseUdpDnsPacket(raw, raw.size)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.ipVersion)
        assertEquals(54321, parsed.srcPort)
        assertArrayEquals(dnsQuery, parsed.dnsPayload)

        val resp = Ipv4UdpPacketParser.buildUdpResponsePacket(parsed, dnsQuery)
        assertEquals(0x60, resp[0].toInt() and 0xF0)
        assertEquals(17, resp[6].toInt() and 0xFF)
        assertArrayEquals(dstIp6, resp.copyOfRange(8, 24))
        assertArrayEquals(srcIp6, resp.copyOfRange(24, 40))

        // IPv6 custom DNS validation
        assertTrue(MainViewModel.isValidIpv6("2606:4700:4700::1111"))
        assertTrue(MainViewModel.isValidIpAddress("2001:4860:4860::8888"))
        assertFalse(MainViewModel.isValidIpv6("2606::4700::1111"))
        assertFalse(MainViewModel.isValidIpAddress("not-an-ip"))
    }

    @Test
    fun `issues 5 6 7 ping forge transport accuracy secondary fallback ranking and 80 percent threshold`() {
        val cloudflare = BuiltInResolvers.defaultResolvers.first { it.id == "cloudflare" }
        var dohCalls = 0
        var udpIpsCalled = mutableListOf<String>()
        val dummyResp = DnsPacket.buildBlockedResponse(
            DnsPacket.buildQueryPacket("example.com", 1),
            DnsPacket.parseQuestion(DnsPacket.buildQueryPacket("example.com", 1))!!
        )

        // Issue 5: DoH selected -> fails -> MUST NOT silently fall back to UDP and report success
        val dohFailEngine = PingForgeEngine(
            upstreamClient = UpstreamDnsClient(),
            settingsRepository = FakeStubs.unusedSettingsRepo(),
            telemetryRepository = FakeStubs.unusedTelemetryRepo(),
            dohProbe = { _, _ -> dohCalls++; null },
            udpProbe = { _, ip, _ -> udpIpsCalled.add(ip); dummyResp to 10L }
        )
        val dohResult = dohFailEngine.benchmarkSingleResolver(cloudflare, preferDoH = true, transportLabel = "Wi-Fi")
        assertEquals(5, dohCalls)
        assertTrue("UDP must not be called during DoH benchmark", udpIpsCalled.isEmpty())
        assertEquals(0, dohResult.successCount)
        assertEquals(0, dohResult.reliabilityPercent)
        assertTrue(dohResult.resolverAddress.startsWith("DoH"))

        // Issue 6: UDP selected -> probes BOTH primary and secondary IPs
        udpIpsCalled.clear()
        val udpEngine = PingForgeEngine(
            upstreamClient = UpstreamDnsClient(),
            settingsRepository = FakeStubs.unusedSettingsRepo(),
            telemetryRepository = FakeStubs.unusedTelemetryRepo(),
            dohProbe = { _, _ -> error("DoH should not be called in UDP mode") },
            udpProbe = { _, ip, _ -> udpIpsCalled.add(ip); dummyResp to 15L }
        )
        val udpResult = udpEngine.benchmarkSingleResolver(cloudflare, preferDoH = false, transportLabel = "Wi-Fi")
        assertTrue(udpIpsCalled.contains(cloudflare.primaryIp))
        assertTrue(udpIpsCalled.contains(cloudflare.secondaryIp))
        assertEquals(8, udpResult.totalCount)
        assertEquals(100, udpResult.reliabilityPercent)
        assertTrue(udpResult.resolverAddress.startsWith("UDP"))

        // Issue 6 & 7: Fallback ordering and 80% automatic selection threshold
        val unreliableFast = BenchmarkResult("quad9", "Quad9", "9.9.9.9", medianMs = 5.0, avgMs = 5.0, minMs = 5.0, maxMs = 5.0, successCount = 3, totalCount = 5, reliabilityPercent = 60, failedQueries = 2, lastError = "timeout", measuredAtMillis = 1L, transportLabel = "Wi-Fi")
        val reliableFast = BenchmarkResult("google", "Google", "8.8.8.8", medianMs = 14.0, avgMs = 14.0, minMs = 12.0, maxMs = 16.0, successCount = 5, totalCount = 5, reliabilityPercent = 100, failedQueries = 0, lastError = null, measuredAtMillis = 1L, transportLabel = "Wi-Fi")
        val reliableSlower = BenchmarkResult("cloudflare", "Cloudflare", "1.1.1.1", medianMs = 22.0, avgMs = 22.0, minMs = 20.0, maxMs = 24.0, successCount = 5, totalCount = 5, reliabilityPercent = 100, failedQueries = 0, lastError = null, measuredAtMillis = 1L, transportLabel = "Wi-Fi")
        val deadResolver = BenchmarkResult("adguard", "AdGuard", "94.140.14.14", medianMs = null, avgMs = null, minMs = null, maxMs = null, successCount = 0, totalCount = 5, reliabilityPercent = 0, failedQueries = 5, lastError = "dead", measuredAtMillis = 1L, transportLabel = "Wi-Fi")

        // Unreliable-only list returns null recommendation
        assertNull(PingForgeEngine.pickRecommended(listOf(unreliableFast, deadResolver)))

        // Among reliable >= 80%, fastest wins over 60%-reliable faster resolver
        val ranked = PingForgeEngine.rankResults(listOf(unreliableFast, reliableSlower, deadResolver, reliableFast))
        val best = PingForgeEngine.pickRecommended(ranked)
        assertEquals("google", best?.resolverId)

        udpEngine.recordBenchmarkRanking(ranked)
        val fallbacks = udpEngine.getRankedFallbackResolvers(
            primary = BuiltInResolvers.defaultResolvers.first { it.id == "google" },
            availableResolvers = BuiltInResolvers.defaultResolvers
        )
        assertEquals("cloudflare", fallbacks.first().id)
        assertFalse("Dead 0%-reliable resolver should be excluded when healthy fallbacks exist", fallbacks.any { it.id == "adguard" })

        // SettingsRepository resolves Custom DNS when resolvedAutomaticId == "custom"
        val autoCustomSettings = AppSettings(
            selectedResolverId = BuiltInResolvers.AUTOMATIC_RESOLVER_ID,
            autoSelectFastestResolver = true,
            resolvedAutomaticId = BuiltInResolvers.CUSTOM_RESOLVER_ID,
            customDnsPrimary = "2606:4700:4700::1111"
        )
        val resolvedCustom = SettingsRepository.resolveActiveResolver(autoCustomSettings)
        assertEquals(BuiltInResolvers.CUSTOM_RESOLVER_ID, resolvedCustom.id)
        assertEquals("2606:4700:4700::1111", resolvedCustom.primaryIp)
    }

    @Test
    fun `ipv4 udp parser parses DNS packet and produces valid response packet`() {
        val dnsQuery = DnsPacket.buildQueryPacket("example.com", transactionId = 0x4242)
        val totalLen = 20 + 8 + dnsQuery.size
        val raw = ByteArray(totalLen)
        raw[0] = 0x45.toByte()
        raw[2] = ((totalLen ushr 8) and 0xFF).toByte()
        raw[3] = (totalLen and 0xFF).toByte()
        raw[9] = 17.toByte()
        raw[12] = 10; raw[13] = 111; raw[14] = 222.toByte(); raw[15] = 2
        raw[16] = 10; raw[17] = 111; raw[18] = 222.toByte(); raw[19] = 1
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
        assertEquals(1.toByte(), resp[15])
        assertEquals(2.toByte(), resp[19])
        assertEquals(53, ((resp[20].toInt() and 0xFF) shl 8) or (resp[21].toInt() and 0xFF))
        assertEquals(51234, ((resp[22].toInt() and 0xFF) shl 8) or (resp[23].toInt() and 0xFF))
    }

    @Test
    fun `issue 8 dns cache respects ttl expiration qclass resolver isolation and refuses failed responses`() {
        val cache = DnsCache(maxEntries = 10)
        val query = DnsPacket.buildQueryPacket("example.com", transactionId = 0x1111, qType = DnsPacket.TYPE_A)
        val parsed = DnsPacket.parseQuestion(query)!!
        val validResp = DnsPacket.buildBlockedResponse(query, parsed) // NOERROR, 1 answer, TTL=60
        val servFailResp = DnsPacket.buildServFailResponse(query, parsed)
        val truncatedResp = DnsPacket.buildTruncatedResponse(query, DnsPacket.parseAllQuestions(query)!!)

        // Refuses SERVFAIL, truncated, malformed, and TTL=0 responses
        assertFalse(cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", servFailResp, ttlSeconds = 60, maxConfiguredTtlSeconds = 300, nowMillis = 1000L))
        assertFalse(cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", truncatedResp, ttlSeconds = 60, maxConfiguredTtlSeconds = 300, nowMillis = 1000L))
        assertFalse(cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", byteArrayOf(1, 2, 3, 4), ttlSeconds = 60, maxConfiguredTtlSeconds = 300, nowMillis = 1000L))
        assertFalse(cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", validResp, ttlSeconds = 0, maxConfiguredTtlSeconds = 300, nowMillis = 1000L))

        // Stores valid response and isolates by resolverKey and qClass
        assertTrue(cache.put("example.com", DnsPacket.TYPE_A, "cloudflare", validResp, ttlSeconds = 30, maxConfiguredTtlSeconds = 300, nowMillis = 1000L, qClass = DnsPacket.CLASS_IN))
        val hit = cache.getWithMetadata("example.com", DnsPacket.TYPE_A, DnsPacket.CLASS_IN, "cloudflare", nowMillis = 11_000L)
        assertNotNull(hit)
        assertEquals(10, hit!!.elapsedSeconds)
        assertNull(cache.get("example.com", DnsPacket.TYPE_A, "google", nowMillis = 11_000L))
        assertNull(cache.get("example.com", DnsPacket.TYPE_A, "cloudflare", nowMillis = 11_000L, qClass = 3))
        assertNull(cache.get("example.com", DnsPacket.TYPE_A, "cloudflare", nowMillis = 35_000L))

        // rewriteResponseForCache decrements TTL accurately (60s - 10s = 50s)
        val decremented = DnsPacket.rewriteResponseForCache(hit.responseBytes, newTxId = 0x2222, elapsedSeconds = hit.elapsedSeconds)
        assertEquals(0x2222, DnsPacket.extractTransactionId(decremented))
        assertEquals(50, DnsPacket.extractMinTtlSeconds(decremented))
    }

    @Test
    fun `issue 9 blocklist atomic file replacement preserves existing file on failure and computes schedule`() {
        val tempDir = Files.createTempDirectory("pingforge_blocklist_test").toFile()
        try {
            val target = File(tempDir, "ads.txt").apply { writeText("old-ad.example.com\n") }
            val emptyTemp = File(tempDir, "ads.empty.tmp").apply { writeText("") }

            // Empty/invalid temp file must NOT overwrite or delete existing blocklist
            assertFalse(BlocklistRepository.replaceFileAtomically(emptyTemp, target))
            assertTrue(target.exists())
            assertEquals("old-ad.example.com\n", target.readText())

            // Valid temp file replaces target cleanly
            val goodTemp = File(tempDir, "ads.good.tmp").apply { writeText("new-ad.example.com\n") }
            assertTrue(BlocklistRepository.replaceFileAtomically(goodTemp, target))
            assertEquals("new-ad.example.com\n", target.readText())
            assertFalse(goodTemp.exists())

            // Auto-update schedule logic
            val now = 100_000_000L
            assertTrue(BlocklistRepository.isAutoUpdateDue(lastUpdatedMillis = 0L, intervalHours = 24, nowMillis = now))
            assertFalse(BlocklistRepository.isAutoUpdateDue(lastUpdatedMillis = now - 3_600_000L, intervalHours = 24, nowMillis = now))
            assertTrue(BlocklistRepository.isAutoUpdateDue(lastUpdatedMillis = now - 25 * 3_600_000L, intervalHours = 24, nowMillis = now))
            assertEquals(
                23 * 3_600_000L,
                BlocklistRepository.computeNextUpdateDelayMillis(lastUpdatedMillis = now - 3_600_000L, intervalHours = 24, nowMillis = now)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `ipv4 validation accepts valid addresses and rejects invalid ones`() {
        assertTrue(MainViewModel.isValidIpv4("1.1.1.1"))
        assertTrue(MainViewModel.isValidIpv4("94.140.14.14"))
        assertFalse(MainViewModel.isValidIpv4("256.1.1.1"))
        assertFalse(MainViewModel.isValidIpv4("1.1.1"))
        assertFalse(MainViewModel.isValidIpv4("dns.google"))
    }

    private fun buildMultiQuestionPacket(txId: Int, questions: List<Pair<String, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((txId ushr 8) and 0xFF); out.write(txId and 0xFF)
        out.write(0x01); out.write(0x00) // RD=1, QR=0
        out.write((questions.size ushr 8) and 0xFF); out.write(questions.size and 0xFF)
        repeat(6) { out.write(0x00) }
        for ((domain, qType) in questions) {
            for (label in domain.split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                out.write(b.size)
                out.write(b)
            }
            out.write(0x00)
            out.write((qType ushr 8) and 0xFF); out.write(qType and 0xFF)
            out.write(0x00); out.write(0x01)
        }
        return out.toByteArray()
    }

    private fun swapPortsTo53ForInspection(rawIpv4Tcp: ByteArray): ByteArray {
        val copy = rawIpv4Tcp.copyOf()
        // Swap srcPort (20..21) and dstPort (22..23) so parseTcpDnsSegment (which filters dstPort==53) parses server->client segments
        val s0 = copy[20]; val s1 = copy[21]
        copy[20] = copy[22]; copy[21] = copy[23]
        copy[22] = s0; copy[23] = s1
        return copy
    }

    private object FakeStubs {
        fun unusedSettingsRepo(): SettingsRepository {
            return allocateWithoutConstructor(SettingsRepository::class.java)
        }

        fun unusedTelemetryRepo(): com.example.data.local.TelemetryRepository {
            return allocateWithoutConstructor(com.example.data.local.TelemetryRepository::class.java)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> allocateWithoutConstructor(clazz: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return method.invoke(unsafe, clazz) as T
        }
    }
}

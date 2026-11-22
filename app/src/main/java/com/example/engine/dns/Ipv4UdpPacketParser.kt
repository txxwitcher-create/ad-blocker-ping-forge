package com.example.engine.dns

import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Parses IPv4 and IPv6 UDP/TCP port 53 DNS packets from the TUN interface
 * and constructs valid IPv4/IPv6 UDP and TCP response packets with RFC-compliant checksums.
 */
object Ipv4UdpPacketParser {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class UdpDnsPacket(
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray,
        val ipVersion: Int = 4
    )

    data class TcpDnsSegment(
        val ipVersion: Int,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val flags: Int,
        val windowSize: Int,
        val payload: ByteArray
    )

    private data class IpHeaderInfo(
        val version: Int,
        val protocol: Int,
        val payloadOffset: Int,
        val packetEnd: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray
    )

    fun parseUdpDnsPacket(raw: ByteArray, length: Int): UdpDnsPacket? {
        val ip = parseIpHeader(raw, length) ?: return null
        if (ip.protocol != PROTOCOL_UDP) return null
        if (ip.packetEnd < ip.payloadOffset + 8) return null

        val u = ip.payloadOffset
        val srcPort = ((raw[u].toInt() and 0xFF) shl 8) or (raw[u + 1].toInt() and 0xFF)
        val dstPort = ((raw[u + 2].toInt() and 0xFF) shl 8) or (raw[u + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        val udpLen = ((raw[u + 4].toInt() and 0xFF) shl 8) or (raw[u + 5].toInt() and 0xFF)
        val payloadStart = u + 8
        val payloadEnd = if (udpLen == 0 && ip.version == 6) {
            ip.packetEnd
        } else {
            minOf(ip.packetEnd, u + udpLen)
        }
        if (payloadEnd <= payloadStart) return null

        return UdpDnsPacket(
            srcIpBytes = ip.srcIp,
            dstIpBytes = ip.dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = raw.copyOfRange(payloadStart, payloadEnd),
            ipVersion = ip.version
        )
    }

    fun parseTcpDnsSegment(raw: ByteArray, length: Int): TcpDnsSegment? {
        val ip = parseIpHeader(raw, length) ?: return null
        if (ip.protocol != PROTOCOL_TCP) return null
        if (ip.packetEnd < ip.payloadOffset + 20) return null

        val t = ip.payloadOffset
        val srcPort = ((raw[t].toInt() and 0xFF) shl 8) or (raw[t + 1].toInt() and 0xFF)
        val dstPort = ((raw[t + 2].toInt() and 0xFF) shl 8) or (raw[t + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        val seqNum = readUint32(raw, t + 4)
        val ackNum = readUint32(raw, t + 8)
        val dataOffsetBytes = ((raw[t + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffsetBytes < 20 || t + dataOffsetBytes > ip.packetEnd) return null

        val flags = raw[t + 13].toInt() and 0xFF
        val windowSize = ((raw[t + 14].toInt() and 0xFF) shl 8) or (raw[t + 15].toInt() and 0xFF)
        val payloadStart = t + dataOffsetBytes
        val payload = if (payloadStart < ip.packetEnd) {
            raw.copyOfRange(payloadStart, ip.packetEnd)
        } else {
            ByteArray(0)
        }

        return TcpDnsSegment(
            ipVersion = ip.version,
            srcIpBytes = ip.srcIp,
            dstIpBytes = ip.dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            seqNum = seqNum,
            ackNum = ackNum,
            flags = flags,
            windowSize = windowSize,
            payload = payload
        )
    }

    fun buildUdpResponsePacket(request: UdpDnsPacket, dnsResponsePayload: ByteArray): ByteArray {
        return if (request.ipVersion == 6 || request.srcIpBytes.size == 16) {
            buildIpv6UdpResponsePacket(request, dnsResponsePayload)
        } else {
            buildIpv4UdpResponsePacket(request, dnsResponsePayload)
        }
    }

    fun buildTcpResponseSegment(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        windowSize: Int = 65535
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val ipHeaderLen = if (ipVersion == 6) 40 else 20
        val totalLen = ipHeaderLen + tcpLen
        val packet = ByteArray(totalLen)

        if (ipVersion == 6) {
            writeIpv6Header(packet, payloadLength = tcpLen, nextHeader = PROTOCOL_TCP, srcIp = srcIp, dstIp = dstIp)
        } else {
            writeIpv4Header(packet, totalLen = totalLen, protocol = PROTOCOL_TCP, srcIp = srcIp, dstIp = dstIp)
        }

        val t = ipHeaderLen
        packet[t] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[t + 1] = (srcPort and 0xFF).toByte()
        packet[t + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[t + 3] = (dstPort and 0xFF).toByte()
        writeUint32(packet, t + 4, seqNum)
        writeUint32(packet, t + 8, ackNum)
        packet[t + 12] = 0x50.toByte() // data offset = 5 (20 bytes)
        packet[t + 13] = (flags and 0xFF).toByte()
        packet[t + 14] = ((windowSize ushr 8) and 0xFF).toByte()
        packet[t + 15] = (windowSize and 0xFF).toByte()
        packet[t + 16] = 0x00; packet[t + 17] = 0x00 // checksum placeholder
        packet[t + 18] = 0x00; packet[t + 19] = 0x00 // urgent pointer

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, t + 20, payload.size)
        }

        val checksum = computeTransportChecksum(
            ipVersion = ipVersion,
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = PROTOCOL_TCP,
            transportPacket = packet,
            transportOffset = t,
            transportLength = tcpLen,
            zeroAsFfff = false
        )
        packet[t + 16] = ((checksum ushr 8) and 0xFF).toByte()
        packet[t + 17] = (checksum and 0xFF).toByte()
        return packet
    }

    private fun buildIpv4UdpResponsePacket(request: UdpDnsPacket, dnsResponsePayload: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + dnsResponsePayload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        writeIpv4Header(
            packet = packet,
            totalLen = totalLen,
            protocol = PROTOCOL_UDP,
            srcIp = request.dstIpBytes,
            dstIp = request.srcIpBytes
        )

        packet[20] = ((request.dstPort ushr 8) and 0xFF).toByte()
        packet[21] = (request.dstPort and 0xFF).toByte()
        packet[22] = ((request.srcPort ushr 8) and 0xFF).toByte()
        packet[23] = (request.srcPort and 0xFF).toByte()
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00; packet[27] = 0x00
        System.arraycopy(dnsResponsePayload, 0, packet, 28, dnsResponsePayload.size)

        val udpChecksum = computeTransportChecksum(
            ipVersion = 4,
            srcIp = request.dstIpBytes,
            dstIp = request.srcIpBytes,
            protocol = PROTOCOL_UDP,
            transportPacket = packet,
            transportOffset = 20,
            transportLength = udpLen,
            zeroAsFfff = true
        )
        packet[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[27] = (udpChecksum and 0xFF).toByte()
        return packet
    }

    private fun buildIpv6UdpResponsePacket(request: UdpDnsPacket, dnsResponsePayload: ByteArray): ByteArray {
        val ipHeaderLen = 40
        val udpLen = 8 + dnsResponsePayload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        writeIpv6Header(
            packet = packet,
            payloadLength = udpLen,
            nextHeader = PROTOCOL_UDP,
            srcIp = request.dstIpBytes,
            dstIp = request.srcIpBytes
        )

        packet[40] = ((request.dstPort ushr 8) and 0xFF).toByte()
        packet[41] = (request.dstPort and 0xFF).toByte()
        packet[42] = ((request.srcPort ushr 8) and 0xFF).toByte()
        packet[43] = (request.srcPort and 0xFF).toByte()
        packet[44] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[45] = (udpLen and 0xFF).toByte()
        packet[46] = 0x00; packet[47] = 0x00
        System.arraycopy(dnsResponsePayload, 0, packet, 48, dnsResponsePayload.size)

        val udpChecksum = computeTransportChecksum(
            ipVersion = 6,
            srcIp = request.dstIpBytes,
            dstIp = request.srcIpBytes,
            protocol = PROTOCOL_UDP,
            transportPacket = packet,
            transportOffset = 40,
            transportLength = udpLen,
            zeroAsFfff = true
        )
        packet[46] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[47] = (udpChecksum and 0xFF).toByte()
        return packet
    }

    private fun parseIpHeader(raw: ByteArray, length: Int): IpHeaderInfo? {
        if (length < 20 || length > raw.size) return null
        val version = (raw[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> {
                val ihlBytes = (raw[0].toInt() and 0x0F) * 4
                if (ihlBytes < 20 || length < ihlBytes) return null
                val totalLength = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
                val effectiveLen = minOf(length, totalLength)
                if (effectiveLen < ihlBytes) return null
                val protocol = raw[9].toInt() and 0xFF
                IpHeaderInfo(
                    version = 4,
                    protocol = protocol,
                    payloadOffset = ihlBytes,
                    packetEnd = effectiveLen,
                    srcIp = raw.copyOfRange(12, 16),
                    dstIp = raw.copyOfRange(16, 20)
                )
            }

            6 -> {
                if (length < 40) return null
                val payloadLen = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
                val effectiveLen = minOf(length, 40 + payloadLen)
                if (effectiveLen < 40) return null
                var nextHeader = raw[6].toInt() and 0xFF
                var offset = 40
                var hops = 0
                while (nextHeader in setOf(0, 43, 44, 60) && hops++ < 8) {
                    if (nextHeader == 44) {
                        // Fragment header is fixed 8 bytes; skip only non-fragmented/first fragment
                        if (offset + 8 > effectiveLen) return null
                        val fragOffset = (((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)) ushr 3
                        if (fragOffset != 0) return null
                        nextHeader = raw[offset].toInt() and 0xFF
                        offset += 8
                    } else {
                        if (offset + 2 > effectiveLen) return null
                        nextHeader = raw[offset].toInt() and 0xFF
                        val extLen = ((raw[offset + 1].toInt() and 0xFF) + 1) * 8
                        offset += extLen
                        if (offset > effectiveLen) return null
                    }
                }
                IpHeaderInfo(
                    version = 6,
                    protocol = nextHeader,
                    payloadOffset = offset,
                    packetEnd = effectiveLen,
                    srcIp = raw.copyOfRange(8, 24),
                    dstIp = raw.copyOfRange(24, 40)
                )
            }

            else -> null
        }
    }

    private fun writeIpv4Header(
        packet: ByteArray,
        totalLen: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00; packet[5] = 0x00
        packet[6] = 0x40; packet[7] = 0x00
        packet[8] = 64.toByte()
        packet[9] = protocol.toByte()
        packet[10] = 0x00; packet[11] = 0x00
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()
    }

    private fun writeIpv6Header(
        packet: ByteArray,
        payloadLength: Int,
        nextHeader: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        packet[0] = 0x60.toByte() // Version 6
        packet[1] = 0x00; packet[2] = 0x00; packet[3] = 0x00
        packet[4] = ((payloadLength ushr 8) and 0xFF).toByte()
        packet[5] = (payloadLength and 0xFF).toByte()
        packet[6] = nextHeader.toByte()
        packet[7] = 64.toByte() // Hop limit
        System.arraycopy(srcIp, 0, packet, 8, 16)
        System.arraycopy(dstIp, 0, packet, 24, 16)
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeTransportChecksum(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        transportPacket: ByteArray,
        transportOffset: Int,
        transportLength: Int,
        zeroAsFfff: Boolean
    ): Int {
        var sum = 0L
        val ipBytesLen = if (ipVersion == 6) 16 else 4
        var j = 0
        while (j < ipBytesLen) {
            sum += ((srcIp[j].toInt() and 0xFF) shl 8) or (srcIp[j + 1].toInt() and 0xFF)
            sum += ((dstIp[j].toInt() and 0xFF) shl 8) or (dstIp[j + 1].toInt() and 0xFF)
            j += 2
        }
        if (ipVersion == 6) {
            sum += (transportLength ushr 16).toLong() and 0xFFFFL
            sum += (transportLength and 0xFFFF).toLong()
            sum += protocol.toLong()
        } else {
            sum += protocol.toLong()
            sum += transportLength.toLong()
        }

        var i = transportOffset
        val end = transportOffset + transportLength
        while (i < end - 1) {
            val word = ((transportPacket[i].toInt() and 0xFF) shl 8) or (transportPacket[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (transportPacket[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        return if (zeroAsFfff && result == 0) 0xFFFF else result
    }

    private fun readUint32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)
    }

    private fun writeUint32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFFL).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        data[offset + 3] = (value and 0xFFL).toByte()
    }
}

/**
 * Bounded, lightweight stateful TCP-over-TUN responder for local DNS port 53 (IPv4 & IPv6).
 * Handles SYN/SYN-ACK handshake, RFC 1035 2-byte length-prefixed DNS query reassembly,
 * segmented PSH+ACK response transmission, FIN/RST teardown, and idle eviction.
 */
class TcpDnsSessionManager(
    private val maxSessions: Int = 64,
    private val idleTimeoutMs: Long = 10_000L
) {
    data class SessionKey(
        val ipVersion: Int,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SessionKey) return false
            return ipVersion == other.ipVersion &&
                clientPort == other.clientPort &&
                serverPort == other.serverPort &&
                clientIp.contentEquals(other.clientIp) &&
                serverIp.contentEquals(other.serverIp)
        }

        override fun hashCode(): Int {
            var r = ipVersion
            r = 31 * r + Arrays.hashCode(clientIp)
            r = 31 * r + Arrays.hashCode(serverIp)
            r = 31 * r + clientPort
            r = 31 * r + serverPort
            return r
        }
    }

    data class InboundResult(
        val immediatePackets: List<ByteArray> = emptyList(),
        val completedDnsQueries: List<Pair<SessionKey, ByteArray>> = emptyList()
    )

    private class SessionState(
        val key: SessionKey,
        var serverSeq: Long,
        var clientNextSeq: Long,
        var lastActivityMs: Long,
        val recvBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<SessionKey, SessionState>(32, 0.75f, true)
    private var seqSeed: Long = 0x10203040L

    fun handleIncomingSegment(
        segment: Ipv4UdpPacketParser.TcpDnsSegment,
        nowMs: Long = System.currentTimeMillis()
    ): InboundResult {
        val key = SessionKey(
            ipVersion = segment.ipVersion,
            clientIp = segment.srcIpBytes,
            serverIp = segment.dstIpBytes,
            clientPort = segment.srcPort,
            serverPort = segment.dstPort
        )

        synchronized(lock) {
            evictExpiredLocked(nowMs)

            // RST tears down session immediately
            if ((segment.flags and Ipv4UdpPacketParser.TCP_RST) != 0) {
                sessions.remove(key)
                return InboundResult()
            }

            // SYN initiates a new TCP DNS session
            if ((segment.flags and Ipv4UdpPacketParser.TCP_SYN) != 0 &&
                (segment.flags and Ipv4UdpPacketParser.TCP_ACK) == 0
            ) {
                if (sessions.size >= maxSessions) {
                    val oldestKey = sessions.entries.firstOrNull()?.key
                    if (oldestKey != null) sessions.remove(oldestKey)
                }
                seqSeed = (seqSeed + 0x10001L) and 0xFFFFFFFFL
                val initialServerSeq = seqSeed
                val clientNext = (segment.seqNum + 1L) and 0xFFFFFFFFL
                val state = SessionState(
                    key = key,
                    serverSeq = (initialServerSeq + 1L) and 0xFFFFFFFFL,
                    clientNextSeq = clientNext,
                    lastActivityMs = nowMs
                )
                sessions[key] = state
                val synAck = Ipv4UdpPacketParser.buildTcpResponseSegment(
                    ipVersion = key.ipVersion,
                    srcIp = key.serverIp,
                    dstIp = key.clientIp,
                    srcPort = key.serverPort,
                    dstPort = key.clientPort,
                    seqNum = initialServerSeq,
                    ackNum = clientNext,
                    flags = Ipv4UdpPacketParser.TCP_SYN or Ipv4UdpPacketParser.TCP_ACK
                )
                return InboundResult(immediatePackets = listOf(synAck))
            }

            val state = sessions[key] ?: return InboundResult()
            state.lastActivityMs = nowMs

            val outgoing = mutableListOf<ByteArray>()
            val completedQueries = mutableListOf<Pair<SessionKey, ByteArray>>()

            if (segment.payload.isNotEmpty()) {
                // Accept in-order data (ignore retransmissions of already-acked data)
                if (segment.seqNum == state.clientNextSeq) {
                    state.recvBuffer.write(segment.payload)
                    state.clientNextSeq = (state.clientNextSeq + segment.payload.size.toLong()) and 0xFFFFFFFFL

                    // Acknowledge received data
                    outgoing.add(
                        Ipv4UdpPacketParser.buildTcpResponseSegment(
                            ipVersion = key.ipVersion,
                            srcIp = key.serverIp,
                            dstIp = key.clientIp,
                            srcPort = key.serverPort,
                            dstPort = key.clientPort,
                            seqNum = state.serverSeq,
                            ackNum = state.clientNextSeq,
                            flags = Ipv4UdpPacketParser.TCP_ACK
                        )
                    )

                    // Extract all complete 2-byte length-prefixed RFC 1035 TCP DNS messages
                    var buffered = state.recvBuffer.toByteArray()
                    var consumed = 0
                    while (buffered.size - consumed >= 2) {
                        val msgLen = ((buffered[consumed].toInt() and 0xFF) shl 8) or
                            (buffered[consumed + 1].toInt() and 0xFF)
                        if (msgLen < 12 || msgLen > 4096) {
                            // Invalid TCP DNS framing; reset session
                            sessions.remove(key)
                            outgoing.add(
                                Ipv4UdpPacketParser.buildTcpResponseSegment(
                                    ipVersion = key.ipVersion,
                                    srcIp = key.serverIp,
                                    dstIp = key.clientIp,
                                    srcPort = key.serverPort,
                                    dstPort = key.clientPort,
                                    seqNum = state.serverSeq,
                                    ackNum = state.clientNextSeq,
                                    flags = Ipv4UdpPacketParser.TCP_RST or Ipv4UdpPacketParser.TCP_ACK
                                )
                            )
                            return InboundResult(immediatePackets = outgoing)
                        }
                        if (buffered.size - consumed - 2 < msgLen) break
                        val query = buffered.copyOfRange(consumed + 2, consumed + 2 + msgLen)
                        completedQueries.add(key to query)
                        consumed += 2 + msgLen
                    }
                    if (consumed > 0) {
                        val remaining = buffered.copyOfRange(consumed, buffered.size)
                        state.recvBuffer.reset()
                        if (remaining.isNotEmpty()) state.recvBuffer.write(remaining)
                    }
                }
            }

            if ((segment.flags and Ipv4UdpPacketParser.TCP_FIN) != 0) {
                state.clientNextSeq = (state.clientNextSeq + 1L) and 0xFFFFFFFFL
                outgoing.add(
                    Ipv4UdpPacketParser.buildTcpResponseSegment(
                        ipVersion = key.ipVersion,
                        srcIp = key.serverIp,
                        dstIp = key.clientIp,
                        srcPort = key.serverPort,
                        dstPort = key.clientPort,
                        seqNum = state.serverSeq,
                        ackNum = state.clientNextSeq,
                        flags = Ipv4UdpPacketParser.TCP_FIN or Ipv4UdpPacketParser.TCP_ACK
                    )
                )
                state.serverSeq = (state.serverSeq + 1L) and 0xFFFFFFFFL
                sessions.remove(key)
            }

            return InboundResult(
                immediatePackets = outgoing,
                completedDnsQueries = completedQueries
            )
        }
    }

    /**
     * Frames a DNS response with 2-byte RFC 1035 TCP length prefix and segments it
     * to fit within TUN MTU (max 1380 bytes per segment).
     */
    fun buildDnsResponseSegments(
        key: SessionKey,
        dnsResponsePayload: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): List<ByteArray> {
        synchronized(lock) {
            val state = sessions[key] ?: return emptyList()
            state.lastActivityMs = nowMs

            val framed = ByteArray(2 + dnsResponsePayload.size)
            framed[0] = ((dnsResponsePayload.size ushr 8) and 0xFF).toByte()
            framed[1] = (dnsResponsePayload.size and 0xFF).toByte()
            System.arraycopy(dnsResponsePayload, 0, framed, 2, dnsResponsePayload.size)

            val mss = if (key.ipVersion == 6) 1360 else 1380
            val packets = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < framed.size) {
                val chunkEnd = minOf(framed.size, offset + mss)
                val chunk = framed.copyOfRange(offset, chunkEnd)
                val isLast = chunkEnd == framed.size
                val flags = if (isLast) {
                    Ipv4UdpPacketParser.TCP_PSH or Ipv4UdpPacketParser.TCP_ACK
                } else {
                    Ipv4UdpPacketParser.TCP_ACK
                }
                packets.add(
                    Ipv4UdpPacketParser.buildTcpResponseSegment(
                        ipVersion = key.ipVersion,
                        srcIp = key.serverIp,
                        dstIp = key.clientIp,
                        srcPort = key.serverPort,
                        dstPort = key.clientPort,
                        seqNum = state.serverSeq,
                        ackNum = state.clientNextSeq,
                        flags = flags,
                        payload = chunk
                    )
                )
                state.serverSeq = (state.serverSeq + chunk.size.toLong()) and 0xFFFFFFFFL
                offset = chunkEnd
            }
            return packets
        }
    }

    fun clear() {
        synchronized(lock) {
            sessions.clear()
        }
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowMs - entry.value.lastActivityMs > idleTimeoutMs) {
                it.remove()
            }
        }
    }
}

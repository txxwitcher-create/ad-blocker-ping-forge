package com.example.engine.dns

/**
 * Parses IPv4 + UDP packets from the TUN interface and constructs valid IPv4 + UDP response packets.
 */
object Ipv4UdpPacketParser {

    data class UdpDnsPacket(
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray
    )

    fun parseUdpDnsPacket(raw: ByteArray, length: Int): UdpDnsPacket? {
        if (length < 28) return null
        val version = (raw[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihlBytes = (raw[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes + 8) return null

        val protocol = raw[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP = 17

        val totalLength = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val effectiveLen = minOf(length, totalLength)
        if (effectiveLen < ihlBytes + 8) return null

        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)

        val srcPort = ((raw[ihlBytes].toInt() and 0xFF) shl 8) or (raw[ihlBytes + 1].toInt() and 0xFF)
        val dstPort = ((raw[ihlBytes + 2].toInt() and 0xFF) shl 8) or (raw[ihlBytes + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        val udpLen = ((raw[ihlBytes + 4].toInt() and 0xFF) shl 8) or (raw[ihlBytes + 5].toInt() and 0xFF)
        val payloadStart = ihlBytes + 8
        val payloadEnd = minOf(effectiveLen, ihlBytes + udpLen)
        if (payloadEnd <= payloadStart) return null

        val dnsPayload = raw.copyOfRange(payloadStart, payloadEnd)
        return UdpDnsPacket(
            srcIpBytes = srcIp,
            dstIpBytes = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload
        )
    }

    fun buildUdpResponsePacket(request: UdpDnsPacket, dnsResponsePayload: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + dnsResponsePayload.size
        val totalLen = ipHeaderLen + udpLen

        val packet = ByteArray(totalLen)

        // IPv4 Header (swapping src and dst IP)
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[1] = 0x00 // DSCP/ECN
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00; packet[5] = 0x00 // Identification
        packet[6] = 0x40; packet[7] = 0x00 // Don't Fragment
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP
        // Checksum [10..11] filled below
        System.arraycopy(request.dstIpBytes, 0, packet, 12, 4) // New Src = Old Dst
        System.arraycopy(request.srcIpBytes, 0, packet, 16, 4) // New Dst = Old Src

        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (swapping src and dst port)
        packet[20] = ((request.dstPort ushr 8) and 0xFF).toByte()
        packet[21] = (request.dstPort and 0xFF).toByte()
        packet[22] = ((request.srcPort ushr 8) and 0xFF).toByte()
        packet[23] = (request.srcPort and 0xFF).toByte()
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00; packet[27] = 0x00

        System.arraycopy(dnsResponsePayload, 0, packet, 28, dnsResponsePayload.size)

        val udpChecksum = computeUdpChecksum(
            srcIp = request.dstIpBytes,
            dstIp = request.srcIpBytes,
            udpPacket = packet,
            udpOffset = 20,
            udpLength = udpLen
        )
        packet[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[27] = (udpChecksum and 0xFF).toByte()

        return packet
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

    private fun computeUdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpPacket: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L
        // Pseudo-header: srcIp (4) + dstIp (4) + zero/proto(17) + udpLength (2)
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17L
        sum += udpLength.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i < end - 1) {
            val word = ((udpPacket[i].toInt() and 0xFF) shl 8) or (udpPacket[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (udpPacket[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        return if (result == 0) 0xFFFF else result
    }
}

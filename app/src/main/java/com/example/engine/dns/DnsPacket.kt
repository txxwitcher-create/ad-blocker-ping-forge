package com.example.engine.dns

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * RFC 1035 DNS wire-format parser and response builder.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    data class DnsQuestion(
        val transactionId: Int,
        val flags: Int,
        val qName: String,
        val qType: Int,
        val qClass: Int,
        val questionEndOffset: Int
    )

    fun parseQuestion(packet: ByteArray, length: Int = packet.size): DnsQuestion? {
        if (length < 12) return null
        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null

        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val nameResult = readDomainName(packet, 12, length) ?: return null
        val offset = nameResult.nextOffset
        if (offset + 4 > length) return null

        val qType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val qClass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)

        return DnsQuestion(
            transactionId = txId,
            flags = flags,
            qName = nameResult.domain,
            qType = qType,
            qClass = qClass,
            questionEndOffset = offset + 4
        )
    }

    /**
     * Builds a real DNS query packet for benchmarking and upstream tests.
     */
    fun buildQueryPacket(domain: String, transactionId: Int, qType: Int = TYPE_A): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header: TXID, RD=1 (0x0100), QDCOUNT=1, AN/NS/AR=0
        out.write((transactionId ushr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        // Question QNAME
        val labels = domain.trim('.').split('.')
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            val len = bytes.size.coerceAtMost(63)
            out.write(len)
            out.write(bytes, 0, len)
        }
        out.write(0x00) // terminator
        // QTYPE
        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        // QCLASS = IN
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    /**
     * Builds an instant blocked DNS response:
     * - For A queries: returns 0.0.0.0 with short TTL (60s) or NXDOMAIN.
     *   Returning NXDOMAIN (RCODE=3) or 0.0.0.0: 0.0.0.0 for A and :: for AAAA / NXDOMAIN for others
     *   is fast, standard (AdGuard/Pi-hole default), and avoids apps retrying aggressively.
     */
    fun buildBlockedResponse(requestPacket: ByteArray, question: DnsQuestion): ByteArray {
        val questionSectionLen = (question.questionEndOffset - 12).coerceAtLeast(0)
        val out = ByteArrayOutputStream(12 + questionSectionLen + 32)

        // Header (12 bytes)
        out.write((question.transactionId ushr 8) and 0xFF)
        out.write(question.transactionId and 0xFF)

        val rd = question.flags and 0x0100
        when (question.qType) {
            TYPE_A, TYPE_AAAA -> {
                // QR=1, AA=0, TC=0, RD=copied, RA=1, RCODE=0 (NOERROR with null IP answer)
                val respFlags = 0x8080 or rd
                out.write((respFlags ushr 8) and 0xFF)
                out.write(respFlags and 0xFF)
                out.write(0x00); out.write(0x01) // QDCOUNT = 1
                out.write(0x00); out.write(0x01) // ANCOUNT = 1
                out.write(0x00); out.write(0x00) // NSCOUNT = 0
                out.write(0x00); out.write(0x00) // ARCOUNT = 0

                // Echo question section verbatim
                out.write(requestPacket, 12, questionSectionLen)

                // Answer RR pointing to QNAME at offset 12 (0xC00C)
                out.write(0xC0); out.write(0x0C)
                out.write((question.qType ushr 8) and 0xFF)
                out.write(question.qType and 0xFF)
                out.write(0x00); out.write(0x01) // CLASS IN
                out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C) // TTL = 60s
                if (question.qType == TYPE_A) {
                    out.write(0x00); out.write(0x04) // RDLENGTH = 4
                    out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00) // 0.0.0.0
                } else {
                    out.write(0x00); out.write(0x10) // RDLENGTH = 16
                    repeat(16) { out.write(0x00) } // ::
                }
            }

            else -> {
                // NXDOMAIN (RCODE = 3) for HTTPS/SVCB/TXT/etc.
                val respFlags = 0x8083 or rd
                out.write((respFlags ushr 8) and 0xFF)
                out.write(respFlags and 0xFF)
                out.write(0x00); out.write(0x01) // QDCOUNT = 1
                out.write(0x00); out.write(0x00) // ANCOUNT = 0
                out.write(0x00); out.write(0x00) // NSCOUNT = 0
                out.write(0x00); out.write(0x00) // ARCOUNT = 0
                out.write(requestPacket, 12, questionSectionLen)
            }
        }
        return out.toByteArray()
    }

    /**
     * Builds SERVFAIL response when all upstream resolvers fail.
     */
    fun buildServFailResponse(requestPacket: ByteArray, question: DnsQuestion): ByteArray {
        val questionSectionLen = (question.questionEndOffset - 12).coerceAtLeast(0)
        val out = ByteArrayOutputStream(12 + questionSectionLen)
        out.write((question.transactionId ushr 8) and 0xFF)
        out.write(question.transactionId and 0xFF)
        val rd = question.flags and 0x0100
        val respFlags = 0x8082 or rd // RCODE = 2 (SERVFAIL)
        out.write((respFlags ushr 8) and 0xFF)
        out.write(respFlags and 0xFF)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(requestPacket, 12, questionSectionLen)
        return out.toByteArray()
    }

    /**
     * Clones a cached DNS response while replacing its Transaction ID with the incoming query's TXID.
     */
    fun rewriteTransactionId(responsePacket: ByteArray, newTxId: Int): ByteArray {
        if (responsePacket.size < 2) return responsePacket
        val copy = responsePacket.copyOf()
        copy[0] = ((newTxId ushr 8) and 0xFF).toByte()
        copy[1] = (newTxId and 0xFF).toByte()
        return copy
    }

    fun isValidResponse(response: ByteArray, expectedTxId: Int? = null): Boolean {
        if (response.size < 12) return false
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return false
        if (expectedTxId != null) {
            val txId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
            if (txId != expectedTxId) return false
        }
        return true
    }

    /**
     * Extracts minimum TTL across Answer RRs (defaults to 60s if no answers or unparseable).
     */
    fun extractMinTtlSeconds(response: ByteArray, fallbackTtl: Int = 60): Int {
        if (response.size < 12) return fallbackTtl
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount <= 0) return 30

        var offset = 12
        repeat(qdCount) {
            val skipped = skipName(response, offset, response.size) ?: return fallbackTtl
            offset = skipped + 4
            if (offset > response.size) return fallbackTtl
        }

        var minTtl = Int.MAX_VALUE
        repeat(anCount) {
            val skipped = skipName(response, offset, response.size) ?: return fallbackTtl
            offset = skipped
            if (offset + 10 > response.size) return fallbackTtl
            val ttl = ByteBuffer.wrap(response, offset + 4, 4).int.coerceAtLeast(15)
            val rdLength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
            minTtl = min(minTtl, ttl)
            offset += 10 + rdLength
            if (offset > response.size) return fallbackTtl
        }
        return if (minTtl == Int.MAX_VALUE) fallbackTtl else minTtl
    }

    private data class NameReadResult(val domain: String, val nextOffset: Int)

    private fun readDomainName(packet: ByteArray, startOffset: Int, length: Int): NameReadResult? {
        val sb = StringBuilder()
        var offset = startOffset
        var jumped = false
        var endOffset = -1
        var jumps = 0

        while (offset < length) {
            val len = packet[offset].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) endOffset = offset + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= length) return null
                if (!jumped) endOffset = offset + 2
                val pointer = ((len and 0x3F) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                if (pointer >= length || jumps++ > 16) return null
                offset = pointer
                jumped = true
            } else if ((len and 0xC0) == 0) {
                offset++
                if (offset + len > length) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until len) {
                    sb.append((packet[offset + i].toInt() and 0xFF).toChar())
                }
                offset += len
            } else {
                return null
            }
        }
        if (endOffset < 0 || sb.isEmpty()) return null
        return NameReadResult(sb.toString(), endOffset)
    }

    private fun skipName(packet: ByteArray, startOffset: Int, length: Int): Int? {
        var offset = startOffset
        var jumps = 0
        while (offset < length) {
            val len = packet[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= length) return null
                return offset + 2
            } else if ((len and 0xC0) == 0) {
                offset += 1 + len
                if (jumps++ > 64) return null
            } else {
                return null
            }
        }
        return null
    }
}

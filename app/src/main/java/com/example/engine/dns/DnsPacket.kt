package com.example.engine.dns

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * RFC 1035 / RFC 2181 DNS wire-format parser, validator, and response builder.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_NOTIMP = 4
    const val RCODE_REFUSED = 5

    data class DnsQuestion(
        val transactionId: Int,
        val flags: Int,
        val qName: String,
        val qType: Int,
        val qClass: Int,
        val questionEndOffset: Int
    )

    data class QuestionEntry(
        val qName: String,
        val qType: Int,
        val qClass: Int,
        val startOffset: Int,
        val endOffset: Int
    )

    data class DnsMessageQuestions(
        val transactionId: Int,
        val flags: Int,
        val questions: List<QuestionEntry>,
        val questionsEndOffset: Int
    )

    fun parseQuestion(packet: ByteArray, length: Int = packet.size): DnsQuestion? {
        val parsed = parseAllQuestions(packet, length, requireQuery = true) ?: return null
        val first = parsed.questions.firstOrNull() ?: return null
        return DnsQuestion(
            transactionId = parsed.transactionId,
            flags = parsed.flags,
            qName = first.qName,
            qType = first.qType,
            qClass = first.qClass,
            questionEndOffset = first.endOffset
        )
    }

    /**
     * Parses ALL questions in a DNS message (supporting multi-question packets cleanly).
     * Returns null if header or any question is malformed or truncated.
     */
    fun parseAllQuestions(
        packet: ByteArray,
        length: Int = packet.size,
        requireQuery: Boolean = true
    ): DnsMessageQuestions? {
        if (length < 12 || length > packet.size) return null
        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (requireQuery && isResponse) return null

        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdCount < 1 || qdCount > 64) return null

        val questions = ArrayList<QuestionEntry>(qdCount)
        var offset = 12
        repeat(qdCount) {
            val start = offset
            val nameResult = readDomainName(packet, offset, length) ?: return null
            offset = nameResult.nextOffset
            if (offset + 4 > length) return null
            val qType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val qClass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            offset += 4
            questions.add(
                QuestionEntry(
                    qName = nameResult.domain,
                    qType = qType,
                    qClass = qClass,
                    startOffset = start,
                    endOffset = offset
                )
            )
        }

        return DnsMessageQuestions(
            transactionId = txId,
            flags = flags,
            questions = questions,
            questionsEndOffset = offset
        )
    }

    fun extractTransactionId(packet: ByteArray): Int? {
        if (packet.size < 2) return null
        return ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
    }

    fun extractRcode(packet: ByteArray): Int {
        if (packet.size < 4) return RCODE_FORMERR
        return packet[3].toInt() and 0x0F
    }

    fun isTruncated(packet: ByteArray): Boolean {
        if (packet.size < 4) return false
        return (packet[2].toInt() and 0x02) != 0
    }

    /**
     * Only non-truncated NOERROR and NXDOMAIN responses are eligible for caching.
     * Never cache SERVFAIL, REFUSED, FORMERR, NOTIMP, or truncated responses.
     */
    fun isCacheableResponse(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return false
        if ((flags and 0x0200) != 0) return false // TC=1 truncated
        val rcode = flags and 0x000F
        return rcode == RCODE_NOERROR || rcode == RCODE_NXDOMAIN
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
     * Builds an instant blocked DNS response for a single parsed DnsQuestion.
     */
    fun buildBlockedResponse(requestPacket: ByteArray, question: DnsQuestion): ByteArray {
        val singleMessage = DnsMessageQuestions(
            transactionId = question.transactionId,
            flags = question.flags,
            questions = listOf(
                QuestionEntry(
                    qName = question.qName,
                    qType = question.qType,
                    qClass = question.qClass,
                    startOffset = 12,
                    endOffset = question.questionEndOffset
                )
            ),
            questionsEndOffset = question.questionEndOffset
        )
        return buildBlockedResponse(requestPacket, singleMessage)
    }

    /**
     * Builds an instant blocked DNS response covering single or multi-question DNS queries.
     * - Single A/AAAA query: NOERROR with 0.0.0.0 / :: (TTL=60s).
     * - Multi-question or other RR types: NXDOMAIN echoing the full original question section.
     */
    fun buildBlockedResponse(requestPacket: ByteArray, message: DnsMessageQuestions): ByteArray {
        val questionSectionLen = (message.questionsEndOffset - 12).coerceAtLeast(0)
        val out = ByteArrayOutputStream(12 + questionSectionLen + 32)

        out.write((message.transactionId ushr 8) and 0xFF)
        out.write(message.transactionId and 0xFF)

        val rd = message.flags and 0x0100
        val singleQuestion = message.questions.singleOrNull()
        if (singleQuestion != null && (singleQuestion.qType == TYPE_A || singleQuestion.qType == TYPE_AAAA)) {
            val respFlags = 0x8080 or rd
            out.write((respFlags ushr 8) and 0xFF)
            out.write(respFlags and 0xFF)
            out.write(0x00); out.write(0x01) // QDCOUNT = 1
            out.write(0x00); out.write(0x01) // ANCOUNT = 1
            out.write(0x00); out.write(0x00) // NSCOUNT = 0
            out.write(0x00); out.write(0x00) // ARCOUNT = 0

            out.write(requestPacket, 12, questionSectionLen)

            // Answer RR pointing to QNAME at offset 12 (0xC00C)
            out.write(0xC0); out.write(0x0C)
            out.write((singleQuestion.qType ushr 8) and 0xFF)
            out.write(singleQuestion.qType and 0xFF)
            out.write((singleQuestion.qClass ushr 8) and 0xFF)
            out.write(singleQuestion.qClass and 0xFF)
            out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C) // TTL = 60s
            if (singleQuestion.qType == TYPE_A) {
                out.write(0x00); out.write(0x04)
                out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00)
            } else {
                out.write(0x00); out.write(0x10)
                repeat(16) { out.write(0x00) }
            }
        } else {
            val qdCount = message.questions.size
            val respFlags = 0x8083 or rd // NXDOMAIN
            out.write((respFlags ushr 8) and 0xFF)
            out.write(respFlags and 0xFF)
            out.write((qdCount ushr 8) and 0xFF); out.write(qdCount and 0xFF)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            out.write(requestPacket, 12, questionSectionLen)
        }
        return out.toByteArray()
    }

    /**
     * Builds SERVFAIL response for a single parsed DnsQuestion.
     */
    fun buildServFailResponse(requestPacket: ByteArray, question: DnsQuestion): ByteArray {
        val singleMessage = DnsMessageQuestions(
            transactionId = question.transactionId,
            flags = question.flags,
            questions = listOf(
                QuestionEntry(
                    qName = question.qName,
                    qType = question.qType,
                    qClass = question.qClass,
                    startOffset = 12,
                    endOffset = question.questionEndOffset
                )
            ),
            questionsEndOffset = question.questionEndOffset
        )
        return buildServFailResponse(requestPacket, singleMessage)
    }

    /**
     * Builds SERVFAIL response covering single or multi-question requests.
     */
    fun buildServFailResponse(requestPacket: ByteArray, message: DnsMessageQuestions): ByteArray {
        val questionSectionLen = (message.questionsEndOffset - 12).coerceAtLeast(0)
        val qdCount = message.questions.size
        val out = ByteArrayOutputStream(12 + questionSectionLen)
        out.write((message.transactionId ushr 8) and 0xFF)
        out.write(message.transactionId and 0xFF)
        val rd = message.flags and 0x0100
        val respFlags = 0x8082 or rd // RCODE = 2 (SERVFAIL)
        out.write((respFlags ushr 8) and 0xFF)
        out.write(respFlags and 0xFF)
        out.write((qdCount ushr 8) and 0xFF); out.write(qdCount and 0xFF)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(requestPacket, 12, questionSectionLen)
        return out.toByteArray()
    }

    /**
     * Builds a truncated (TC=1) DNS response when a UDP answer exceeds TUN MTU,
     * signaling the client stub resolver to retry over TCP port 53.
     */
    fun buildTruncatedResponse(requestPacket: ByteArray, message: DnsMessageQuestions): ByteArray {
        val questionSectionLen = (message.questionsEndOffset - 12).coerceAtLeast(0)
        val qdCount = message.questions.size
        val out = ByteArrayOutputStream(12 + questionSectionLen)
        out.write((message.transactionId ushr 8) and 0xFF)
        out.write(message.transactionId and 0xFF)
        val rd = message.flags and 0x0100
        val respFlags = 0x8280 or rd // QR=1, TC=1, RA=1, NOERROR
        out.write((respFlags ushr 8) and 0xFF)
        out.write(respFlags and 0xFF)
        out.write((qdCount ushr 8) and 0xFF); out.write(qdCount and 0xFF)
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

    /**
     * Clones a cached DNS response, updates Transaction ID, and decrements RR TTLs
     * by [elapsedSeconds] (skipping EDNS OPT pseudo-RRs) so clients get accurate remaining TTL.
     */
    fun rewriteResponseForCache(
        responsePacket: ByteArray,
        newTxId: Int,
        elapsedSeconds: Int
    ): ByteArray {
        val copy = rewriteTransactionId(responsePacket, newTxId)
        if (copy.size < 12 || elapsedSeconds <= 0) return copy

        val qdCount = ((copy[4].toInt() and 0xFF) shl 8) or (copy[5].toInt() and 0xFF)
        val anCount = ((copy[6].toInt() and 0xFF) shl 8) or (copy[7].toInt() and 0xFF)
        val nsCount = ((copy[8].toInt() and 0xFF) shl 8) or (copy[9].toInt() and 0xFF)
        val arCount = ((copy[10].toInt() and 0xFF) shl 8) or (copy[11].toInt() and 0xFF)

        var offset = 12
        repeat(qdCount) {
            val skipped = skipName(copy, offset, copy.size) ?: return copy
            offset = skipped + 4
            if (offset > copy.size) return copy
        }

        val totalRrs = anCount + nsCount + arCount
        repeat(totalRrs) {
            val skipped = skipName(copy, offset, copy.size) ?: return copy
            offset = skipped
            if (offset + 10 > copy.size) return copy
            val rrType = ((copy[offset].toInt() and 0xFF) shl 8) or (copy[offset + 1].toInt() and 0xFF)
            val rdLength = ((copy[offset + 8].toInt() and 0xFF) shl 8) or (copy[offset + 9].toInt() and 0xFF)
            if (rrType != TYPE_OPT) {
                val rawTtl = ByteBuffer.wrap(copy, offset + 4, 4).int
                val clamped = if (rawTtl < 0) 0 else rawTtl
                val updated = (clamped - elapsedSeconds).coerceAtLeast(1)
                copy[offset + 4] = ((updated ushr 24) and 0xFF).toByte()
                copy[offset + 5] = ((updated ushr 16) and 0xFF).toByte()
                copy[offset + 6] = ((updated ushr 8) and 0xFF).toByte()
                copy[offset + 7] = (updated and 0xFF).toByte()
            }
            offset += 10 + rdLength
            if (offset > copy.size) return copy
        }
        return copy
    }

    /**
     * Validates DNS response header (QR=1), Transaction ID, and matching Question section
     * against [expectedQueryPacket] when provided.
     */
    fun isValidResponse(
        response: ByteArray,
        expectedTxId: Int? = null,
        expectedQueryPacket: ByteArray? = null
    ): Boolean {
        if (response.size < 12) return false
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return false

        val responseTxId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        val queryTxId = expectedTxId ?: expectedQueryPacket?.let { extractTransactionId(it) }
        if (queryTxId != null && responseTxId != queryTxId) return false

        if (expectedQueryPacket != null) {
            val expectedQuestions = parseAllQuestions(expectedQueryPacket, expectedQueryPacket.size, requireQuery = true)
                ?: return false
            val responseQuestions = parseAllQuestions(response, response.size, requireQuery = false)
                ?: return false
            if (responseQuestions.questions.size != expectedQuestions.questions.size) return false
            for (i in expectedQuestions.questions.indices) {
                val exp = expectedQuestions.questions[i]
                val act = responseQuestions.questions[i]
                if (!exp.qName.equals(act.qName, ignoreCase = true) ||
                    exp.qType != act.qType ||
                    exp.qClass != act.qClass
                ) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Extracts the minimum valid TTL in seconds across Answer RRs (or SOA negative TTL for
     * NXDOMAIN/NODATA). Returns 0 if the response is not cacheable or explicitly has TTL=0.
     */
    fun extractMinTtlSeconds(response: ByteArray, fallbackTtl: Int = 60): Int {
        if (!isCacheableResponse(response)) return 0
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        val nsCount = ((response[8].toInt() and 0xFF) shl 8) or (response[9].toInt() and 0xFF)

        var offset = 12
        repeat(qdCount) {
            val skipped = skipName(response, offset, response.size) ?: return 0
            offset = skipped + 4
            if (offset > response.size) return 0
        }

        if (anCount > 0) {
            var minTtl = Int.MAX_VALUE
            repeat(anCount) {
                val skipped = skipName(response, offset, response.size) ?: return 0
                offset = skipped
                if (offset + 10 > response.size) return 0
                val rawTtl = ByteBuffer.wrap(response, offset + 4, 4).int
                // RFC 2181: MSB set (negative signed 32-bit) must be treated as 0
                val ttl = if (rawTtl < 0) 0 else rawTtl
                val rdLength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
                minTtl = min(minTtl, ttl)
                offset += 10 + rdLength
                if (offset > response.size) return 0
            }
            return if (minTtl == Int.MAX_VALUE) 0 else minTtl
        }

        // Negative / NODATA response (anCount == 0): inspect Authority SOA minimum TTL per RFC 2308
        if (nsCount > 0) {
            var minNegativeTtl = Int.MAX_VALUE
            repeat(nsCount) {
                val skipped = skipName(response, offset, response.size) ?: return min(fallbackTtl, 30)
                offset = skipped
                if (offset + 10 > response.size) return min(fallbackTtl, 30)
                val rrType = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
                val rawTtl = ByteBuffer.wrap(response, offset + 4, 4).int
                val rrTtl = if (rawTtl < 0) 0 else rawTtl
                val rdLength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
                val rdataStart = offset + 10
                val rdataEnd = rdataStart + rdLength
                if (rdataEnd > response.size) return min(fallbackTtl, 30)

                if (rrType == TYPE_SOA && rdLength >= 22) {
                    // SOA RDATA: MNAME, RNAME, SERIAL(4), REFRESH(4), RETRY(4), EXPIRE(4), MINIMUM(4)
                    val afterMname = skipName(response, rdataStart, rdataEnd)
                    val afterRname = afterMname?.let { skipName(response, it, rdataEnd) }
                    if (afterRname != null && afterRname + 20 <= rdataEnd) {
                        val rawSoaMin = ByteBuffer.wrap(response, afterRname + 16, 4).int
                        val soaMin = if (rawSoaMin < 0) 0 else rawSoaMin
                        minNegativeTtl = min(minNegativeTtl, min(rrTtl, soaMin))
                    } else {
                        minNegativeTtl = min(minNegativeTtl, rrTtl)
                    }
                }
                offset = rdataEnd
            }
            if (minNegativeTtl != Int.MAX_VALUE) {
                return minNegativeTtl.coerceAtMost(60)
            }
        }

        return min(fallbackTtl, 30)
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

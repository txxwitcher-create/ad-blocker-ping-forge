package com.example.engine.dns

import android.net.VpnService
import com.example.domain.model.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Handles real upstream DNS queries over DoH (RFC 8484), UDP (port 53), and TCP (port 53),
 * strictly validates every DNS response against the original query (TXID, QR=1, and Questions),
 * protects sockets from VPN routing loops, and supports automatic fallback resolvers.
 */
class UpstreamDnsClient {

    data class ForwardResult(
        val responseBytes: ByteArray,
        val latencyMs: Long,
        val usedResolverName: String,
        val usedFallback: Boolean,
        val usedResolverId: String = "",
        val usedTransport: String = "UDP"
    )

    private val dohMediaType = "application/dns-message".toMediaType()

    @Volatile
    private var activeVpnService: VpnService? = null

    private val baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .socketFactory(object : SocketFactory() {
            private val delegate = getDefault()
            override fun createSocket(): Socket = delegate.createSocket().also { protectSocket(it) }
            override fun createSocket(host: String?, port: Int): Socket =
                delegate.createSocket(host, port).also { protectSocket(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).also { protectSocket(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                delegate.createSocket(host, port).also { protectSocket(it) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).also { protectSocket(it) }
        })
        .build()

    fun attachVpnService(service: VpnService?) {
        activeVpnService = service
    }

    private fun protectSocket(socket: Socket) {
        activeVpnService?.protect(socket)
    }

    suspend fun forwardQuery(
        queryPacket: ByteArray,
        primaryResolver: DnsResolver,
        fallbackResolvers: List<DnsResolver>,
        preferDoH: Boolean,
        timeoutMs: Int = 2500,
        forceTcp: Boolean = false
    ): ForwardResult? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(primaryResolver)
            fallbackResolvers.forEach { if (it.id != primaryResolver.id) add(it) }
        }

        for ((index, resolver) in candidates.withIndex()) {
            val isFallback = index > 0

            // Try DoH first if preferred & supported
            if (preferDoH && resolver.supportsDoH && !resolver.dohUrl.isNullOrBlank()) {
                val dohResult = queryDoH(queryPacket, resolver.dohUrl)
                if (dohResult != null) {
                    return@withContext ForwardResult(
                        responseBytes = dohResult.first,
                        latencyMs = dohResult.second,
                        usedResolverName = resolver.name,
                        usedFallback = isFallback,
                        usedResolverId = resolver.id,
                        usedTransport = "DoH"
                    )
                }
            }

            // Primary IP (TCP if forceTcp, else UDP with automatic RFC 1035 TC->TCP retry)
            val primaryRes = if (forceTcp) {
                queryTcp(queryPacket, resolver.primaryIp, timeoutMs)
            } else {
                queryUdp(queryPacket, resolver.primaryIp, timeoutMs)
            }
            if (primaryRes != null) {
                return@withContext ForwardResult(
                    responseBytes = primaryRes.first,
                    latencyMs = primaryRes.second,
                    usedResolverName = resolver.name,
                    usedFallback = isFallback,
                    usedResolverId = resolver.id,
                    usedTransport = if (forceTcp) "TCP" else "UDP"
                )
            }

            // Secondary IP if configured and distinct from primary
            if (resolver.secondaryIp.isNotBlank() && resolver.secondaryIp != resolver.primaryIp) {
                val secondaryRes = if (forceTcp) {
                    queryTcp(queryPacket, resolver.secondaryIp, timeoutMs)
                } else {
                    queryUdp(queryPacket, resolver.secondaryIp, timeoutMs)
                }
                if (secondaryRes != null) {
                    return@withContext ForwardResult(
                        responseBytes = secondaryRes.first,
                        latencyMs = secondaryRes.second,
                        usedResolverName = resolver.name,
                        usedFallback = true,
                        usedResolverId = resolver.id,
                        usedTransport = if (forceTcp) "TCP" else "UDP"
                    )
                }
            }
        }
        null
    }

    /**
     * Sends a single real DNS query over DoH (POST application/dns-message) and validates
     * TXID, response flag, and matching DNS question(s).
     */
    fun queryDoH(queryPacket: ByteArray, dohUrl: String): Pair<ByteArray, Long>? {
        val expectedTxId = DnsPacket.extractTransactionId(queryPacket) ?: return null
        val startNs = System.nanoTime()
        return try {
            val request = Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-message")
                .post(queryPacket.toRequestBody(dohMediaType))
                .build()

            baseHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.bytes() ?: return null
                if (!DnsPacket.isValidResponse(body, expectedTxId = expectedTxId, expectedQueryPacket = queryPacket)) {
                    return null
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                body to elapsedMs
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends a single real UDP DNS query to [ipAddress]:53 (IPv4 or IPv6), validates response,
     * and automatically retries over TCP port 53 if the TC (Truncated) flag is set.
     */
    fun queryUdp(
        queryPacket: ByteArray,
        ipAddress: String,
        timeoutMs: Int = 2500,
        allowTcpFallbackOnTruncation: Boolean = true
    ): Pair<ByteArray, Long>? {
        if (ipAddress.isBlank()) return null
        val expectedTxId = DnsPacket.extractTransactionId(queryPacket) ?: return null
        val startNs = System.nanoTime()
        var socket: DatagramSocket? = null
        val udpResult: Pair<ByteArray, Long>? = try {
            socket = DatagramSocket()
            activeVpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            val addr = InetAddress.getByName(ipAddress)
            val sendPacket = DatagramPacket(queryPacket, queryPacket.size, addr, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)
            val responseBytes = recvBuf.copyOfRange(0, recvPacket.length)
            if (!DnsPacket.isValidResponse(responseBytes, expectedTxId = expectedTxId, expectedQueryPacket = queryPacket)) {
                null
            } else {
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                responseBytes to elapsedMs
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }

        if (udpResult != null && allowTcpFallbackOnTruncation && DnsPacket.isTruncated(udpResult.first)) {
            val tcpRetry = queryTcp(queryPacket, ipAddress, timeoutMs)
            if (tcpRetry != null) {
                return tcpRetry
            }
        }
        return udpResult
    }

    /**
     * Sends a real DNS query over TCP port 53 (RFC 1035 2-byte length prefix) to [ipAddress] (IPv4 or IPv6),
     * protecting the socket against VPN loops and strictly validating the response.
     */
    fun queryTcp(queryPacket: ByteArray, ipAddress: String, timeoutMs: Int = 2500): Pair<ByteArray, Long>? {
        if (ipAddress.isBlank() || queryPacket.size > 65535) return null
        val expectedTxId = DnsPacket.extractTransactionId(queryPacket) ?: return null
        val startNs = System.nanoTime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            protectSocket(socket)
            val addr = InetAddress.getByName(ipAddress)
            socket.connect(InetSocketAddress(addr, 53), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true

            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(queryPacket.size)
            out.write(queryPacket)
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val respLen = input.readUnsignedShort()
            if (respLen < 12 || respLen > 65535) return null
            val responseBytes = ByteArray(respLen)
            input.readFully(responseBytes)

            if (!DnsPacket.isValidResponse(responseBytes, expectedTxId = expectedTxId, expectedQueryPacket = queryPacket)) {
                return null
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            responseBytes to elapsedMs
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}

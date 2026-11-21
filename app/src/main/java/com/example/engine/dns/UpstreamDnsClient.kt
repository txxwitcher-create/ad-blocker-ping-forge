package com.example.engine.dns

import android.net.VpnService
import com.example.domain.model.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Handles real upstream DNS queries over DoH (RFC 8484) and UDP (port 53),
 * protecting sockets from VPN routing loops when a VpnService is active,
 * and supporting automatic fallback to secondary / backup resolvers.
 */
class UpstreamDnsClient {

    data class ForwardResult(
        val responseBytes: ByteArray,
        val latencyMs: Long,
        val usedResolverName: String,
        val usedFallback: Boolean
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
        timeoutMs: Int = 2500
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
                        usedFallback = isFallback
                    )
                }
            }

            // Try Primary UDP IP
            val primaryUdp = queryUdp(queryPacket, resolver.primaryIp, timeoutMs)
            if (primaryUdp != null) {
                return@withContext ForwardResult(
                    responseBytes = primaryUdp.first,
                    latencyMs = primaryUdp.second,
                    usedResolverName = resolver.name,
                    usedFallback = isFallback
                )
            }

            // Try Secondary UDP IP if configured
            if (resolver.secondaryIp.isNotBlank() && resolver.secondaryIp != resolver.primaryIp) {
                val secondaryUdp = queryUdp(queryPacket, resolver.secondaryIp, timeoutMs)
                if (secondaryUdp != null) {
                    return@withContext ForwardResult(
                        responseBytes = secondaryUdp.first,
                        latencyMs = secondaryUdp.second,
                        usedResolverName = resolver.name,
                        usedFallback = true
                    )
                }
            }
        }
        null
    }

    /**
     * Sends a single real DNS query over DoH (POST application/dns-message) and returns (response, latencyMs).
     */
    fun queryDoH(queryPacket: ByteArray, dohUrl: String): Pair<ByteArray, Long>? {
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
                if (!DnsPacket.isValidResponse(body)) return null
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
     * Sends a single real UDP DNS query to [ipAddress]:53 and returns (response, latencyMs).
     */
    fun queryUdp(queryPacket: ByteArray, ipAddress: String, timeoutMs: Int = 2500): Pair<ByteArray, Long>? {
        if (ipAddress.isBlank()) return null
        val startNs = System.nanoTime()
        var socket: DatagramSocket? = null
        return try {
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
            if (!DnsPacket.isValidResponse(responseBytes)) return null
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

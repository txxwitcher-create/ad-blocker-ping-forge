package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PingForgeApplication
import com.example.R
import com.example.domain.model.DnsQueryRecord
import com.example.domain.model.QueryAction
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnStatus
import com.example.engine.dns.DnsPacket
import com.example.engine.dns.Ipv4UdpPacketParser
import com.example.engine.dns.TcpDnsSessionManager
import com.example.engine.filter.FilterDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Local split-DNS VpnService supporting IPv4 & IPv6 DNS over UDP and TCP (port 53).
 * Routes only our virtual DNS endpoints (10.111.222.1/32 and fd00:111:222::1/128) into TUN so:
 * - All device-wide DNS queries are filtered and resolved with Ping Forge's selected upstream DNS.
 * - Normal app/game TCP & UDP packets bypass the VPN tunnel directly over Wi-Fi/Mobile with zero overhead.
 */
class PingForgeVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.vpn.ACTION_START"
        const val ACTION_STOP = "com.example.vpn.ACTION_STOP"
        private const val NOTIFICATION_CHANNEL_ID = "pingforge_vpn_channel"
        private const val NOTIFICATION_ID = 1042

        private const val VPN_ADDRESS = "10.111.222.2"
        private const val VIRTUAL_DNS_SERVER = "10.111.222.1"
        private const val VPN_ADDRESS_V6 = "fd00:111:222::2"
        private const val VIRTUAL_DNS_SERVER_V6 = "fd00:111:222::1"

        private const val WORKER_COUNT = 16
        private const val REQUEST_QUEUE_CAPACITY = 64
        private const val TELEMETRY_QUEUE_CAPACITY = 256
        private const val MAX_UDP_DNS_PAYLOAD_V4 = 1472
        private const val MAX_UDP_DNS_PAYLOAD_V6 = 1452
    }

    private sealed class InboundDnsRequest {
        abstract val dnsPayload: ByteArray

        data class Udp(val packet: Ipv4UdpPacketParser.UdpDnsPacket) : InboundDnsRequest() {
            override val dnsPayload: ByteArray get() = packet.dnsPayload
        }

        data class Tcp(
            val sessionKey: TcpDnsSessionManager.SessionKey,
            override val dnsPayload: ByteArray
        ) : InboundDnsRequest()
    }

    private data class TelemetryEvent(
        val totalDelta: Long = 1L,
        val blockedDelta: Long = 0L,
        val cachedDelta: Long = 0L,
        val failedDelta: Long = 0L,
        val queryRecord: DnsQueryRecord? = null
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetLoopJob: Job? = null
    private val workerJobs = mutableListOf<Job>()
    private var telemetryJob: Job? = null
    private var requestChannel: Channel<InboundDnsRequest>? = null
    private var telemetryChannel: Channel<TelemetryEvent>? = null
    private val tcpSessionManager = TcpDnsSessionManager()
    private val writeLock = Any()

    private val appContainer: AppContainer
        get() = (application as PingForgeApplication).container

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appContainer.upstreamDnsClient.attachVpnService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startVpnInternal()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpnInternal(
            userInitiated = false,
            errorType = VpnErrorType.SERVICE_REVOKED,
            errorMessage = "VPN permission was revoked by the system or replaced by another VPN app."
        )
    }

    override fun onDestroy() {
        appContainer.upstreamDnsClient.attachVpnService(null)
        stopVpnInternal(userInitiated = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpnInternal() {
        if (vpnInterface != null && packetLoopJob?.isActive == true) {
            val resolver = appContainer.getActiveResolver()
            appContainer.updateVpnState(
                VpnConnectionState(
                    status = VpnStatus.CONNECTED,
                    connectedSinceMillis = appContainer.vpnState.value.connectedSinceMillis
                        ?: System.currentTimeMillis(),
                    activeResolverName = resolver.name,
                    activeResolverAddress = resolver.primaryIp
                )
            )
            return
        }

        // Check permission before building interface
        if (prepare(this) != null) {
            appContainer.reportVpnError(
                VpnErrorType.PERMISSION_DENIED,
                "VPN authorization is required to start local DNS protection."
            )
            stopSelf()
            return
        }

        try {
            val notification = buildNotification("DNS protection active • Monitoring queries")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val builder = Builder()
                .setSession(getString(R.string.brand_full_title))
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(VIRTUAL_DNS_SERVER)
                .addRoute(VIRTUAL_DNS_SERVER, 32)
                .setMtu(1500)
                .setBlocking(true)

            // Route virtual IPv6 DNS server through TUN as well when supported by the device kernel
            runCatching {
                builder
                    .addAddress(VPN_ADDRESS_V6, 128)
                    .addDnsServer(VIRTUAL_DNS_SERVER_V6)
                    .addRoute(VIRTUAL_DNS_SERVER_V6, 128)
            }

            // Prevent our own DoH & blocklist update requests from entering TUN
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish()
            if (pfd == null) {
                appContainer.reportVpnError(
                    VpnErrorType.INTERFACE_FAILED,
                    "Unable to establish VPN TUN interface. Ensure no other Always-On VPN is blocking connections."
                )
                stopForegroundCompat()
                stopSelf()
                return
            }

            vpnInterface = pfd
            val resolver = appContainer.getActiveResolver()
            appContainer.updateVpnState(
                VpnConnectionState(
                    status = VpnStatus.CONNECTED,
                    connectedSinceMillis = System.currentTimeMillis(),
                    activeResolverName = resolver.name,
                    activeResolverAddress = resolver.primaryIp
                )
            )

            startPacketLoop(pfd)
        } catch (e: SecurityException) {
            appContainer.reportVpnError(
                VpnErrorType.PERMISSION_DENIED,
                e.message ?: "VPN permission denied by system."
            )
            stopForegroundCompat()
            stopSelf()
        } catch (e: Exception) {
            appContainer.reportVpnError(
                VpnErrorType.INTERFACE_FAILED,
                e.message ?: "Failed to initialize DNS VPN interface."
            )
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopWorkersAndChannels() {
        packetLoopJob?.cancel()
        packetLoopJob = null

        requestChannel?.close()
        requestChannel = null

        workerJobs.forEach { it.cancel() }
        workerJobs.clear()

        telemetryChannel?.close()
        telemetryChannel = null

        telemetryJob?.cancel()
        telemetryJob = null

        tcpSessionManager.clear()
    }

    private fun startPacketLoop(pfd: ParcelFileDescriptor) {
        stopWorkersAndChannels()

        val output = FileOutputStream(pfd.fileDescriptor)
        val reqChannel = Channel<InboundDnsRequest>(
            capacity = REQUEST_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
        val telChannel = Channel<TelemetryEvent>(
            capacity = TELEMETRY_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        requestChannel = reqChannel
        telemetryChannel = telChannel

        // Dedicated non-blocking telemetry writer so DataStore/Room I/O never stalls DNS workers
        telemetryJob = serviceScope.launch {
            for (event in telChannel) {
                runCatching {
                    appContainer.settingsRepository.incrementCounters(
                        totalDelta = event.totalDelta,
                        blockedDelta = event.blockedDelta,
                        cachedDelta = event.cachedDelta,
                        failedDelta = event.failedDelta
                    )
                    if (event.queryRecord != null) {
                        appContainer.telemetryRepository.recordQuery(event.queryRecord)
                    }
                }
            }
        }

        // Fixed-size worker pool prevents unbounded coroutine accumulation under heavy DNS traffic
        repeat(WORKER_COUNT) {
            workerJobs.add(
                serviceScope.launch {
                    for (request in reqChannel) {
                        runCatching {
                            handleInboundRequest(request, output)
                        }
                    }
                }
            )
        }

        packetLoopJob = serviceScope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isActive) {
                val length = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                } catch (_: Exception) {
                    break
                }

                if (length <= 0) continue

                val packetCopy = buffer.copyOf(length)

                val udpPacket = Ipv4UdpPacketParser.parseUdpDnsPacket(packetCopy, length)
                if (udpPacket != null) {
                    // Non-blocking enqueue: drops safely under extreme flood instead of spawning coroutines
                    reqChannel.trySend(InboundDnsRequest.Udp(udpPacket))
                    continue
                }

                val tcpSegment = Ipv4UdpPacketParser.parseTcpDnsSegment(packetCopy, length)
                if (tcpSegment != null) {
                    val result = tcpSessionManager.handleIncomingSegment(tcpSegment)
                    for (ctrlPacket in result.immediatePackets) {
                        writeRawTunPacket(ctrlPacket, output)
                    }
                    for ((sessionKey, dnsQuery) in result.completedDnsQueries) {
                        reqChannel.trySend(InboundDnsRequest.Tcp(sessionKey, dnsQuery))
                    }
                }
            }
        }
    }

    private suspend fun handleInboundRequest(
        request: InboundDnsRequest,
        output: FileOutputStream
    ) {
        val message = DnsPacket.parseAllQuestions(request.dnsPayload) ?: return
        val startNs = System.nanoTime()
        val settings = appContainer.latestSettings
        val resolver = appContainer.getActiveResolver()

        // Evaluate EVERY question in the DNS packet so multi-question queries cannot bypass filtering
        var blockedDecision: FilterDecision.Blocked? = null
        var invalidDecision: FilterDecision.InvalidDomain? = null
        var anyAllowlisted = false
        var primaryNormalizedDomain = message.questions.first().qName

        for ((index, q) in message.questions.withIndex()) {
            when (val decision = appContainer.filterEngine.evaluate(q.qName)) {
                is FilterDecision.Blocked -> {
                    blockedDecision = decision
                    break
                }
                is FilterDecision.InvalidDomain -> {
                    if (invalidDecision == null) invalidDecision = decision
                }
                is FilterDecision.Allowlisted -> {
                    anyAllowlisted = true
                    if (index == 0) primaryNormalizedDomain = decision.normalizedDomain
                }
                is FilterDecision.Allowed -> {
                    if (index == 0) primaryNormalizedDomain = decision.normalizedDomain
                }
            }
        }

        if (blockedDecision != null) {
            val blockedDns = DnsPacket.buildBlockedResponse(request.dnsPayload, message)
            sendDnsResponse(request, message, blockedDns, output)
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            enqueueTelemetry(
                TelemetryEvent(
                    totalDelta = 1L,
                    blockedDelta = 1L,
                    queryRecord = if (settings.showQueryLogs) {
                        DnsQueryRecord(
                            domain = blockedDecision.normalizedDomain,
                            category = blockedDecision.category,
                            isCustomRule = blockedDecision.isCustomRule,
                            action = QueryAction.BLOCKED,
                            latencyMs = elapsedMs,
                            resolverName = "Local Shield"
                        )
                    } else null
                )
            )
            return
        }

        if (invalidDecision != null) {
            val servFail = DnsPacket.buildServFailResponse(request.dnsPayload, message)
            sendDnsResponse(request, message, servFail, output)
            enqueueTelemetry(TelemetryEvent(totalDelta = 1L, failedDelta = 1L))
            return
        }

        val singleQuestion = message.questions.singleOrNull()
        val activeTransport = if (settings.preferDoH && resolver.supportsDoH && !resolver.dohUrl.isNullOrBlank()) {
            "doh"
        } else {
            "udp"
        }
        val cacheResolverKey = "${resolver.id}:$activeTransport:${resolver.primaryIp}"

        // Check DNS Cache first if enabled (single-question queries only)
        if (settings.enableDnsCache && singleQuestion != null) {
            val cachedHit = appContainer.dnsCache.getWithMetadata(
                domain = primaryNormalizedDomain,
                qType = singleQuestion.qType,
                qClass = singleQuestion.qClass,
                resolverKey = cacheResolverKey
            )
            if (cachedHit != null) {
                val rewritten = DnsPacket.rewriteResponseForCache(
                    responsePacket = cachedHit.responseBytes,
                    newTxId = message.transactionId,
                    elapsedSeconds = cachedHit.elapsedSeconds
                )
                sendDnsResponse(request, message, rewritten, output)
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                enqueueTelemetry(
                    TelemetryEvent(
                        totalDelta = 1L,
                        cachedDelta = 1L,
                        queryRecord = if (settings.showQueryLogs) {
                            DnsQueryRecord(
                                domain = primaryNormalizedDomain,
                                action = if (anyAllowlisted) QueryAction.ALLOWLISTED else QueryAction.CACHED,
                                latencyMs = elapsedMs,
                                resolverName = "${resolver.name} (Cache)"
                            )
                        } else null
                    )
                )
                return
            }
        }

        // Forward to upstream resolver with automatic fallback (force TCP if client queried over TCP)
        val forwardResult = appContainer.upstreamDnsClient.forwardQuery(
            queryPacket = request.dnsPayload,
            primaryResolver = resolver,
            fallbackResolvers = appContainer.getFallbackResolvers(resolver),
            preferDoH = settings.preferDoH,
            timeoutMs = 2500,
            forceTcp = request is InboundDnsRequest.Tcp
        )

        if (forwardResult != null) {
            val responseWithTx = DnsPacket.rewriteTransactionId(
                forwardResult.responseBytes,
                message.transactionId
            )
            sendDnsResponse(request, message, responseWithTx, output)

            if (settings.enableDnsCache &&
                singleQuestion != null &&
                !forwardResult.usedFallback &&
                DnsPacket.isCacheableResponse(forwardResult.responseBytes)
            ) {
                val ttl = DnsPacket.extractMinTtlSeconds(forwardResult.responseBytes)
                if (ttl > 0) {
                    appContainer.dnsCache.put(
                        domain = primaryNormalizedDomain,
                        qType = singleQuestion.qType,
                        resolverId = cacheResolverKey,
                        responseBytes = forwardResult.responseBytes,
                        ttlSeconds = ttl,
                        maxConfiguredTtlSeconds = settings.cacheTtlSeconds,
                        qClass = singleQuestion.qClass
                    )
                }
            }

            enqueueTelemetry(
                TelemetryEvent(
                    totalDelta = 1L,
                    queryRecord = if (settings.showQueryLogs) {
                        DnsQueryRecord(
                            domain = primaryNormalizedDomain,
                            action = if (anyAllowlisted) QueryAction.ALLOWLISTED else QueryAction.ALLOWED,
                            latencyMs = forwardResult.latencyMs,
                            resolverName = if (forwardResult.usedFallback) {
                                "${forwardResult.usedResolverName} (Fallback)"
                            } else {
                                forwardResult.usedResolverName
                            }
                        )
                    } else null
                )
            )
        } else {
            val servFail = DnsPacket.buildServFailResponse(request.dnsPayload, message)
            sendDnsResponse(request, message, servFail, output)
            enqueueTelemetry(
                TelemetryEvent(
                    totalDelta = 1L,
                    failedDelta = 1L,
                    queryRecord = if (settings.showQueryLogs) {
                        DnsQueryRecord(
                            domain = primaryNormalizedDomain,
                            action = QueryAction.FAILED,
                            latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L),
                            resolverName = resolver.name
                        )
                    } else null
                )
            )
        }
    }

    private fun enqueueTelemetry(event: TelemetryEvent) {
        telemetryChannel?.trySend(event)
    }

    private fun sendDnsResponse(
        request: InboundDnsRequest,
        message: DnsPacket.DnsMessageQuestions,
        dnsResponsePayload: ByteArray,
        output: FileOutputStream
    ) {
        when (request) {
            is InboundDnsRequest.Udp -> {
                val maxUdpPayload = if (request.packet.ipVersion == 6) {
                    MAX_UDP_DNS_PAYLOAD_V6
                } else {
                    MAX_UDP_DNS_PAYLOAD_V4
                }
                val safePayload = if (dnsResponsePayload.size > maxUdpPayload) {
                    DnsPacket.buildTruncatedResponse(request.dnsPayload, message)
                } else {
                    dnsResponsePayload
                }
                val rawResponse = Ipv4UdpPacketParser.buildUdpResponsePacket(request.packet, safePayload)
                writeRawTunPacket(rawResponse, output)
            }

            is InboundDnsRequest.Tcp -> {
                val segments = tcpSessionManager.buildDnsResponseSegments(request.sessionKey, dnsResponsePayload)
                for (segment in segments) {
                    writeRawTunPacket(segment, output)
                }
            }
        }
    }

    private fun writeRawTunPacket(rawPacket: ByteArray, output: FileOutputStream) {
        synchronized(writeLock) {
            runCatching {
                output.write(rawPacket)
            }
        }
    }

    private fun stopVpnInternal(
        userInitiated: Boolean,
        errorType: VpnErrorType? = null,
        errorMessage: String? = null
    ) {
        stopWorkersAndChannels()

        runCatching { vpnInterface?.close() }
        vpnInterface = null

        if (userInitiated || errorType == null) {
            val resolver = appContainer.getActiveResolver()
            appContainer.updateVpnState(
                VpnConnectionState(
                    status = VpnStatus.DISCONNECTED,
                    activeResolverName = resolver.name,
                    activeResolverAddress = resolver.primaryIp
                )
            )
        } else {
            appContainer.reportVpnError(errorType, errorMessage ?: "VPN disconnected unexpectedly.")
        }

        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PingForgeVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()
    }
}

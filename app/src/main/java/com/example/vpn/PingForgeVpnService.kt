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
import com.example.engine.filter.FilterDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Local split-DNS VpnService.
 * Routes only our virtual DNS endpoint (10.111.222.1/32) into the TUN interface so:
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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetLoopJob: Job? = null
    private val writeLock = Any()
    private val queryConcurrencyLimiter = Semaphore(24)

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

    private fun startPacketLoop(pfd: ParcelFileDescriptor) {
        packetLoopJob?.cancel()
        packetLoopJob = serviceScope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
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
                val udpPacket = Ipv4UdpPacketParser.parseUdpDnsPacket(packetCopy, length) ?: continue
                val question = DnsPacket.parseQuestion(udpPacket.dnsPayload) ?: continue

                launch {
                    queryConcurrencyLimiter.withPermit {
                        handleDnsQuestion(udpPacket, question, output)
                    }
                }
            }
        }
    }

    private suspend fun handleDnsQuestion(
        udpPacket: Ipv4UdpPacketParser.UdpDnsPacket,
        question: DnsPacket.DnsQuestion,
        output: FileOutputStream
    ) {
        val startNs = System.nanoTime()
        val settings = appContainer.latestSettings
        val resolver = appContainer.getActiveResolver()
        val decision = appContainer.filterEngine.evaluate(question.qName)

        when (decision) {
            is FilterDecision.Blocked -> {
                val blockedDns = DnsPacket.buildBlockedResponse(udpPacket.dnsPayload, question)
                writeTunResponse(udpPacket, blockedDns, output)
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

                appContainer.settingsRepository.incrementCounters(totalDelta = 1L, blockedDelta = 1L)
                if (settings.showQueryLogs) {
                    appContainer.telemetryRepository.recordQuery(
                        DnsQueryRecord(
                            domain = decision.normalizedDomain,
                            category = decision.category,
                            isCustomRule = decision.isCustomRule,
                            action = QueryAction.BLOCKED,
                            latencyMs = elapsedMs,
                            resolverName = "Local Shield"
                        )
                    )
                }
                return
            }

            is FilterDecision.InvalidDomain -> {
                val servFail = DnsPacket.buildServFailResponse(udpPacket.dnsPayload, question)
                writeTunResponse(udpPacket, servFail, output)
                appContainer.settingsRepository.incrementCounters(totalDelta = 1L, failedDelta = 1L)
                return
            }

            is FilterDecision.Allowlisted, is FilterDecision.Allowed -> {
                val normalizedDomain = when (decision) {
                    is FilterDecision.Allowlisted -> decision.normalizedDomain
                    is FilterDecision.Allowed -> decision.normalizedDomain
                    else -> question.qName
                }
                val isAllowlisted = decision is FilterDecision.Allowlisted

                // Check DNS Cache first if enabled
                if (settings.enableDnsCache) {
                    val cached = appContainer.dnsCache.get(normalizedDomain, question.qType, resolver.id)
                    if (cached != null) {
                        val rewritten = DnsPacket.rewriteTransactionId(cached, question.transactionId)
                        writeTunResponse(udpPacket, rewritten, output)
                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        appContainer.settingsRepository.incrementCounters(totalDelta = 1L, cachedDelta = 1L)
                        if (settings.showQueryLogs) {
                            appContainer.telemetryRepository.recordQuery(
                                DnsQueryRecord(
                                    domain = normalizedDomain,
                                    action = if (isAllowlisted) QueryAction.ALLOWLISTED else QueryAction.CACHED,
                                    latencyMs = elapsedMs,
                                    resolverName = "${resolver.name} (Cache)"
                                )
                            )
                        }
                        return
                    }
                }

                // Forward to upstream resolver with automatic fallback
                val forwardResult = appContainer.upstreamDnsClient.forwardQuery(
                    queryPacket = udpPacket.dnsPayload,
                    primaryResolver = resolver,
                    fallbackResolvers = appContainer.getFallbackResolvers(resolver),
                    preferDoH = settings.preferDoH,
                    timeoutMs = 2500
                )

                if (forwardResult != null) {
                    val responseWithTx = DnsPacket.rewriteTransactionId(
                        forwardResult.responseBytes,
                        question.transactionId
                    )
                    writeTunResponse(udpPacket, responseWithTx, output)

                    if (settings.enableDnsCache) {
                        val ttl = DnsPacket.extractMinTtlSeconds(forwardResult.responseBytes)
                        appContainer.dnsCache.put(
                            domain = normalizedDomain,
                            qType = question.qType,
                            resolverId = resolver.id,
                            responseBytes = forwardResult.responseBytes,
                            ttlSeconds = ttl,
                            maxConfiguredTtlSeconds = settings.cacheTtlSeconds
                        )
                    }

                    appContainer.settingsRepository.incrementCounters(totalDelta = 1L)
                    if (settings.showQueryLogs) {
                        appContainer.telemetryRepository.recordQuery(
                            DnsQueryRecord(
                                domain = normalizedDomain,
                                action = if (isAllowlisted) QueryAction.ALLOWLISTED else QueryAction.ALLOWED,
                                latencyMs = forwardResult.latencyMs,
                                resolverName = if (forwardResult.usedFallback) {
                                    "${forwardResult.usedResolverName} (Fallback)"
                                } else {
                                    forwardResult.usedResolverName
                                }
                            )
                        )
                    }
                } else {
                    val servFail = DnsPacket.buildServFailResponse(udpPacket.dnsPayload, question)
                    writeTunResponse(udpPacket, servFail, output)
                    appContainer.settingsRepository.incrementCounters(totalDelta = 1L, failedDelta = 1L)
                    if (settings.showQueryLogs) {
                        appContainer.telemetryRepository.recordQuery(
                            DnsQueryRecord(
                                domain = normalizedDomain,
                                action = QueryAction.FAILED,
                                latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L),
                                resolverName = resolver.name
                            )
                        )
                    }
                }
            }
        }
    }

    private fun writeTunResponse(
        udpPacket: Ipv4UdpPacketParser.UdpDnsPacket,
        dnsResponsePayload: ByteArray,
        output: FileOutputStream
    ) {
        val rawResponse = Ipv4UdpPacketParser.buildUdpResponsePacket(udpPacket, dnsResponsePayload)
        synchronized(writeLock) {
            runCatching {
                output.write(rawResponse)
            }
        }
    }

    private fun stopVpnInternal(
        userInitiated: Boolean,
        errorType: VpnErrorType? = null,
        errorMessage: String? = null
    ) {
        packetLoopJob?.cancel()
        packetLoopJob = null

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

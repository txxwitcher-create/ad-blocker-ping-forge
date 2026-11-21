package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.DnsCounters
import com.example.domain.model.DnsResolver
import com.example.domain.model.NetworkStatus
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.ShieldCoreVisual
import com.example.ui.components.StatusPill
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.Emerald
import com.example.ui.theme.IceCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmAmber

@Composable
fun HomeScreen(
    vpnState: VpnConnectionState,
    settings: AppSettings,
    networkStatus: NetworkStatus,
    activeResolver: DnsResolver,
    counters: DnsCounters,
    blocklistStatus: BlocklistStatus,
    onToggleConnect: () -> Unit,
    onSelectMode: (AppMode) -> Unit,
    onNavigateToProtection: () -> Unit,
    onNavigateToGaming: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (vpnState.status) {
            VpnStatus.CONNECTED -> Emerald
            VpnStatus.CONNECTING -> IceCyan
            VpnStatus.ERROR -> AlertCrimson
            VpnStatus.DISCONNECTED -> ElectricViolet
        },
        label = "status_color"
    )

    val statusLabel = when (vpnState.status) {
        VpnStatus.CONNECTED -> "CONNECTED"
        VpnStatus.CONNECTING -> "CONNECTING"
        VpnStatus.ERROR -> "ERROR"
        VpnStatus.DISCONNECTED -> "DISCONNECTED"
    }

    val statusDescription = when (vpnState.status) {
        VpnStatus.CONNECTED -> if (settings.selectedMode == AppMode.GAMING) {
            "Gaming DNS routing active • Category filtering paused for zero-overhead game lookups"
        } else {
            "Device-wide local DNS shield active • Filtering ${settings.enabledCategories.size} categories"
        }
        VpnStatus.CONNECTING -> "Establishing local split-DNS VPN tunnel..."
        VpnStatus.ERROR -> vpnState.errorMessage ?: "Connection error occurred"
        VpnStatus.DISCONNECTED -> "Local DNS shield is offline • Tap Connect to protect DNS queries"
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("home_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Branding & Live Network Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.brand_full_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Split-DNS Protection & Ping Forge",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    NetworkBadgePill(networkStatus = networkStatus)
                }
            }

            // Hero Shield + Connect/Disconnect Control Card
            item {
                GlassCard(
                    accentBorder = statusColor,
                    cornerRadius = 26.dp,
                    contentPadding = 20.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        StatusPill(
                            text = statusLabel,
                            color = statusColor,
                            modifier = Modifier.testTag("vpn_status_pill")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier.size(164.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ShieldCoreVisual(
                                status = vpnState.status,
                                reduceMotion = settings.reduceMotion,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = statusDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (vpnState.status == VpnStatus.ERROR) AlertCrimson else TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        val isConnecting = vpnState.status == VpnStatus.CONNECTING
                        val isConnected = vpnState.status == VpnStatus.CONNECTED
                        val buttonText = when (vpnState.status) {
                            VpnStatus.CONNECTED -> "DISCONNECT SHIELD"
                            VpnStatus.CONNECTING -> "CONNECTING..."
                            VpnStatus.ERROR -> "RETRY CONNECTION"
                            VpnStatus.DISCONNECTED -> "CONNECT DNS SHIELD"
                        }

                        Button(
                            onClick = onToggleConnect,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isConnected) AlertCrimson.copy(alpha = 0.22f) else statusColor,
                                contentColor = if (isConnected) Color.White else Color(0xFF070910)
                            ),
                            border = if (isConnected) BorderStroke(1.dp, AlertCrimson.copy(alpha = 0.6f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("vpn_connect_button")
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.2.dp,
                                    color = Color(0xFF070910)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.PowerSettingsNew,
                                    contentDescription = buttonText,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = buttonText,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Explicit Other-VPN Conflict Warning Banner if detected
            item {
                AnimatedVisibility(visible = networkStatus.hasOtherVpnActive && vpnState.status != VpnStatus.CONNECTED) {
                    GlassCard(accentBorder = WarmAmber, contentPadding = 14.dp) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "VPN Conflict",
                                tint = WarmAmber
                            )
                            Column {
                                Text(
                                    text = "Another VPN Is Active",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = WarmAmber
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Android allows only one active VPN service at a time. Disconnect your other VPN before enabling AD BLOCKER | PING FORGE.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Operating Mode Switcher (Protection / Gaming / Balanced)
            item {
                GlassCard(contentPadding = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OPERATING MODE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = settings.selectedMode.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = IceCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppMode.entries.forEach { mode ->
                            val selected = settings.selectedMode == mode
                            val accent = when (mode) {
                                AppMode.PROTECTION -> Emerald
                                AppMode.GAMING -> IceCyan
                                AppMode.BALANCED -> ElectricViolet
                            }
                            Surface(
                                onClick = { onSelectMode(mode) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("mode_chip_${mode.name.lowercase()}")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) Color.White else TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = settings.selectedMode.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Active DNS Resolver & Ping Forge Entry Point
            item {
                GlassCard(
                    accentBorder = IceCyan.copy(alpha = 0.5f),
                    onClick = onNavigateToGaming,
                    modifier = Modifier.testTag("home_gaming_dns_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(IceCyan.copy(alpha = 0.22f), ElectricViolet.copy(alpha = 0.22f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = "Ping Forge DNS",
                                    tint = IceCyan
                                )
                            }
                            Column {
                                Text(
                                    text = "Ping Forge • Active DNS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = IceCyan
                                )
                                Text(
                                    text = activeResolver.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${activeResolver.primaryIp} • ${if (settings.preferDoH && activeResolver.supportsDoH) "DoH Encrypted" else "UDP Port 53"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Open Ping Forge",
                            tint = IceCyan
                        )
                    }
                }
            }

            // Protection Summary Card
            item {
                GlassCard(
                    onClick = onNavigateToProtection,
                    modifier = Modifier.testTag("home_protection_summary_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = "Protection Summary",
                                tint = Emerald
                            )
                            Text(
                                text = "Protection Summary",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Manage",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricViolet
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatMiniTile(
                            label = "BLOCKED",
                            value = counters.blockedQueries.toString(),
                            accent = WarmAmber,
                            modifier = Modifier.weight(1f)
                        )
                        StatMiniTile(
                            label = "TOTAL DNS",
                            value = counters.totalQueries.toString(),
                            accent = ElectricViolet,
                            modifier = Modifier.weight(1f)
                        )
                        StatMiniTile(
                            label = "CACHE HITS",
                            value = counters.cachedQueries.toString(),
                            accent = Emerald,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "${blocklistStatus.totalDomainsLoaded} domains indexed • ${settings.enabledCategories.size}/6 categories active • ${settings.allowlistDomains.size} allowlisted",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Transparent Split-DNS Architecture Note
            item {
                GlassCard(contentPadding = 14.dp) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = ElectricViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Local Split-DNS Architecture: Only DNS lookups route through the local VPN interface (10.111.222.1). App data and game packets travel directly over your network without tunneling overhead or inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkBadgePill(networkStatus: NetworkStatus) {
    val (color, icon) = when {
        !networkStatus.isConnected -> AlertCrimson to Icons.Rounded.WifiOff
        networkStatus.transportType == TransportType.WIFI -> IceCyan to Icons.Rounded.Wifi
        networkStatus.transportType == TransportType.CELLULAR -> Emerald to Icons.Rounded.NetworkCheck
        else -> ElectricViolet to Icons.Rounded.Dns
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = networkStatus.interfaceSummary,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = networkStatus.interfaceSummary,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun StatMiniTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

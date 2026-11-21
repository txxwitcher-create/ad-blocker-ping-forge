package com.example.ui.gaming

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.BenchmarkResult
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsResolver
import com.example.domain.model.NetworkStatus
import com.example.engine.pingforge.BenchmarkProgress
import com.example.engine.pingforge.PingForgeEngine
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.Emerald
import com.example.ui.theme.IceCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmAmber
import kotlin.math.roundToInt

@Composable
fun GamingScreen(
    settings: AppSettings,
    networkStatus: NetworkStatus,
    activeResolver: DnsResolver,
    benchmarkResults: List<BenchmarkResult>,
    benchmarkProgress: BenchmarkProgress,
    onRunBenchmark: () -> Unit,
    onSelectResolver: (String) -> Unit,
    onToggleAutoSelect: (Boolean) -> Unit,
    onSelectMode: (AppMode) -> Unit,
    onSaveCustomDns: (String, String, String) -> Boolean,
    modifier: Modifier = Modifier
) {
    var showCustomDnsDialog by remember { mutableStateOf(false) }

    val recommended = remember(benchmarkResults) {
        PingForgeEngine.pickRecommended(benchmarkResults)
    }
    val maxMeasuredMs = remember(benchmarkResults) {
        benchmarkResults.mapNotNull { it.medianMs }.maxOrNull()?.coerceAtLeast(40.0) ?: 100.0
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("gaming_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Ping Forge",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Real DNS Resolver Benchmarking & Routing",
                            style = MaterialTheme.typography.bodySmall,
                            color = IceCyan
                        )
                    }
                    StatusPill(
                        text = networkStatus.interfaceSummary,
                        color = if (networkStatus.isConnected) IceCyan else AlertCrimson
                    )
                }
            }

            // Honest Technical Notice: DNS lookup != In-Game Server Ping
            item {
                GlassCard(accentBorder = ElectricViolet.copy(alpha = 0.4f), contentPadding = 14.dp) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "DNS Speed Information",
                            tint = ElectricViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "DNS lookup latency measures how fast domain names resolve to IP addresses—it does not change in-game server ping (which depends on ISP routing, distance, and game servers). Ping Forge selects the fastest, most reliable DNS resolver for crisp connection establishment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Gaming Mode Quick Activation & Auto-Select Fastest Resolver
            item {
                GlassCard(
                    accentBorder = if (settings.selectedMode == AppMode.GAMING) IceCyan else null,
                    contentPadding = 16.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SportsEsports,
                                contentDescription = "Gaming Mode",
                                tint = IceCyan
                            )
                            Column {
                                Text(
                                    text = "Gaming Mode",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Prioritizes DNS speed, caching & bypasses category filters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = settings.selectedMode == AppMode.GAMING,
                            onCheckedChange = { enabled ->
                                onSelectMode(if (enabled) AppMode.GAMING else AppMode.BALANCED)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF041418),
                                checkedTrackColor = IceCyan
                            ),
                            modifier = Modifier.testTag("gaming_mode_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic Fastest Resolver",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Applies top reliable benchmarked resolver automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.autoSelectFastestResolver,
                            onCheckedChange = onToggleAutoSelect,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF070910),
                                checkedTrackColor = Emerald
                            ),
                            modifier = Modifier.testTag("auto_resolver_switch")
                        )
                    }
                }
            }

            // Benchmark Action Card & Live Progress
            item {
                GlassCard(accentBorder = IceCyan.copy(alpha = 0.55f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ACTIVE RESOLVER",
                                style = MaterialTheme.typography.labelSmall,
                                color = IceCyan
                            )
                            Text(
                                text = activeResolver.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "${activeResolver.primaryIp} • Fallback: ${activeResolver.secondaryIp}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = onRunBenchmark,
                            enabled = !benchmarkProgress.isRunning,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = IceCyan,
                                contentColor = Color(0xFF041418)
                            ),
                            modifier = Modifier.testTag("run_benchmark_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Benchmark resolvers",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (benchmarkProgress.isRunning) "Testing..." else "Benchmark",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    AnimatedVisibility(visible = benchmarkProgress.isRunning) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            val fraction = if (benchmarkProgress.totalResolvers > 0) {
                                benchmarkProgress.completedResolvers.toFloat() / benchmarkProgress.totalResolvers.toFloat()
                            } else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = IceCyan,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Probing ${benchmarkProgress.currentResolverName} (${benchmarkProgress.completedResolvers}/${benchmarkProgress.totalResolvers})...",
                                style = MaterialTheme.typography.labelSmall,
                                color = IceCyan
                            )
                        }
                    }

                    if (benchmarkProgress.lastError != null && !benchmarkProgress.isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = benchmarkProgress.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertCrimson
                        )
                    }
                }
            }

            // Recommended Resolver Spotlight (Only shown when real benchmark data exists)
            if (recommended != null && recommended.medianMs != null) {
                item {
                    GlassCard(accentBorder = Emerald.copy(alpha = 0.65f), contentPadding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Recommended Resolver",
                                    tint = Emerald
                                )
                                Column {
                                    Text(
                                        text = "RECOMMENDED ON ${recommended.transportLabel.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Emerald
                                    )
                                    Text(
                                        text = recommended.resolverName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Median ${recommended.medianMs.roundToInt()} ms • ${recommended.reliabilityPercent}% reliable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onSelectResolver(recommended.resolverId) },
                                border = BorderStroke(1.dp, Emerald.copy(alpha = 0.6f))
                            ) {
                                Text("Use", color = Emerald)
                            }
                        }
                    }
                }
            }

            // Measured Benchmark Comparison Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MEASURED RESOLVER COMPARISON",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    TextButton(
                        onClick = { showCustomDnsDialog = true },
                        modifier = Modifier.testTag("configure_custom_dns_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Custom DNS",
                            modifier = Modifier.size(16.dp),
                            tint = ElectricViolet
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Custom DNS", color = ElectricViolet)
                    }
                }
            }

            if (benchmarkResults.isEmpty()) {
                item {
                    GlassCard(contentPadding = 16.dp) {
                        Text(
                            text = "No DNS measurements collected yet. Tap Benchmark above to send real DNS test queries and measure actual response latency and reliability on your current network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            } else {
                items(benchmarkResults, key = { it.resolverId }) { res ->
                    BenchmarkComparisonCard(
                        result = res,
                        isSelected = activeResolver.id == res.resolverId,
                        maxScaleMs = maxMeasuredMs,
                        onSelect = { onSelectResolver(res.resolverId) }
                    )
                }
            }

            // Manual Resolver Selection List
            item {
                Text(
                    text = "MANUAL RESOLVER DIRECTORY",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(BuiltInResolvers.defaultResolvers, key = { it.id }) { resolver ->
                val isSelected = !settings.autoSelectFastestResolver &&
                    settings.selectedResolverId == resolver.id
                val isActiveNow = activeResolver.id == resolver.id

                GlassCard(
                    accentBorder = if (isActiveNow) IceCyan.copy(alpha = 0.6f) else null,
                    contentPadding = 14.dp,
                    onClick = { onSelectResolver(resolver.id) },
                    modifier = Modifier.testTag("resolver_option_${resolver.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = resolver.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                if (isActiveNow) {
                                    StatusPill(
                                        text = if (isSelected) "Selected" else "Auto Active",
                                        color = IceCyan
                                    )
                                }
                            }
                            Text(
                                text = resolver.providerTag,
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricViolet
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = resolver.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        if (isActiveNow) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Active resolver",
                                tint = IceCyan
                            )
                        }
                    }
                }
            }

            // Custom DNS Entry Card
            item {
                val hasCustom = settings.customDnsPrimary.isNotBlank()
                val isCustomSelected = settings.selectedResolverId == BuiltInResolvers.CUSTOM_RESOLVER_ID && hasCustom
                GlassCard(
                    accentBorder = if (isCustomSelected) ElectricViolet else null,
                    contentPadding = 14.dp,
                    onClick = { showCustomDnsDialog = true },
                    modifier = Modifier.testTag("custom_dns_summary_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Custom DNS Server",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (hasCustom) {
                                    "Primary: ${settings.customDnsPrimary}" +
                                        (settings.customDnsSecondary.takeIf { it.isNotBlank() }?.let { " • Backup: $it" } ?: "")
                                } else {
                                    "Tap to configure a custom IPv4 & optional DoH resolver"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit Custom DNS",
                            tint = ElectricViolet
                        )
                    }
                }
            }
        }
    }

    if (showCustomDnsDialog) {
        CustomDnsDialog(
            initialPrimary = settings.customDnsPrimary,
            initialSecondary = settings.customDnsSecondary,
            initialDoh = settings.customDnsDohUrl,
            onSave = { p, s, d ->
                if (onSaveCustomDns(p, s, d)) {
                    showCustomDnsDialog = false
                }
            },
            onDismiss = { showCustomDnsDialog = false }
        )
    }
}

@Composable
private fun BenchmarkComparisonCard(
    result: BenchmarkResult,
    isSelected: Boolean,
    maxScaleMs: Double,
    onSelect: () -> Unit
) {
    val barColor = when {
        result.reliabilityPercent < 60 || result.medianMs == null -> AlertCrimson
        result.medianMs <= 35.0 -> Emerald
        result.medianMs <= 80.0 -> IceCyan
        else -> WarmAmber
    }
    val progress = if (result.medianMs != null && maxScaleMs > 0.0) {
        (result.medianMs / maxScaleMs).toFloat().coerceIn(0.08f, 1f)
    } else 0f

    GlassCard(
        accentBorder = if (isSelected) IceCyan else null,
        contentPadding = 14.dp,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.resolverName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "${result.resolverAddress} • ${result.successCount}/${result.totalCount} ok (${result.reliabilityPercent}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                text = if (result.medianMs != null) "${result.medianMs.roundToInt()} ms" else "Timeout",
                style = MaterialTheme.typography.titleMedium,
                color = barColor,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.07f))
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(barColor.copy(alpha = 0.7f), barColor)
                            )
                        )
                )
            }
        }

        if (result.failedQueries > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${result.failedQueries} failed/timed out queries${result.lastError?.let { " • $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = WarmAmber
            )
        }
    }
}

@Composable
fun CustomDnsDialog(
    initialPrimary: String,
    initialSecondary: String,
    initialDoh: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var primary by remember { mutableStateOf(initialPrimary) }
    var secondary by remember { mutableStateOf(initialSecondary) }
    var dohUrl by remember { mutableStateOf(initialDoh) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom DNS Configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = { Text("Primary IPv4 DNS *") },
                    placeholder = { Text("e.g. 1.1.1.1") },
                    singleLine = true,
                    modifier = Modifier.testTag("custom_dns_primary_input")
                )
                OutlinedTextField(
                    value = secondary,
                    onValueChange = { secondary = it },
                    label = { Text("Fallback IPv4 DNS (Optional)") },
                    placeholder = { Text("e.g. 1.0.0.1") },
                    singleLine = true,
                    modifier = Modifier.testTag("custom_dns_secondary_input")
                )
                OutlinedTextField(
                    value = dohUrl,
                    onValueChange = { dohUrl = it },
                    label = { Text("DoH Endpoint URL (Optional)") },
                    placeholder = { Text("https://dns.example.com/dns-query") },
                    singleLine = true,
                    modifier = Modifier.testTag("custom_dns_doh_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(primary, secondary, dohUrl) },
                modifier = Modifier.testTag("custom_dns_save_button")
            ) {
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

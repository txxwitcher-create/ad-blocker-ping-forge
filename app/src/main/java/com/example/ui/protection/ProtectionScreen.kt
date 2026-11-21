package com.example.ui.protection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.DnsCounters
import com.example.domain.model.DnsQueryRecord
import com.example.domain.model.ProtectionCategory
import com.example.domain.model.QueryAction
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnStatus
import com.example.engine.filter.FilterDecision
import com.example.ui.DomainTestResult
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProtectionScreen(
    vpnState: VpnConnectionState,
    settings: AppSettings,
    blocklistStatus: BlocklistStatus,
    counters: DnsCounters,
    recentLogs: List<DnsQueryRecord>,
    domainTestResult: DomainTestResult?,
    onToggleCategory: (ProtectionCategory, Boolean) -> Unit,
    onUpdateBlocklists: () -> Unit,
    onAddAllowlist: (String) -> Boolean,
    onRemoveAllowlist: (String) -> Unit,
    onAddCustomBlock: (String) -> Boolean,
    onRemoveCustomBlock: (String) -> Unit,
    onTestDomain: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var showCustomBlockDialog by remember { mutableStateOf(false) }
    var testQuery by remember { mutableStateOf("") }

    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val lastUpdatedLabel = if (blocklistStatus.lastUpdatedMillis > 0L) {
        dateFormatter.format(Date(blocklistStatus.lastUpdatedMillis))
    } else {
        "Bundled baseline active"
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("protection_lazy_column"),
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
                            text = "DNS Protection",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Real-time category filtering & custom rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    val activePillColor = when {
                        vpnState.status != VpnStatus.CONNECTED -> TextMuted
                        settings.selectedMode == AppMode.GAMING -> WarmAmber
                        else -> Emerald
                    }
                    val activePillText = when {
                        vpnState.status != VpnStatus.CONNECTED -> "VPN OFF"
                        settings.selectedMode == AppMode.GAMING -> "GAMING BYPASS"
                        else -> "ENFORCING"
                    }
                    StatusPill(text = activePillText, color = activePillColor)
                }
            }

            if (settings.selectedMode == AppMode.GAMING && vpnState.status == VpnStatus.CONNECTED) {
                item {
                    GlassCard(accentBorder = WarmAmber, contentPadding = 14.dp) {
                        Text(
                            text = "Gaming Mode is active: category blocklists are temporarily bypassed to protect game CDN & matchmaking lookups. Switch to Balanced or Protection Mode to enforce category filtering.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarmAmber
                        )
                    }
                }
            }

            // Blocklist Status & Live Update Card
            item {
                GlassCard(accentBorder = ElectricViolet.copy(alpha = 0.4f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BLOCKLIST ENGINE",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricViolet
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${blocklistStatus.totalDomainsLoaded} domains loaded",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "Last update: $lastUpdatedLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            if (blocklistStatus.lastError != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = blocklistStatus.lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarmAmber
                                )
                            }
                        }

                        Button(
                            onClick = onUpdateBlocklists,
                            enabled = !blocklistStatus.isUpdating,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricViolet,
                                contentColor = Color(0xFF070910)
                            ),
                            modifier = Modifier.testTag("update_blocklists_button")
                        ) {
                            if (blocklistStatus.isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF070910)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Updating")
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Update blocklists",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Update")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Blocked: ${counters.blockedQueries}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarmAmber
                        )
                        Text(
                            text = "Total: ${counters.totalQueries}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "Cached: ${counters.cachedQueries}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Emerald
                        )
                    }
                }
            }

            // Independent Protection Category Toggles
            item {
                Text(
                    text = "PROTECTION CATEGORIES",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            items(ProtectionCategory.entries, key = { it.id }) { category ->
                val enabled = settings.enabledCategories.contains(category)
                val count = blocklistStatus.categoryCounts[category] ?: 0
                GlassCard(
                    accentBorder = if (enabled) Emerald.copy(alpha = 0.35f) else null,
                    contentPadding = 14.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = category.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "$count rules",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (enabled) Emerald else TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = category.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { onToggleCategory(category, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF06150E),
                                checkedTrackColor = Emerald,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier.testTag("category_toggle_${category.id}")
                        )
                    }
                }
            }

            // Allowlist & Custom Blocklist Management Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassCard(
                        accentBorder = Emerald.copy(alpha = 0.4f),
                        onClick = { showAllowlistDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manage_allowlist_card")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Allowlist",
                            tint = Emerald
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Allowlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${settings.allowlistDomains.size} domains • Highest priority",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    GlassCard(
                        accentBorder = WarmAmber.copy(alpha = 0.4f),
                        onClick = { showCustomBlockDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manage_custom_blocklist_card")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = "Custom Blocklist",
                            tint = WarmAmber
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Custom Block",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${settings.customBlocklistDomains.size} custom domains",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Live Domain Rule Tester (Verify Allowlist Precedence & Category Matching)
            item {
                GlassCard(contentPadding = 14.dp) {
                    Text(
                        text = "RULE VERIFICATION TESTER",
                        style = MaterialTheme.typography.labelMedium,
                        color = IceCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = testQuery,
                            onValueChange = { testQuery = it },
                            placeholder = { Text("e.g. doubleclick.net") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("rule_tester_input")
                        )
                        Button(
                            onClick = { onTestDomain(testQuery) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("rule_tester_button")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "Test domain")
                        }
                    }

                    if (domainTestResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val (verdict, color) = when (val d = domainTestResult.decision) {
                            is FilterDecision.Allowlisted -> "ALLOWLISTED (${d.normalizedDomain}) — Allowed with top precedence" to Emerald
                            is FilterDecision.Blocked -> {
                                val src = if (d.isCustomRule) "Custom Blocklist" else d.category?.displayName ?: "Blocklist"
                                "BLOCKED (${d.normalizedDomain}) — Matched $src" to WarmAmber
                            }
                            is FilterDecision.Allowed -> "ALLOWED (${d.normalizedDomain}) — Not matched by active blocklists" to IceCyan
                            is FilterDecision.InvalidDomain -> "INVALID DOMAIN — Enter a valid hostname (e.g. ads.example.com)" to AlertCrimson
                        }
                        Text(
                            text = verdict,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            modifier = Modifier.testTag("rule_tester_result")
                        )
                    }
                }
            }

            // Recent Live DNS Queries Log
            item {
                Text(
                    text = "RECENT DNS ACTIVITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (recentLogs.isEmpty()) {
                item {
                    GlassCard(contentPadding = 16.dp) {
                        Text(
                            text = "No DNS queries recorded yet. Connect the DNS Shield and browse or launch an app to inspect live filtered and resolved domains.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            } else {
                items(recentLogs.take(25), key = { "${it.id}_${it.timestampMillis}" }) { record ->
                    QueryLogRow(record = record)
                }
            }
        }
    }

    if (showAllowlistDialog) {
        DomainListManagerDialog(
            title = "Allowlist Domains",
            subtitle = "Allowlisted domains always bypass all category & custom blocklists.",
            domains = settings.allowlistDomains.sorted(),
            accentColor = Emerald,
            onAdd = onAddAllowlist,
            onRemove = onRemoveAllowlist,
            onDismiss = { showAllowlistDialog = false }
        )
    }

    if (showCustomBlockDialog) {
        DomainListManagerDialog(
            title = "Custom Blocklist",
            subtitle = "Block specific domains & all their subdomains whenever Protection or Balanced Mode is active.",
            domains = settings.customBlocklistDomains.sorted(),
            accentColor = WarmAmber,
            onAdd = onAddCustomBlock,
            onRemove = onRemoveCustomBlock,
            onDismiss = { showCustomBlockDialog = false }
        )
    }
}

@Composable
private fun QueryLogRow(record: DnsQueryRecord) {
    val (badgeColor, badgeText) = when (record.action) {
        QueryAction.BLOCKED -> WarmAmber to (record.category?.displayName ?: if (record.isCustomRule) "Custom" else "Blocked")
        QueryAction.ALLOWLISTED -> Emerald to "Allowlisted"
        QueryAction.CACHED -> IceCyan to "Cached"
        QueryAction.ALLOWED -> ElectricViolet to "Resolved"
        QueryAction.FAILED -> AlertCrimson to "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = record.domain,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${record.resolverName} • ${record.latencyMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
        StatusPill(text = badgeText, color = badgeColor)
    }
}

@Composable
fun DomainListManagerDialog(
    title: String,
    subtitle: String,
    domains: List<String>,
    accentColor: Color,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("domain_dialog_input")
                    )
                    IconButton(
                        onClick = {
                            if (onAdd(input)) input = ""
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .testTag("domain_dialog_add_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add domain", tint = accentColor)
                    }
                }

                if (domains.isEmpty()) {
                    Text(
                        text = "No domains added yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(domains, key = { it }) { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = domain,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onRemove(domain) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remove $domain",
                                        tint = AlertCrimson,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

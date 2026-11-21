package com.example.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeStyle
import com.example.domain.model.BlocklistStatus
import com.example.domain.model.BuiltInResolvers
import com.example.domain.model.DnsCounters
import com.example.domain.model.DnsResolver
import com.example.domain.model.ProtectionCategory
import com.example.ui.components.GlassCard
import com.example.ui.gaming.CustomDnsDialog
import com.example.ui.protection.DomainListManagerDialog
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
fun SettingsScreen(
    settings: AppSettings,
    activeResolver: DnsResolver,
    counters: DnsCounters,
    blocklistStatus: BlocklistStatus,
    onToggleCategory: (ProtectionCategory, Boolean) -> Unit,
    onSelectResolver: (String) -> Unit,
    onToggleAutoSelect: (Boolean) -> Unit,
    onSaveCustomDns: (String, String, String) -> Boolean,
    onTogglePreferDoH: (Boolean) -> Unit,
    onToggleDnsCache: (Boolean) -> Unit,
    onChangeCacheTtl: (Int) -> Unit,
    onToggleAutoUpdateBlocklists: (Boolean) -> Unit,
    onChangeUpdateIntervalHours: (Int) -> Unit,
    onUpdateBlocklistsNow: () -> Unit,
    onToggleRebenchmarkOnNetwork: (Boolean) -> Unit,
    onToggleShowQueryLogs: (Boolean) -> Unit,
    onChangeThemeStyle: (AppThemeStyle) -> Unit,
    onToggleReduceMotion: (Boolean) -> Unit,
    onReplayIntro: () -> Unit,
    onAddAllowlist: (String) -> Boolean,
    onRemoveAllowlist: (String) -> Unit,
    onAddCustomBlock: (String) -> Boolean,
    onRemoveCustomBlock: (String) -> Unit,
    onClearStatsAndLogs: () -> Unit,
    onResetConfiguration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showDnsPickerDialog by remember { mutableStateOf(false) }
    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var showCustomBlockDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Persistent DNS, filtering, Ping Forge & visual controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // 1. DNS Provider & Encryption
            item {
                SectionLabel("DNS PROVIDER & TRANSPORT")
                GlassCard(contentPadding = 16.dp) {
                    SettingsClickRow(
                        title = "Active DNS Provider",
                        subtitle = if (settings.autoSelectFastestResolver) {
                            "Automatic (${activeResolver.name} • ${activeResolver.primaryIp})"
                        } else {
                            "${activeResolver.name} (${activeResolver.primaryIp})"
                        },
                        accent = IceCyan,
                        onClick = { showDnsPickerDialog = true },
                        testTag = "settings_dns_provider_row"
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Automatic Fastest DNS",
                        subtitle = "Select top reliable resolver from Ping Forge benchmarks",
                        checked = settings.autoSelectFastestResolver,
                        onCheckedChange = onToggleAutoSelect,
                        testTag = "settings_auto_dns_switch"
                    )
                    SettingsDivider()
                    SettingsClickRow(
                        title = "Custom DNS Server",
                        subtitle = settings.customDnsPrimary.ifBlank { "Not configured" },
                        accent = ElectricViolet,
                        onClick = { showCustomDnsDialog = true },
                        testTag = "settings_custom_dns_row"
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "DNS-over-HTTPS (DoH)",
                        subtitle = "Encrypt upstream DNS queries when supported by resolver",
                        checked = settings.preferDoH,
                        onCheckedChange = onTogglePreferDoH,
                        testTag = "settings_doh_switch"
                    )
                }
            }

            // 2. Gaming DNS & Cache Preferences
            item {
                SectionLabel("PING FORGE & DNS CACHE")
                GlassCard(contentPadding = 16.dp) {
                    SettingsSwitchRow(
                        title = "Re-benchmark on Network Change",
                        subtitle = "Automatically re-test resolvers when switching Wi-Fi & Mobile Data",
                        checked = settings.rebenchmarkOnNetworkChange,
                        onCheckedChange = onToggleRebenchmarkOnNetwork,
                        testTag = "settings_rebenchmark_switch"
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Enable Local DNS Cache",
                        subtitle = "Instant sub-millisecond answers for recently resolved domains",
                        checked = settings.enableDnsCache,
                        onCheckedChange = onToggleDnsCache,
                        testTag = "settings_dns_cache_switch"
                    )
                    if (settings.enableDnsCache) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Max Cache TTL: ${settings.cacheTtlSeconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = IceCyan
                        )
                        Slider(
                            value = settings.cacheTtlSeconds.toFloat(),
                            onValueChange = { onChangeCacheTtl(it.roundToInt()) },
                            valueRange = 60f..1800f,
                            colors = SliderDefaults.colors(
                                thumbColor = IceCyan,
                                activeTrackColor = IceCyan
                            )
                        )
                    }
                }
            }

            // 3. Protection Categories & Rule Lists
            item {
                SectionLabel("PROTECTION & RULES")
                GlassCard(contentPadding = 16.dp) {
                    ProtectionCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) SettingsDivider()
                        SettingsSwitchRow(
                            title = category.displayName,
                            subtitle = "${blocklistStatus.categoryCounts[category] ?: 0} domains indexed",
                            checked = settings.enabledCategories.contains(category),
                            onCheckedChange = { onToggleCategory(category, it) },
                            testTag = "settings_category_${category.id}"
                        )
                    }
                    SettingsDivider()
                    SettingsClickRow(
                        title = "Allowlist Manager",
                        subtitle = "${settings.allowlistDomains.size} domains allowed with top precedence",
                        accent = Emerald,
                        onClick = { showAllowlistDialog = true },
                        testTag = "settings_allowlist_row"
                    )
                    SettingsDivider()
                    SettingsClickRow(
                        title = "Custom Blocklist",
                        subtitle = "${settings.customBlocklistDomains.size} user-defined blocked domains",
                        accent = WarmAmber,
                        onClick = { showCustomBlockDialog = true },
                        testTag = "settings_custom_blocklist_row"
                    )
                }
            }

            // 4. Blocklist Update Preferences
            item {
                SectionLabel("BLOCKLIST UPDATES")
                GlassCard(contentPadding = 16.dp) {
                    SettingsSwitchRow(
                        title = "Auto-Update Blocklists",
                        subtitle = "Refresh threat & ad feeds when network is available",
                        checked = settings.autoUpdateBlocklists,
                        onCheckedChange = onToggleAutoUpdateBlocklists,
                        testTag = "settings_auto_update_switch"
                    )
                    SettingsDivider()
                    Text(
                        text = "Update Interval",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(12, 24, 72).forEach { hours ->
                            val selected = settings.updateIntervalHours == hours
                            Surface(
                                onClick = { onChangeUpdateIntervalHours(hours) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) ElectricViolet.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) ElectricViolet else Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${hours}h",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) Color.White else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onUpdateBlocklistsNow,
                        enabled = !blocklistStatus.isUpdating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (blocklistStatus.isUpdating) "Updating Blocklists..." else "Update Blocklists Now")
                    }
                }
            }

            // 5. Theme & App Behavior
            item {
                SectionLabel("THEME & BEHAVIOR")
                GlassCard(contentPadding = 16.dp) {
                    Text(
                        text = "Visual Theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppThemeStyle.entries.forEach { style ->
                        val selected = settings.themeStyle == style
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onChangeThemeStyle(style) }
                                .padding(vertical = 6.dp)
                                .testTag("theme_option_${style.name.lowercase()}")
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onChangeThemeStyle(style) },
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricViolet)
                            )
                            Column {
                                Text(style.displayName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(style.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Record Live Query Logs",
                        subtitle = "Store recent DNS decisions locally for Protection dashboard",
                        checked = settings.showQueryLogs,
                        onCheckedChange = onToggleShowQueryLogs,
                        testTag = "settings_query_logs_switch"
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Reduce Motion",
                        subtitle = "Minimizes ambient shield pulse and intro animations",
                        checked = settings.reduceMotion,
                        onCheckedChange = onToggleReduceMotion,
                        testTag = "settings_reduce_motion_switch"
                    )
                    SettingsDivider()
                    SettingsClickRow(
                        title = "Replay Cinematic Intro",
                        subtitle = "Preview the opening shield reveal animation",
                        accent = IceCyan,
                        onClick = onReplayIntro,
                        testTag = "settings_replay_intro_row"
                    )
                }
            }

            // 6. Real Statistics & Reset Configuration
            item {
                SectionLabel("STATISTICS & MAINTENANCE")
                GlassCard(contentPadding = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Queries", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("${counters.totalQueries}", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Blocked Domains", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("${counters.blockedQueries}", style = MaterialTheme.typography.labelLarge, color = WarmAmber)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cache Hits", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("${counters.cachedQueries}", style = MaterialTheme.typography.labelLarge, color = Emerald)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Failed Lookups", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("${counters.failedQueries}", style = MaterialTheme.typography.labelLarge, color = AlertCrimson)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearStatsAndLogs,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clear_stats_button")
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Logs")
                        }
                        Button(
                            onClick = { showResetConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlertCrimson.copy(alpha = 0.22f),
                                contentColor = AlertCrimson
                            ),
                            border = BorderStroke(1.dp, AlertCrimson.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_settings_button")
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset All")
                        }
                    }
                }
            }

            // 7. About Application
            item {
                SectionLabel("ABOUT APPLICATION")
                GlassCard(contentPadding = 16.dp) {
                    Text(
                        text = stringResource(R.string.brand_full_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = IceCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Uses Android's VpnService in Split-DNS mode (10.111.222.1/32) to filter and optimize DNS lookups device-wide without root. Normal TCP/UDP traffic bypasses the tunnel directly. Only one VPN service can run simultaneously on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // 8. CREATOR PROFILE (Very bottom of Settings screen ONLY)
            item {
                SectionLabel("CREATOR PROFILE")
                CreatorProfileCard(
                    onOpenInstagram = {
                        openInstagramProfile(
                            context = context,
                            url = context.getString(R.string.creator_instagram_url)
                        )
                    }
                )
            }
        }
    }

    if (showDnsPickerDialog) {
        AlertDialog(
            onDismissRequest = { showDnsPickerDialog = false },
            title = { Text("Select DNS Provider") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSelectResolver(BuiltInResolvers.AUTOMATIC_RESOLVER_ID)
                                showDnsPickerDialog = false
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = settings.autoSelectFastestResolver,
                            onClick = {
                                onSelectResolver(BuiltInResolvers.AUTOMATIC_RESOLVER_ID)
                                showDnsPickerDialog = false
                            }
                        )
                        Column {
                            Text("Automatic (Ping Forge Fastest)", style = MaterialTheme.typography.bodyMedium)
                            Text("Uses top benchmarked reliable DNS", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                    BuiltInResolvers.defaultResolvers.forEach { resolver ->
                        val selected = !settings.autoSelectFastestResolver && settings.selectedResolverId == resolver.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelectResolver(resolver.id)
                                    showDnsPickerDialog = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onSelectResolver(resolver.id)
                                    showDnsPickerDialog = false
                                }
                            )
                            Column {
                                Text(resolver.name, style = MaterialTheme.typography.bodyMedium)
                                Text(resolver.providerTag, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDnsPickerDialog = false }) { Text("Close") }
            }
        )
    }

    if (showCustomDnsDialog) {
        CustomDnsDialog(
            initialPrimary = settings.customDnsPrimary,
            initialSecondary = settings.customDnsSecondary,
            initialDoh = settings.customDnsDohUrl,
            onSave = { p, s, d ->
                if (onSaveCustomDns(p, s, d)) showCustomDnsDialog = false
            },
            onDismiss = { showCustomDnsDialog = false }
        )
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
            subtitle = "Block specific domains & subdomains whenever Protection or Balanced Mode is active.",
            domains = settings.customBlocklistDomains.sorted(),
            accentColor = WarmAmber,
            onAdd = onAddCustomBlock,
            onRemove = onRemoveCustomBlock,
            onDismiss = { showCustomBlockDialog = false }
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset All Settings?") },
            text = {
                Text(
                    "This restores default operating mode, protection categories, DNS resolver, allowlist, custom blocklist, and clears recorded logs and statistics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetConfiguration()
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertCrimson),
                    modifier = Modifier.testTag("confirm_reset_button")
                ) {
                    Text("Reset Configuration")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Creator Profile placed exclusively at the bottom of the Settings screen.
 *
 * PHOTO INTEGRATION SLOT:
 * Currently uses R.drawable.creator_avatar_placeholder (neutral geometric placeholder).
 * Once the user uploads their personal photo, save it to `app/src/main/res/drawable/creator_photo.jpg`
 * (or .png) and replace `R.drawable.creator_avatar_placeholder` below with `R.drawable.creator_photo`.
 */
@Composable
private fun CreatorProfileCard(
    onOpenInstagram: () -> Unit
) {
    GlassCard(
        accentBorder = ElectricViolet.copy(alpha = 0.45f),
        contentPadding = 16.dp,
        onClick = onOpenInstagram,
        modifier = Modifier.testTag("creator_profile_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(listOf(ElectricViolet, IceCyan)),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.creator_avatar_placeholder),
                    contentDescription = "Creator profile photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.creator_role_placeholder),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_instagram),
                        contentDescription = "Instagram",
                        tint = IceCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.creator_instagram_handle),
                        style = MaterialTheme.typography.labelLarge,
                        color = IceCyan
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap to open Instagram profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "Open Instagram Profile",
                tint = ElectricViolet
            )
        }
    }
}

fun openInstagramProfile(context: Context, url: String) {
    val uri = Uri.parse(url)
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.instagram.android")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(browserIntent) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF070910),
                checkedTrackColor = ElectricViolet
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SettingsClickRow(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text("Edit", style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

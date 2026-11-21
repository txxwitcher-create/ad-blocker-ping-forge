package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppMode
import com.example.domain.model.AppThemeStyle
import com.example.domain.model.ProtectionCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name and bundled blocklists load across all categories`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Ping Forge", context.getString(R.string.app_name))

        val app = context as PingForgeApplication
        app.container.blocklistRepository.loadAllListsIntoEngine()
        val counts = app.container.filterEngine.getCategoryCounts()
        ProtectionCategory.entries.forEach { cat ->
            assertTrue("Expected non-empty bundled blocklist for $cat", (counts[cat] ?: 0) > 50)
        }
        assertTrue(app.container.filterEngine.getTotalLoadedDomains() > 500)
    }

    @Test
    fun `settings repository persists updates and resets cleanly to defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)

        repo.setMode(AppMode.PROTECTION)
        repo.setCategoryEnabled(ProtectionCategory.GAMBLING, true)
        repo.addAllowlistDomain("trusted.example.com")
        repo.addCustomBlockDomain("blocked.example.com")
        repo.setCustomDns("9.9.9.9", "149.112.112.112", "https://dns.quad9.net/dns-query", selectNow = true)
        repo.setThemeStyle(AppThemeStyle.AMOLED_BLACK)

        val saved = repo.getCurrentSettings()
        assertEquals(AppMode.PROTECTION, saved.selectedMode)
        assertTrue(saved.enabledCategories.contains(ProtectionCategory.GAMBLING))
        assertTrue(saved.allowlistDomains.contains("trusted.example.com"))
        assertTrue(saved.customBlocklistDomains.contains("blocked.example.com"))
        assertEquals("9.9.9.9", saved.customDnsPrimary)
        assertEquals(AppThemeStyle.AMOLED_BLACK, saved.themeStyle)

        repo.resetAllToDefaults()
        val reset = repo.getCurrentSettings()
        assertEquals(AppMode.BALANCED, reset.selectedMode)
        assertTrue(reset.allowlistDomains.isEmpty())
        assertTrue(reset.customBlocklistDomains.isEmpty())
        assertEquals("", reset.customDnsPrimary)
        assertEquals(AppThemeStyle.OBSIDIAN_GLASS, reset.themeStyle)
    }

    @Test
    fun `cuj navigation modes protection tester gaming and creator profile`() {
        // Wait for DataStore-backed intro state to resolve
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("skip_intro_button").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("vpn_connect_button").fetchSemanticsNodes().isNotEmpty()
        }

        // Dismiss intro splash if shown
        val skipNodes = composeRule.onAllNodesWithTag("skip_intro_button").fetchSemanticsNodes()
        if (skipNodes.isNotEmpty()) {
            composeRule.onNodeWithTag("skip_intro_button").performClick()
            composeRule.waitForIdle()
        }

        // Home screen visible with Connect button & status pill
        composeRule.onNodeWithTag("vpn_status_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("vpn_connect_button").performScrollTo().assertIsDisplayed()

        // Scroll Home LazyColumn to Mode selector and switch to Protection
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("mode_chip_protection"))
        composeRule.onNodeWithTag("mode_chip_protection").performClick()
        composeRule.waitForIdle()

        // Navigate to Protection screen
        composeRule.onNodeWithTag("nav_protection").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("update_blocklists_button").assertIsDisplayed()

        // Scroll Protection LazyColumn to Rule Verification Tester and verify real domain blocking
        composeRule.onNodeWithTag("protection_lazy_column").performScrollToNode(hasTestTag("rule_tester_input"))
        composeRule.onNodeWithTag("rule_tester_input").performTextInput("doubleclick.net")
        composeRule.onNodeWithTag("rule_tester_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rule_tester_result")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("BLOCKED (doubleclick.net)", substring = true)

        // Add doubleclick.net to Allowlist and verify allowlist precedence
        composeRule.onNodeWithTag("protection_lazy_column").performScrollToNode(hasTestTag("manage_allowlist_card"))
        composeRule.onNodeWithTag("manage_allowlist_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("domain_dialog_input").performTextInput("doubleclick.net")
        composeRule.onNodeWithTag("domain_dialog_add_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("protection_lazy_column").performScrollToNode(hasTestTag("rule_tester_button"))
        composeRule.onNodeWithTag("rule_tester_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rule_tester_result")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("ALLOWLISTED (doubleclick.net)", substring = true)

        // Navigate to Gaming (Ping Forge) screen
        composeRule.onNodeWithTag("nav_gaming").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ping Forge").assertIsDisplayed()
        composeRule.onNodeWithTag("gaming_lazy_column").performScrollToNode(hasTestTag("run_benchmark_button"))
        composeRule.onNodeWithTag("run_benchmark_button").assertIsDisplayed()

        // Navigate to Settings screen and verify Creator Profile with @unfiltered.java
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("creator_profile_card"))
        composeRule.onNodeWithTag("creator_profile_card").assertIsDisplayed()
        composeRule.onNodeWithText("@unfiltered.java").assertIsDisplayed()
    }
}

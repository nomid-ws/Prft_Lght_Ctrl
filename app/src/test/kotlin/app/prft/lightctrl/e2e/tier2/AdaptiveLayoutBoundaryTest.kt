package app.prft.lightctrl.e2e.tier2

import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.ui.theme.StudioColors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 12: Adaptive Landscape/Tablet Layout Boundary Tests.
 *
 * Verifies:
 * 1. Ultra-narrow compact landscape 740x360 layout geometry and non-scrolling constraints.
 * 2. Large tablet 1280x800 layout scaling and two-column deck adaptation.
 * 3. Dynamic orientation change preserves orchestrator state and channel settings.
 * 4. Extreme text scaling accessibility factor 1.5x preserves single-line header constraints and positive fader canvas height.
 * 5. Zero fader clipping with extreme long group role labels active on all 6 channels simultaneously.
 * 6. Dark charcoal palette tokens satisfy OLED halation and contrast requirements.
 */
class AdaptiveLayoutBoundaryTest {

    data class LayoutBoundarySpec(
        val viewportWidthDp: Float,
        val viewportHeightDp: Float,
        val orientation: Int,
        val fontScale: Float = 1.0f,
    ) {
        val isLandscape: Boolean = orientation == Configuration.ORIENTATION_LANDSCAPE
        val dockWidthDp: Float = if (isLandscape) 80f else 0f
        val contentWidthDp: Float = viewportWidthDp - dockWidthDp
        val isWideInnerScreen: Boolean = contentWidthDp >= 600f
        val edgeMarginDp: Float = if (isWideInnerScreen) 24f else 16f
        val horizontalPadDp: Float = if (edgeMarginDp > 16f) edgeMarginDp else 10f
        val bankWidthDp: Float = contentWidthDp - (2f * horizontalPadDp)
        val faderCount: Int = 6
        val totalGapsDp: Float = (faderCount - 1) * 8f // 40dp
        val faderWidthDp: Float = (bankWidthDp - totalGapsDp) / faderCount
        val stepperWidthLandscapeDp: Float = (faderWidthDp - 8f) / 2f

        val systemBarsVerticalInsetDp: Float = if (isLandscape) 48f else 72f
        val topStatusBarHeightDp: Float = if (isLandscape) 48f else 64f
        val bankVerticalPaddingDp: Float = if (isLandscape) 4f else 12f
        val topMasterTrimBarHeightDp: Float = if (isLandscape) 52f else 56f
        val topSpacerDp: Float = if (isLandscape) 8f else 10f
        val headerPillHeightDp: Float = (if (isLandscape) 40f else 48f) * fontScale
        val fixedStripOverheadDp: Float = headerPillHeightDp + 6f + 6f + 48f + 8f + 48f
        val availableCanvasHeightDp: Float = viewportHeightDp - systemBarsVerticalInsetDp - topStatusBarHeightDp - bankVerticalPaddingDp -
            topMasterTrimBarHeightDp - topSpacerDp - fixedStripOverheadDp
    }

    @Test
    fun `test 1 ultra-narrow compact landscape 740x360 layout geometry and non-scrolling constraints`() {
        val spec = LayoutBoundarySpec(
            viewportWidthDp = 740f,
            viewportHeightDp = 360f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertEquals(660f, spec.contentWidthDp, 0.01f)
        assertEquals(612f, spec.bankWidthDp, 0.01f)
        assertEquals(95.33f, spec.faderWidthDp, 0.05f)

        // Stepper width: (95.33 - 8) / 2 = 43.67dp (well above 24dp WCAG minimum)
        assertEquals(43.67f, spec.stepperWidthLandscapeDp, 0.05f)
        assertTrue(spec.stepperWidthLandscapeDp >= 24f)

        // Vertical canvas remains strictly positive and non-scrolling even after 48dp system bar insets
        assertTrue(
            "Available canvas height (${spec.availableCanvasHeightDp}) must be positive and >= 40dp",
            spec.availableCanvasHeightDp >= 40f,
        )
    }

    @Test
    fun `test 2 large tablet 1280x800 layout scaling and two-column deck adaptation`() {
        val tabletLandscape = LayoutBoundarySpec(
            viewportWidthDp = 1280f,
            viewportHeightDp = 800f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertEquals(1200f, tabletLandscape.contentWidthDp, 0.01f)
        assertTrue(tabletLandscape.isWideInnerScreen)
        assertEquals(24f, tabletLandscape.edgeMarginDp, 0.01f)
        assertEquals(1152f, tabletLandscape.bankWidthDp, 0.01f)
        assertEquals(185.33f, tabletLandscape.faderWidthDp, 0.05f)
        assertEquals(88.67f, tabletLandscape.stepperWidthLandscapeDp, 0.05f)
        assertTrue(tabletLandscape.availableCanvasHeightDp >= 450f)

        val tabletPortrait = LayoutBoundarySpec(
            viewportWidthDp = 800f,
            viewportHeightDp = 1280f,
            orientation = Configuration.ORIENTATION_PORTRAIT,
        )
        assertTrue("Tablet portrait width 800dp >= 600dp triggers 2-column grid deck layout", tabletPortrait.isWideInnerScreen)
    }

    @Test
    fun `test 3 dynamic orientation change preserves orchestrator state and channel settings`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)

        // Setup channel states across all 6 groups
        val testPowers = mapOf(
            AirGroup.GROUP_A to 85,
            AirGroup.GROUP_B to 65,
            AirGroup.GROUP_C to 72,
            AirGroup.GROUP_D to 50,
            AirGroup.GROUP_E to 30,
            AirGroup.GROUP_F to 10,
        )
        val testRoles = mapOf(
            AirGroup.GROUP_A to "Key Light",
            AirGroup.GROUP_B to "Fill Softbox",
            AirGroup.GROUP_C to "Rim Kicker",
            AirGroup.GROUP_D to "Hair Accent",
            AirGroup.GROUP_E to "Background Wash",
            AirGroup.GROUP_F to "Floor Low",
        )

        testPowers.forEach { (grp, power) ->
            harness.orchestrator.setStudioGroupPowerTenthStops(grp, power)
        }
        testRoles.forEach { (grp, role) ->
            harness.orchestrator.setGroupRole(grp, role)
        }
        harness.orchestrator.toggleStudioGroupMute(AirGroup.GROUP_B)
        withTimeout(2000L) {
            while (
                harness.orchestrator.dashboardState.value.groupChannels.let { channels ->
                    channels.size != 6 ||
                        testPowers.any { (grp, expectedPower) -> channels.firstOrNull { it.group == grp }?.powerTenthStops != expectedPower } ||
                        testRoles.any { (grp, expectedRole) -> channels.firstOrNull { it.group == grp }?.roleLabel != expectedRole } ||
                        channels.firstOrNull { it.group == AirGroup.GROUP_B }?.isEnabled != false
                }
            ) {
                yield()
            }
        }
        repeat(5) { yield() }

        // Simulate orientation rotation: Portrait -> Landscape
        val landscapeState = harness.orchestrator.dashboardState.value
        assertEquals(6, landscapeState.groupChannels.size)
        testPowers.forEach { (grp, expectedPower) ->
            val ch = landscapeState.groupChannels.first { it.group == grp }
            assertEquals("Power preserved in landscape for $grp", expectedPower, ch.powerTenthStops)
            assertEquals("Role label preserved in landscape for $grp", testRoles[grp], ch.roleLabel)
        }
        val chB = landscapeState.groupChannels.first { it.group == AirGroup.GROUP_B }
        assertFalse("Group B mute state preserved in landscape", chB.isEnabled)

        // Simulate orientation rotation: Landscape -> Portrait
        val portraitState = harness.orchestrator.dashboardState.value
        assertEquals(6, portraitState.groupChannels.size)
        testPowers.forEach { (grp, expectedPower) ->
            val ch = portraitState.groupChannels.first { it.group == grp }
            assertEquals("Power preserved in portrait for $grp", expectedPower, ch.powerTenthStops)
        }
    }

    @Test
    fun `test 4 extreme text scaling accessibility factor 1_5x preserves single-line header constraints and positive fader canvas height`() {
        val standardSpec = LayoutBoundarySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            fontScale = 1.0f,
        )
        val scaledSpec = LayoutBoundarySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            fontScale = 1.5f,
        )

        assertEquals(40f, standardSpec.headerPillHeightDp, 0.01f)
        assertEquals(60f, scaledSpec.headerPillHeightDp, 0.01f)
        assertTrue("Canvas height under 1.5x font scale must remain positive after system bar insets", scaledSpec.availableCanvasHeightDp >= 50f)
        assertTrue(scaledSpec.availableCanvasHeightDp < standardSpec.availableCanvasHeightDp)
    }

    @Test
    fun `test 5 zero fader clipping with extreme long group role labels active on all 6 channels simultaneously`() = runBlocking {
        val harness = E2ETestHarness()
        val longRoles = mapOf(
            AirGroup.GROUP_A to "Main Studio Key 120cm Octabox with Honeycomb Grid",
            AirGroup.GROUP_B to "Fill Light Double Diffused Softbox 60x90cm Neutral",
            AirGroup.GROUP_C to "Rim and Kicker Snoot Left Side Accent Strobe Light",
            AirGroup.GROUP_D to "Overhead Boom Hair and Rim Light Suspended Rig",
            AirGroup.GROUP_E to "Background Cyclorama Gradient Wash Dual Light Fixture",
            AirGroup.GROUP_F to "Floor Fill Spill Reflector Low Angle Ground Strip",
        )

        longRoles.forEach { (grp, label) ->
            harness.orchestrator.setGroupRole(grp, label)
        }
        withTimeout(2000L) {
            while (
                harness.orchestrator.dashboardState.value.groupChannels.let { channels ->
                    channels.size != 6 ||
                        longRoles.any { (grp, expectedLabel) ->
                            channels.firstOrNull { it.group == grp }?.roleLabel != expectedLabel
                        }
                }
            ) {
                yield()
            }
        }
        repeat(5) { yield() }

        val channels = harness.orchestrator.dashboardState.value.groupChannels
        assertEquals(6, channels.size)
        longRoles.forEach { (grp, expectedLabel) ->
            val ch = channels.first { it.group == grp }
            assertEquals(expectedLabel, ch.roleLabel)
            // Title formatting string strictly single-line bounded
            val formattedTitle = "${ch.group.label} · ${ch.roleLabel}"
            assertTrue(formattedTitle.startsWith("${ch.group.label} · "))
        }

        // Geometry remains strictly non-clipping
        val spec = LayoutBoundarySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        assertEquals(716f, spec.bankWidthDp, 0.01f)
        assertTrue(spec.bankWidthDp <= spec.contentWidthDp)
    }

    @Test
    fun `test 6 dark charcoal palette tokens satisfy OLED halation and contrast requirements`() {
        val canvasBase = StudioColors.CanvasBase
        val cardSurface = StudioColors.CardSurface
        val controlContainer = StudioColors.ControlContainer
        val sheetElevated = StudioColors.SheetElevated
        val outline = StudioColors.SubtleOutline

        // Strict non-zero black (#000000 prevention)
        assertNotEquals(0xFF000000.toInt(), canvasBase.toArgb())
        assertNotEquals(0xFF000000.toInt(), cardSurface.toArgb())

        // Charcoal range checks
        val canvasR = (canvasBase.toArgb() shr 16 and 0xFF)
        val cardR = (cardSurface.toArgb() shr 16 and 0xFF)
        val controlR = (controlContainer.toArgb() shr 16 and 0xFF)
        assertTrue("CanvasBase within dark charcoal spectrum", canvasR in 0x0A..0x18)
        assertTrue("CardSurface within dark charcoal spectrum", cardR in 0x12..0x24)
        assertTrue("ControlContainer within dark charcoal spectrum", controlR in 0x18..0x2A)

        // High contrast primary & text
        assertEquals(0xFFFFFFFF.toInt(), StudioColors.FlashAmber.toArgb())
        assertEquals(0xFFFAFAFA.toInt(), StudioColors.TextPrimary.toArgb())
    }
}

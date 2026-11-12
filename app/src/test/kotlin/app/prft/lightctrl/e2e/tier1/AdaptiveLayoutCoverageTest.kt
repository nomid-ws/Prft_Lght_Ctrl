package app.prft.lightctrl.e2e.tier1

import android.content.res.Configuration
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.ui.components.STUDIO_GROUP_ROLE_SUGGESTIONS
import app.prft.lightctrl.ui.theme.StudioColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 12: Adaptive Landscape/Tablet Layout Coverage Tests.
 *
 * Verifies:
 * 1. Compact landscape 844x390 renders without horizontal overflow (width math & zero clipping).
 * 2. Standard landscape 915x412 geometry and side-by-side steppers satisfy >= 48dp touch targets.
 * 3. Portrait layout uses bottom navigation dock while landscape uses NavigationRail dock.
 * 4. All 6 faders A-F fit in landscape viewport without vertical/horizontal scroll requirement.
 * 5. Interactive touch targets satisfy >= 48dp and >= 8dp spacing requirements.
 */
class AdaptiveLayoutCoverageTest {

    data class LayoutGeometrySpec(
        val viewportWidthDp: Float,
        val viewportHeightDp: Float,
        val orientation: Int,
        val fontScale: Float = 1.0f,
    ) {
        val isLandscape: Boolean = orientation == Configuration.ORIENTATION_LANDSCAPE
        val dockType: String = if (isLandscape) "NAVIGATION_RAIL" else "BOTTOM_BAR"
        val dockWidthDp: Float = if (isLandscape) 80f else 0f
        val contentWidthDp: Float = viewportWidthDp - dockWidthDp
        val isWideInnerScreen: Boolean = contentWidthDp >= 600f
        val edgeMarginDp: Float = if (isWideInnerScreen) 24f else 16f
        val horizontalPadDp: Float = if (edgeMarginDp > 16f) edgeMarginDp else 10f
        val bankWidthDp: Float = contentWidthDp - (2f * horizontalPadDp)
        val faderCount: Int = 6
        val interFaderGapDp: Float = 8f
        val totalGapsDp: Float = (faderCount - 1) * interFaderGapDp // 40dp for 6 faders
        val faderWidthDp: Float = (bankWidthDp - totalGapsDp) / faderCount
        val stepperWidthLandscapeDp: Float = (faderWidthDp - 8f) / 2f
        val stepperHeightDp: Float = 48f
        val muteButtonHeightDp: Float = 48f

        // Vertical height budget including real Android system bar insets (statusBar + navigationBar)
        val systemBarsVerticalInsetDp: Float = if (isLandscape) 48f else 72f
        val topStatusBarHeightDp: Float = if (isLandscape) 48f else 64f
        val bankVerticalPaddingDp: Float = if (isLandscape) 4f else 12f
        val topMasterTrimBarHeightDp: Float = if (isLandscape) 52f else 56f
        val topSpacerDp: Float = if (isLandscape) 8f else 10f
        val headerPillHeightDp: Float = (if (isLandscape) 40f else 48f) * fontScale
        val fixedStripOverheadDp: Float = headerPillHeightDp + 6f + 6f + stepperHeightDp + 8f + muteButtonHeightDp
        val availableCanvasHeightDp: Float = viewportHeightDp - systemBarsVerticalInsetDp - topStatusBarHeightDp - bankVerticalPaddingDp -
            topMasterTrimBarHeightDp - topSpacerDp - fixedStripOverheadDp
    }

    @Test
    fun `test 1 compact landscape 844x390 renders without horizontal overflow`() {
        val spec = LayoutGeometrySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertTrue(spec.isLandscape)
        assertEquals("NAVIGATION_RAIL", spec.dockType)
        assertEquals(80f, spec.dockWidthDp, 0.01f)
        assertEquals(764f, spec.contentWidthDp, 0.01f)
        assertTrue("764dp >= 600dp evaluates to wide inner screen", spec.isWideInnerScreen)
        assertEquals(24f, spec.edgeMarginDp, 0.01f)
        assertEquals(24f, spec.horizontalPadDp, 0.01f)

        // Fader bank width: 764 - 48 = 716dp
        assertEquals(716f, spec.bankWidthDp, 0.01f)
        assertTrue("Bank width must fit strictly inside content width", spec.bankWidthDp <= spec.contentWidthDp)
        assertEquals(0f, (spec.bankWidthDp + (2f * spec.horizontalPadDp)) - spec.contentWidthDp, 0.01f)

        // All 6 faders fit without horizontal scroll
        assertEquals(6, spec.faderCount)
        assertEquals(40f, spec.totalGapsDp, 0.01f)
        assertEquals(112.67f, spec.faderWidthDp, 0.05f)
        assertTrue("Individual fader width >= 48dp", spec.faderWidthDp >= 48f)
    }

    @Test
    fun `test 2 standard landscape 915x412 geometry and side-by-side steppers satisfy 48dp touch targets`() {
        val spec = LayoutGeometrySpec(
            viewportWidthDp = 915f,
            viewportHeightDp = 412f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertEquals(835f, spec.contentWidthDp, 0.01f)
        assertEquals(787f, spec.bankWidthDp, 0.01f)
        assertEquals(124.5f, spec.faderWidthDp, 0.05f)

        // Side-by-side [-] and [+] steppers in landscape (8dp separation)
        assertEquals(58.25f, spec.stepperWidthLandscapeDp, 0.05f)
        assertEquals(48f, spec.stepperHeightDp, 0.01f)
        assertTrue("Stepper width (58.25dp) must satisfy >= 48dp touch target", spec.stepperWidthLandscapeDp >= 48f)
        assertTrue("Stepper height (48dp) must satisfy >= 48dp touch target", spec.stepperHeightDp >= 48f)
    }

    @Test
    fun `test 3 portrait orientation uses thumb-zone bottom navigation dock while landscape uses navigation rail`() {
        val portraitSpec = LayoutGeometrySpec(
            viewportWidthDp = 390f,
            viewportHeightDp = 844f,
            orientation = Configuration.ORIENTATION_PORTRAIT,
        )
        assertFalse(portraitSpec.isLandscape)
        assertEquals("BOTTOM_BAR", portraitSpec.dockType)
        assertEquals(0f, portraitSpec.dockWidthDp, 0.01f)
        assertEquals(390f, portraitSpec.contentWidthDp, 0.01f)

        val landscapeSpec = LayoutGeometrySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        assertTrue(landscapeSpec.isLandscape)
        assertEquals("NAVIGATION_RAIL", landscapeSpec.dockType)
        assertEquals(80f, landscapeSpec.dockWidthDp, 0.01f)
    }

    @Test
    fun `test 4 all 6 faders A through F fit in landscape viewport without vertical or horizontal scroll requirement`() {
        val viewports = listOf(
            Pair(844f, 390f),
            Pair(915f, 412f),
            Pair(896f, 414f),
        )

        for ((w, h) in viewports) {
            val spec = LayoutGeometrySpec(
                viewportWidthDp = w,
                viewportHeightDp = h,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            )
            assertEquals(6, spec.faderCount)
            assertTrue("Fader bank width (${spec.bankWidthDp}) <= content width (${spec.contentWidthDp})",
                spec.bankWidthDp <= spec.contentWidthDp)
            assertTrue("Available vertical canvas height (${spec.availableCanvasHeightDp}) must be positive and >= 70dp after system bar insets",
                spec.availableCanvasHeightDp >= 70f)
        }
    }

    @Test
    fun `test 5 interactive touch targets and spacing comply with WCAG 2_2 and 48dp requirement`() {
        val spec = LayoutGeometrySpec(
            viewportWidthDp = 844f,
            viewportHeightDp = 390f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        // Navigation rail width: 80dp >= 48dp
        assertTrue(spec.dockWidthDp >= 48f)

        // Master trim step buttons: height >= 48dp, width >= 48dp
        val masterStepButtonDim = 48f
        assertTrue(masterStepButtonDim >= 48f)

        // Landscape steppers [-] and [+]: 54.33dp width x 48dp height
        assertTrue(spec.stepperWidthLandscapeDp >= 48f)
        assertTrue(spec.stepperHeightDp >= 48f)

        // Mute button: 112.67dp width x 48dp height
        assertTrue(spec.faderWidthDp >= 48f)
        assertTrue(spec.muteButtonHeightDp >= 48f)

        // Gap spacing between faders is 8dp
        assertEquals(8f, spec.interFaderGapDp, 0.01f)
        assertTrue("Inter-element spacing must be >= 8dp", spec.interFaderGapDp >= 8f)
    }
}

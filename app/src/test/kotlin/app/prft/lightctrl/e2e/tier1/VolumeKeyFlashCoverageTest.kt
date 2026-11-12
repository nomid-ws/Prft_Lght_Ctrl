package app.prft.lightctrl.e2e.tier1

import android.view.KeyEvent
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.ui.VolumeKeyEventHandler
import app.prft.lightctrl.ui.dashboard.StudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 11: Hardware Volume-Key Test Flash Coverage Tests.
 *
 * Verifies:
 * 1. Preference toggle defaults to false, toggles true/false, and propagates to dashboardState.
 * 2. KEYCODE_VOLUME_DOWN onKeyDown event triggers master test flash when enabled.
 * 3. KEYCODE_VOLUME_UP onKeyDown event triggers master test flash when enabled.
 * 4. KEYCODE_VOLUME_DOWN and KEYCODE_VOLUME_UP pass through without firing when toggle disabled.
 * 5. Master test flash dispatches PUP Opcode 45 (TEST_FLASH) to all connected ready strobes.
 */
class VolumeKeyFlashCoverageTest {

    @Test
    fun `test 1 Preference toggle defaults to false and propagates updates to dashboard state`() = runBlocking {
        val harness = E2ETestHarness()

        // 1. Initial state must be disabled by default
        assertFalse("Default preference must be false", harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled)

        // 2. Enable toggle
        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(20)
            }
        }
        assertTrue("Dashboard state must reflect enabled toggle", harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled)

        // 3. Disable toggle
        harness.orchestrator.setVolumeKeyTestFlashEnabled(false)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(20)
            }
        }
        assertFalse("Dashboard state must reflect disabled toggle", harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled)
    }

    @Test
    fun `test 2 Volume Down key down event triggers master test flash when toggle enabled`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull("Recording link must be tracked", link)
        link!!.clear()

        // Enable volume key test flash
        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(20)
            }
        }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // Simulate Volume Down press
        val consumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        assertTrue("Volume down key must be consumed when enabled", consumed)

        // Await Opcode 45 frame on wire
        withTimeout(2000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }

        val flashFrames = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertEquals("Exactly one TEST_FLASH frame must be transmitted", 1, flashFrames.size)
        assertFalse("Flash frame must be command, not query", flashFrames[0].first.isQuery)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 3 Volume Up key down event triggers master test flash when toggle enabled`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull("Recording link must be tracked", link)
        link!!.clear()

        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(20)
            }
        }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // Simulate Volume Up press
        val consumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0)
        assertTrue("Volume up key must be consumed when enabled", consumed)

        withTimeout(2000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }

        val flashFrames = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertEquals("Exactly one TEST_FLASH frame must be transmitted", 1, flashFrames.size)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 Volume keys pass through without firing when toggle disabled`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)!!
        link.clear()

        // Explicitly disabled
        harness.orchestrator.setVolumeKeyTestFlashEnabled(false)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                yield()
            }
        }
        repeat(5) { yield() }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // Both volume keys must return false (not consumed)
        val downConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        val upConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0)
        assertFalse("Volume Down must pass through when disabled", downConsumed)
        assertFalse("Volume Up must pass through when disabled", upConsumed)

        // Instant settling loop ensuring zero flash frames dispatched when disabled
        repeat(5) {
            yield()
            assertEquals(
                "Zero TEST_FLASH frames must be transmitted when disabled",
                0,
                link.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH },
            )
        }
        val flashFrames = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertEquals("Zero TEST_FLASH frames must be transmitted when disabled", 0, flashFrames.size)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 Volume key test flash dispatches PUP TestFlash frame to all ready fixtures`() = runBlocking {
        val harness = E2ETestHarness()
        val addr1 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addr2 = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addr1)
        harness.connectByAddress(addr2)
        harness.awaitActiveReadyCount(2)

        val link1 = harness.getRecordingLink(addr1)!!
        val link2 = harness.getRecordingLink(addr2)!!
        link1.clear()
        link2.clear()

        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(20)
            }
        }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        val consumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        assertTrue(consumed)

        withTimeout(2000L) {
            while (link1.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH } ||
                link2.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }

        assertEquals(1, link1.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        assertEquals(1, link2.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })

        harness.disconnectDevice(addr1)
        harness.disconnectDevice(addr2)
    }
}

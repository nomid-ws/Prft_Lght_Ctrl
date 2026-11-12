package app.prft.lightctrl.e2e.tier2

import android.view.KeyEvent
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.FakeBleGateway
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.ui.VolumeKeyEventHandler
import app.prft.lightctrl.ui.dashboard.StudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 11: Hardware Volume-Key Test Flash Boundary & Corner Case Tests.
 *
 * Verifies:
 * 1. Non-volume hardware keys are completely ignored and passed through to system.
 * 2. Disconnected studio handles key event gracefully without exception.
 * 3. Long-press repeat events (repeatCount > 0) are consumed without triggering extra flashes.
 * 4. Key-up events are consumed when enabled without firing extra flashes; passed through when disabled.
 * 5. Smart remote-covered monolight flash suppression vs standalone strobe routing.
 * 6. Rapid successive key presses pace flash dispatcher without queue overflow or crashing.
 */
class VolumeKeyFlashBoundaryTest {

    @Test
    fun `test 1 Non-volume hardware keys are ignored and passed through`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)
        val link = harness.getRecordingLink(addr)!!
        link.clear()

        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(10)
            }
        }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { true },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        val nonVolumeKeys = listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )

        for (keyCode in nonVolumeKeys) {
            val downConsumed = handler.onKeyDown(keyCode, repeatCount = 0)
            val upConsumed = handler.onKeyUp(keyCode)
            assertFalse("Key code $keyCode must NOT be consumed by volume flash handler", downConsumed)
            assertFalse("Key code $keyCode keyUp must NOT be consumed", upConsumed)
        }

        // Instant settling loop ensuring zero flash frames dispatched by ignored keys
        repeat(5) {
            yield()
            assertEquals(0, link.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        }

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 2 Disconnected studio handles volume key event gracefully without exception`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)

        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled) {
                delay(10)
            }
        }

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { true },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // Consumed cleanly even with 0 devices connected, no crash or hanging jobs
        val downConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        val upConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0)

        assertTrue("Volume down should be consumed in eyes-free mode even if empty studio", downConsumed)
        assertTrue("Volume up should be consumed in eyes-free mode even if empty studio", upConsumed)
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)
    }

    @Test
    fun `test 3 Hardware key repeat suppression prevents test flash flood on long press`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)
        val link = harness.getRecordingLink(addr)!!
        link.clear()

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { true },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // repeatCount = 0: initial press -> fires flash
        val initialConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        assertTrue("Initial press must be consumed", initialConsumed)

        // repeatCount = 1, 2, 3, 4: holding key down -> must consume but NOT fire flash
        for (repeat in 1..4) {
            val repeatConsumed = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = repeat)
            assertTrue("Repeat event $repeat must still be consumed to prevent OS volume HUD", repeatConsumed)
        }

        withTimeout(2000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }
        // Instant settling loop to confirm repeat presses are suppressed and frame count settles at 1
        repeat(5) {
            yield()
            assertEquals(
                "Only initial press must trigger flash; repeats must be suppressed",
                1,
                link.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH },
            )
        }

        val flashFrames = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertEquals("Only initial press must trigger flash; repeats must be suppressed", 1, flashFrames.size)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 Key up event consumes key release without firing duplicate test flash`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)
        val link = harness.getRecordingLink(addr)!!
        link.clear()

        var isEnabled = true
        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { isEnabled },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // 1. Initial down press
        handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)

        // 2. Key up when enabled
        val upConsumed = handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertTrue("Key up must be consumed when enabled", upConsumed)

        withTimeout(2000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }
        // Instant settling loop to confirm key up does not trigger duplicate flash
        repeat(5) {
            yield()
            assertEquals(1, link.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        }

        // Total flash frames must remain exactly 1 (key-up did not fire second flash)
        assertEquals(1, link.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })

        // 3. Key up when disabled
        isEnabled = false
        val upPassThrough = handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertFalse("Key up must pass through when disabled", upPassThrough)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 Remote-covered monolight suppresses direct BLE flash frame to prevent double trigger`() = runBlocking {
        val harness = E2ETestHarness()
        val addrConnectPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10X = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addrConnectPro)
        harness.connectByAddress(addrB10X)
        harness.connectByAddress(addrA2)
        harness.awaitDeviceReady(addrConnectPro)
        harness.awaitDeviceReady(addrB10X)
        harness.awaitDeviceReady(addrA2)

        // Both Connect Pro and B10X are on Channel 1 (default)
        // Set A2 to Channel 2 to act as a standalone un-covered strobe
        harness.orchestrator.setAirChannel(addrA2, 2)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addrA2 }?.airChannel != 2) {
                delay(10)
            }
        }

        val linkConnectPro = harness.getRecordingLink(addrConnectPro)!!
        val linkB10X = harness.getRecordingLink(addrB10X)!!
        val linkA2 = harness.getRecordingLink(addrA2)!!
        linkConnectPro.clear()
        linkB10X.clear()
        linkA2.clear()

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { true },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)

        withTimeout(2000L) {
            while (linkConnectPro.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH } ||
                linkA2.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }
        // Instant settling loop to ensure remote suppression and dispatch frames settle
        repeat(5) {
            yield()
            assertEquals(0, linkB10X.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        }

        // Connect Pro remote receives TEST_FLASH frame
        assertEquals(1, linkConnectPro.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        // Monolight on same channel is covered by remote -> direct BLE packet suppressed!
        assertEquals(0, linkB10X.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })
        // Monolight on Channel 2 is NOT covered by remote -> receives direct BLE packet!
        assertEquals(1, linkA2.decodeRecordedFrames().count { it.first.opcode == PupOpcodes.TEST_FLASH })

        harness.disconnectDevice(addrConnectPro)
        harness.disconnectDevice(addrB10X)
        harness.disconnectDevice(addrA2)
    }

    @Test
    fun `test 6 Rapid successive key presses pace flash dispatcher without queue overflow`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)
        val link = harness.getRecordingLink(addr)!!
        link.clear()

        val viewModel = StudioViewModel(harness.orchestrator)
        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { true },
            onTriggerFlash = { viewModel.triggerMasterTestFlash() },
        )

        // Rapid distinct key clicks (separate key-downs)
        repeat(5) {
            handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
            yield()
        }

        withTimeout(3000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.TEST_FLASH }) {
                delay(20)
            }
        }
        // Instant settling loop ensuring conflated flash dispatcher settles
        repeat(5) {
            yield()
        }

        val flashFrames = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.TEST_FLASH }
        // Conflated channel guarantees >= 1 frame transmitted and zero crashes
        assertTrue("At least one TEST_FLASH frame must be transmitted", flashFrames.isNotEmpty())
        assertTrue("Sequence numbers must be valid non-negative values", flashFrames.all { it.first.counter >= 0 })

        harness.disconnectDevice(addr)
    }
}

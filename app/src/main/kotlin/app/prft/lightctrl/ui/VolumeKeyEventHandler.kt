package app.prft.lightctrl.ui

import android.view.KeyEvent

/**
 * Handles hardware volume key events for eyes-free test flash triggering.
 * Decoupled from ComponentActivity lifecycle to allow hermetic JVM unit testing.
 */
class VolumeKeyEventHandler(
    private val isEnabledProvider: () -> Boolean,
    private val onTriggerFlash: () -> Unit,
) {
    /**
     * Intercepts volume key down events. Returns true if consumed, false if passed to system.
     */
    fun onKeyDown(keyCode: Int, repeatCount: Int = 0): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (isEnabledProvider()) {
                if (repeatCount == 0) {
                    onTriggerFlash()
                }
                return true
            }
        }
        return false
    }

    /**
     * Intercepts volume key up events. Returns true if consumed, false if passed to system.
     */
    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (isEnabledProvider()) {
                return true
            }
        }
        return false
    }
}

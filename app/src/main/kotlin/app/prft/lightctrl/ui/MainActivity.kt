package app.prft.lightctrl.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prft.lightctrl.BuildConfig
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.ui.dashboard.StudioDashboardScreen
import app.prft.lightctrl.ui.dashboard.StudioViewModel
import app.prft.lightctrl.ui.theme.PrftStudioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesStore: StudioPreferencesStore

    private val viewModel: StudioViewModel by viewModels()

    private val volumeKeyEventHandler by lazy {
        VolumeKeyEventHandler(
            isEnabledProvider = { preferencesStore.isVolumeKeyTestFlashEnabled.value },
            onTriggerFlash = {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.triggerMasterTestFlash()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0D0D0F.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF1A1A1E.toInt()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            PrftStudioTheme {
                val dashboardState by viewModel.state.collectAsStateWithLifecycle()

                val lifecycleOwner = LocalLifecycleOwner.current
                var hasPermissions by remember { mutableStateOf(hasRequiredBlePermissions()) }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val nowGranted = hasRequiredBlePermissions()
                            hasPermissions = nowGranted
                            viewModel.refreshBluetooth()
                            if (nowGranted && dashboardState.connectedUnits.isEmpty()) {
                                viewModel.startScan()
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    hasPermissions = hasRequiredBlePermissions()
                    viewModel.refreshBluetooth()
                    if (hasPermissions) {
                        viewModel.startScan()
                    }
                }

                LaunchedEffect(hasPermissions, dashboardState.isBluetoothEnabled) {
                    if (!BuildConfig.DEBUG) {
                        viewModel.setBenchSimulator(false)
                    }
                    viewModel.refreshBluetooth()
                    if (hasPermissions && dashboardState.isBluetoothEnabled) {
                        viewModel.startScan()
                    }
                }

                StudioDashboardScreen(
                    state = dashboardState,
                    hasBlePermissions = hasPermissions,
                    allowSimulator = BuildConfig.DEBUG,
                    onRequestBlePermissions = {
                        permissionLauncher.launch(requiredPermissionsArray())
                    },
                    viewModel = viewModel,
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return super.onKeyDown(keyCode, event)
        }
        if (volumeKeyEventHandler.onKeyDown(keyCode, event?.repeatCount ?: 0)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return super.onKeyUp(keyCode, event)
        }
        if (volumeKeyEventHandler.onKeyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hasRequiredBlePermissions(): Boolean {
        val scanOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
        val connectOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        return scanOk && connectOk
    }

    private fun requiredPermissionsArray(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
    }
}

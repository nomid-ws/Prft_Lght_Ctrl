package app.prft.lightctrl.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prft.lightctrl.BuildConfig
import app.prft.lightctrl.ui.dashboard.StudioDashboardScreen
import app.prft.lightctrl.ui.dashboard.StudioViewModel
import app.prft.lightctrl.ui.theme.PrftStudioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0D0D0F.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF1A1A1E.toInt()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            PrftStudioTheme {
                val vm: StudioViewModel = hiltViewModel()
                val dashboardState by vm.state.collectAsStateWithLifecycle()

                var hasPermissions by remember { mutableStateOf(hasRequiredBlePermissions()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    hasPermissions = hasRequiredBlePermissions()
                    vm.refreshBluetooth()
                    if (hasPermissions) {
                        vm.startScan()
                    }
                }

                LaunchedEffect(hasPermissions) {
                    if (!BuildConfig.DEBUG) {
                        vm.setBenchSimulator(false)
                    }
                    vm.refreshBluetooth()
                    if (hasPermissions) {
                        vm.startScan()
                    }
                }

                StudioDashboardScreen(
                    state = dashboardState,
                    hasBlePermissions = hasPermissions,
                    allowSimulator = BuildConfig.DEBUG,
                    onRequestBlePermissions = {
                        permissionLauncher.launch(requiredPermissionsArray())
                    },
                    viewModel = vm,
                )
            }
        }
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

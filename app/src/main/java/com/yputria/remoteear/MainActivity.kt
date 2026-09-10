package com.yputria.remoteear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yputria.remoteear.theme.LocalPalette
import com.yputria.remoteear.theme.RemoteEarTheme
import com.yputria.remoteear.ui.AboutScreen
import com.yputria.remoteear.ui.DocumentScreen
import com.yputria.remoteear.ui.MenuItem
import com.yputria.remoteear.ui.MonitorRoute
import com.yputria.remoteear.ui.MonitorViewModel

/**
 * The only Activity, and deliberately thin.
 *
 * It owns three things the UI layer cannot: the permission dialogs, the window, and the resume-time
 * refresh. It does *not* own the audio pipeline - that lives in
 * [com.yputria.remoteear.monitor.MonitoringService], because microphone capture in the background
 * requires the service to be the owner (ADR-0005). Note also the absence of `FLAG_KEEP_SCREEN_ON`:
 * the service is what keeps listening alive, and needing the screen on would defeat the point.
 */
class MainActivity : ComponentActivity() {

    private var viewModel: MonitorViewModel? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Read after the dialog closed: false here means Android has stopped asking, which is the
        // only reliable signal for a permanent denial.
        viewModel?.onPermissionResult(
            granted = granted,
            canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Soft dependency: denial hides the notification but does not block listening. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteEarTheme {
                val vm: MonitorViewModel = viewModel()
                viewModel = vm

                // Three documents behind an overflow menu is not enough to justify a navigation
                // library, and CLAUDE.md keeps third-party runtime dependencies out. One nullable
                // value plus BackHandler is the whole router.
                var open by remember { mutableStateOf<MenuItem?>(null) }
                BackHandler(enabled = open != null) { open = null }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalPalette.current.surface,
                ) {
                    when (val item = open) {
                        null -> MonitorRoute(
                            viewModel = vm,
                            onRequestMicPermission = {
                                // Once Android has stopped asking, launching the request does
                                // nothing at all - so the button has to go somewhere the user can
                                // actually act, rather than sit there looking functional.
                                if (vm.uiState.value.micPermanentlyDenied) {
                                    openAppSettings()
                                } else {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onMenu = { open = it },
                        )
                        MenuItem.About -> AboutScreen(
                            onBack = { open = null },
                            onOpen = { open = it },
                        )
                        else -> DocumentScreen(item = item, onBack = { open = null })
                    }
                }
            }
        }

        // Asked once, up front, and never blocking: an app that holds the microphone for hours
        // should be conspicuous, and the ongoing notification is how. See docs/privacy.md.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Permission and volume can both change in the system settings while we are stopped. */
    override fun onResume() {
        super.onResume()
        viewModel?.refresh()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}

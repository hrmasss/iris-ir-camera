package io.github.hrmasss.iris

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hrmasss.iris.ui.CameraListScreen
import io.github.hrmasss.iris.ui.CameraScreen
import io.github.hrmasss.iris.ui.CameraViewModel
import io.github.hrmasss.iris.ui.PermissionScreen
import io.github.hrmasss.iris.ui.SettingsScreen
import io.github.hrmasss.iris.ui.theme.IrisTheme

private enum class Screen { Camera, Settings }

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()
    private var granted by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.Camera)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        granted = hasCamera()
        setContent {
            var denied by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                granted = ok
                denied = !ok
                if (ok) vm.rescan()
            }
            val state by vm.state.collectAsStateWithLifecycle()

            IrisTheme(dark = true) {
                when {
                    !granted -> PermissionScreen(
                        denied = denied,
                        onGrant = { launcher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        },
                    )
                    else -> AnimatedContent(screen to (state.showFallback || state.scanning), label = "screen") { (current, listing) ->
                        when {
                            current == Screen.Settings -> {
                                BackHandler { screen = Screen.Camera }
                                SettingsScreen(vm) { screen = Screen.Camera }
                            }
                            listing -> {
                                val canGoBack = state.active != null
                                if (canGoBack) BackHandler { vm.choose(state.active!!) }
                                CameraListScreen(
                                    scanning = state.scanning,
                                    cameras = state.cameras,
                                    irFound = state.cameras.any { it.isIr },
                                    onOpen = vm::choose,
                                    onRescan = vm::rescan,
                                    onBack = if (canGoBack) ({ vm.choose(state.active!!) }) else null,
                                )
                            }
                            else -> CameraScreen(vm) { screen = Screen.Settings }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted from system settings while we were away.
        granted = hasCamera()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && granted && screen == Screen.Camera) {
            // Held keys repeat; only the first press shoots, but repeats are still swallowed.
            if (event.repeatCount > 0) return vm.settings.value.volumeKeysShoot
            if (vm.onVolumeKey()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

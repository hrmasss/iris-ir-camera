package io.github.hrmasss.iris.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hrmasss.iris.R
import io.github.hrmasss.iris.camera.CameraInfo
import io.github.hrmasss.iris.camera.Look
import io.github.hrmasss.iris.camera.THERMAL_SHADER
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(vm: CameraViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onPause() }

    var rotation by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snapped = ((orientation + 45) / 90 * 90) % 360
                if (snapped != rotation) {
                    rotation = snapped
                    vm.deviceRotation = snapped
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    val iconRotation by animateFloatAsState(-rotation.toFloat().let { if (it < -180f) it + 360f else it }, label = "iconRotation")

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopControls(
                timerSeconds = settings.timerSeconds,
                grid = settings.grid,
                iconRotation = iconRotation,
                onTimer = { vm.setTimer(nextTimer(settings.timerSeconds)) },
                onGrid = { vm.setGrid(!settings.grid) },
                onCameras = vm::showCameraList,
                onSettings = onOpenSettings,
            )
            // The preview takes whatever height is left, so the controls below never get squeezed.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Preview(vm, state, settings.grid, Modifier)
            }
            LookRow(state.look, vm::setLook)
            ModeRow(state.mode, enabled = state.recordingSince == null, videoSupported = state.active?.videoSize != null, onMode = vm::setMode)
            BottomControls(
                state = state,
                iconRotation = iconRotation,
                onShutter = vm::shutter,
                onSwitch = vm::nextCamera,
                onThumb = {
                    state.lastUri?.let { uri ->
                        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp))
    }
}

private fun nextTimer(current: Int) = when (current) {
    0 -> 3
    3 -> 10
    else -> 0
}

@Composable
private fun TopControls(
    timerSeconds: Int,
    grid: Boolean,
    iconRotation: Float,
    onTimer: () -> Unit,
    onGrid: () -> Unit,
    onCameras: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onTimer) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.rotate(iconRotation)) {
                if (timerSeconds == 0) {
                    Icon(Icons.Outlined.TimerOff, stringResource(R.string.timer_off), tint = Color.White)
                } else {
                    Icon(Icons.Outlined.Timer, stringResource(R.string.timer), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (timerSeconds > 0) {
            Text(stringResource(R.string.seconds, timerSeconds), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = onGrid) {
            Icon(
                if (grid) Icons.Outlined.Grid3x3 else Icons.Outlined.GridOff,
                stringResource(R.string.grid),
                tint = if (grid) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.rotate(iconRotation),
            )
        }
        IconButton(onClick = onCameras) {
            Icon(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.no_ir_cameras), tint = Color.White, modifier = Modifier.rotate(iconRotation))
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = Color.White, modifier = Modifier.rotate(iconRotation))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Preview(vm: CameraViewModel, state: CameraUiState, grid: Boolean, modifier: Modifier) {
    val camera = state.active
    // The preview buffer is landscape for a 90/270 sensor; flip the ratio for the portrait screen.
    val aspect = camera?.let {
        val w = it.previewSize.width.toFloat()
        val h = it.previewSize.height.toFloat()
        if (it.sensorOrientation % 180 == 0) w / h else h / w
    } ?: 0.75f
    val zoom by rememberUpdatedState(state.zoom)

    Box(
        modifier
            .aspectRatio(aspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111111))
            .pointerInput(Unit) {
                detectTransformGestures { _, _, scale, _ -> if (scale != 1f) vm.setZoom(zoom * scale) }
            },
    ) {
        val thermal = state.look.needsShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = vm.onSurface(surface)
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            vm.onSurface(null)
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    vm.previewFrame = { bitmap }
                }
            },
            update = { view ->
                view.setLayerPaint(state.look.matrix?.let { m -> Paint().apply { colorFilter = ColorMatrixColorFilter(m) } })
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = if (thermal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        RenderEffect.createRuntimeShaderEffect(RuntimeShader(THERMAL_SHADER), "content").asComposeRenderEffect()
                    } else {
                        null
                    }
                },
        )

        if (grid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(c, androidx.compose.ui.geometry.Offset(size.width * i / 3, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height * i / 3), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 3), 1.5f)
                }
            }
        }

        camera?.let { CameraBadge(it, Modifier.align(Alignment.TopStart).padding(12.dp)) }

        state.recordingSince?.let { RecordingClock(it, Modifier.align(Alignment.TopCenter).padding(12.dp)) }

        AnimatedVisibility(!state.previewing, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        }

        AnimatedVisibility(state.countdown > 0, Modifier.align(Alignment.Center), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Text(
                state.countdown.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
            )
        }

        if (state.zoom > 1.01f) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                Text(
                    String.format(Locale.US, "%.1f×", state.zoom),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CameraBadge(camera: CameraInfo, modifier: Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (camera.isIr) {
            Text(
                stringResource(R.string.ir_badge),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(stringResource(R.string.camera_label, camera.id), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RecordingClock(since: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        modifier.clip(CircleShape).background(Color(0xFFB3261E)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LookRow(current: Look, onLook: (Look) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        items(Look.available()) { look ->
            FilterChip(
                selected = look == current,
                onClick = { onLook(look) },
                label = { Text(stringResource(look.label)) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Color.White,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeRow(mode: Mode, enabled: Boolean, videoSupported: Boolean, onMode: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        ToggleButton(checked = mode == Mode.Photo, onCheckedChange = { onMode(Mode.Photo) }, enabled = enabled) {
            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.mode_photo))
        }
        Spacer(Modifier.width(8.dp))
        ToggleButton(checked = mode == Mode.Video, onCheckedChange = { onMode(Mode.Video) }, enabled = enabled && videoSupported) {
            Icon(Icons.Outlined.Videocam, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.mode_video))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomControls(
    state: CameraUiState,
    iconRotation: Float,
    onShutter: () -> Unit,
    onSwitch: () -> Unit,
    onThumb: () -> Unit,
) {
    var spins by remember { mutableIntStateOf(0) }
    val switchRotation by animateFloatAsState(spins * 180f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "switch")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .requiredSize(56.dp)
                .clip(MaterialShapes.Cookie4Sided.toShape())
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(enabled = state.lastUri != null, onClick = onThumb),
            contentAlignment = Alignment.Center,
        ) {
            state.lastThumb?.let {
                Image(it.asImageBitmap(), stringResource(R.string.last_capture), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }

        Shutter(state, onShutter)

        FilledTonalIconButton(
            onClick = {
                spins++
                onSwitch()
            },
            enabled = state.cameras.size > 1 && state.recordingSince == null,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            modifier = Modifier.requiredSize(56.dp),
        ) {
            Icon(Icons.Outlined.Cameraswitch, stringResource(R.string.switch_camera), Modifier.rotate(switchRotation + iconRotation))
        }
    }
}

@Composable
private fun Shutter(state: CameraUiState, onShutter: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "shutter")
    val recording = state.recordingSince != null
    val video = state.mode == Mode.Video
    val inner = when {
        recording -> Color(0xFFB3261E)
        video -> Color(0xFFE5484D)
        else -> Color.White
    }
    val innerSize by animateFloatAsState(if (recording) 30f else 62f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "inner")
    val corner by animateFloatAsState(if (recording) 8f else 31f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "corner")
    val label = stringResource(
        when {
            recording -> R.string.record_stop
            video -> R.string.record_start
            else -> R.string.shutter
        },
    )
    Box(
        Modifier
            .requiredSize(80.dp)
            .scale(scale)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = state.previewing && !state.busy && state.countdown == 0,
                onClick = onShutter,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(innerSize.dp)
                .clip(RoundedCornerShape(corner.dp))
                .background(if (state.previewing) inner else inner.copy(alpha = 0.4f)),
        )
    }
}

package io.github.hrmasss.iris.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hrmasss.iris.R
import io.github.hrmasss.iris.camera.CameraInfo
import io.github.hrmasss.iris.camera.Facing
import io.github.hrmasss.iris.camera.SensorKind

/**
 * Shown when no IR camera was found, and from the camera screen's camera list button.
 * Lists every camera the phone exposes so the app stays useful without an IR sensor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraListScreen(
    scanning: Boolean,
    cameras: List<CameraInfo>,
    irFound: Boolean,
    onOpen: (CameraInfo) -> Unit,
    onRescan: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var showHelp by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (irFound) R.string.no_ir_cameras else R.string.app_name)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    }
                },
                actions = {
                    IconButton(onClick = onRescan, enabled = !scanning) { Icon(Icons.Outlined.Refresh, stringResource(R.string.no_ir_rescan)) }
                },
            )
        },
    ) { padding ->
        if (scanning) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(72.dp))
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!irFound) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Box(
                            Modifier.size(96.dp).clip(MaterialShapes.Cookie7Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.no_ir_title), style = MaterialTheme.typography.headlineMediumEmphasized)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.no_ir_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showHelp = true }) { Text(stringResource(R.string.no_ir_help)) }
                            FilledTonalButton(onClick = onRescan) { Text(stringResource(R.string.no_ir_rescan)) }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.no_ir_cameras),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (cameras.isEmpty()) {
                item { Text(stringResource(R.string.no_ir_none), style = MaterialTheme.typography.bodyLarge) }
            }
            items(cameras, key = { it.id }) { camera -> CameraCard(camera, onOpen) }
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.no_ir_help)) },
            text = { Text(stringResource(R.string.no_ir_help_body)) },
        )
    }
}

@Composable
private fun CameraCard(camera: CameraInfo, onOpen: (CameraInfo) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.camera_label, camera.id), style = MaterialTheme.typography.titleMedium)
                    if (camera.isIr) {
                        Text(
                            stringResource(R.string.ir_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        stringResource(facingLabel(camera.facing)),
                        stringResource(sensorLabel(camera.sensorKind)),
                        "${camera.previewSize.width}×${camera.previewSize.height}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!camera.listed) {
                    Text(stringResource(R.string.sensor_hidden), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Button(onClick = { onOpen(camera) }) { Text(stringResource(R.string.no_ir_open)) }
        }
    }
}

private fun facingLabel(f: Facing) = when (f) {
    Facing.Front -> R.string.facing_front
    Facing.Back -> R.string.facing_back
    Facing.External -> R.string.facing_external
    Facing.Unknown -> R.string.facing_unknown
}

private fun sensorLabel(k: SensorKind) = when (k) {
    SensorKind.NearInfrared -> R.string.sensor_nir
    SensorKind.Monochrome -> R.string.sensor_mono
    SensorKind.Color, SensorKind.Unknown -> R.string.sensor_color
}

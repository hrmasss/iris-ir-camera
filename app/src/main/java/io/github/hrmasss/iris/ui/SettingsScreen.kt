package io.github.hrmasss.iris.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hrmasss.iris.BuildConfigInfo
import io.github.hrmasss.iris.R
import io.github.hrmasss.iris.camera.Look

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var pickCamera by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { Section(R.string.settings_camera) }
            item {
                val preferred = state.cameras.firstOrNull { it.id == settings.preferredCameraId }
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_preferred)) },
                    supportingContent = {
                        Text(preferred?.let { stringResource(R.string.camera_label, it.id) } ?: stringResource(R.string.settings_preferred_auto))
                    },
                    modifier = Modifier.clickable { pickCamera = true },
                )
            }

            item { Section(R.string.settings_capture) }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_default_filter)) },
                    supportingContent = {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            items(Look.available()) { look ->
                                FilterChip(
                                    selected = settings.defaultLook == look,
                                    onClick = { vm.setDefaultLook(look) },
                                    label = { Text(stringResource(look.label)) },
                                )
                            }
                        }
                    },
                )
            }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_timer)) },
                    supportingContent = {
                        val options = listOf(0, 3, 10)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            options.forEachIndexed { index, seconds ->
                                ToggleButton(
                                    checked = settings.timerSeconds == seconds,
                                    onCheckedChange = { vm.setTimer(seconds) },
                                    shapes = when (index) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (seconds == 0) stringResource(R.string.off) else stringResource(R.string.seconds, seconds))
                                }
                            }
                        }
                    },
                )
            }
            item {
                SwitchItem(R.string.settings_bake_filter, R.string.settings_bake_filter_desc, settings.bakeLook, vm::setBakeLook)
            }
            item { SwitchItem(R.string.settings_mirror, R.string.settings_mirror_desc, settings.mirrorSelfies, vm::setMirrorSelfies) }
            item { SwitchItem(R.string.settings_volume_keys, R.string.settings_volume_keys_desc, settings.volumeKeysShoot, vm::setVolumeKeysShoot) }
            item { SwitchItem(R.string.settings_shutter_sound, R.string.settings_shutter_sound_desc, settings.shutterSound, vm::setShutterSound) }
            item { SwitchItem(R.string.settings_grid, null, settings.grid, vm::setGrid) }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.mode_video)) },
                    supportingContent = { Text(stringResource(R.string.video_note)) },
                )
            }

            item { Section(R.string.settings_about) }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_privacy)) },
                    supportingContent = { Text(stringResource(R.string.settings_privacy_desc)) },
                )
            }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_source)) },
                    supportingContent = { Text(stringResource(R.string.settings_source_desc)) },
                )
            }
            item {
                SettingItem(
                    headline = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(BuildConfigInfo.versionName(androidx.compose.ui.platform.LocalContext.current)) },
                )
            }
        }
    }

    if (pickCamera) {
        AlertDialog(
            onDismissRequest = { pickCamera = false },
            confirmButton = { TextButton(onClick = { pickCamera = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.settings_preferred)) },
            text = {
                Column {
                    RadioRow(stringResource(R.string.settings_preferred_auto), settings.preferredCameraId.isEmpty()) {
                        vm.setPreferredCamera("")
                        pickCamera = false
                    }
                    state.cameras.forEach { camera ->
                        val label = stringResource(R.string.camera_label, camera.id) + if (camera.isIr) "  ·  IR" else ""
                        RadioRow(label, settings.preferredCameraId == camera.id) {
                            vm.setPreferredCamera(camera.id)
                            vm.choose(camera)
                            pickCamera = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(title: Int, description: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingItem(
        headline = { Text(stringResource(title)) },
        supportingContent = description?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** ListItem with the headline as a named slot, matching how the rest of this screen reads. */
@Composable
private fun SettingItem(
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = ListItem(modifier = modifier, supportingContent = supportingContent, trailingContent = trailingContent, content = headline)

package com.airbuds.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbuds.companion.R
import com.airbuds.companion.data.ble.BlePermissions
import com.airbuds.companion.data.ble.GattState
import com.airbuds.companion.data.ble.RaceDeviceInfo
import com.airbuds.companion.data.ble.ScannedDevice
import com.airbuds.companion.data.ble.TwsRole
import com.airbuds.companion.data.ble.YfyBatteryLevel
import com.airbuds.companion.data.ble.YfyBatteryStatus
import com.airbuds.companion.domain.model.BudsBattery
import com.airbuds.companion.overlay.BudsOverlayService
import com.airbuds.companion.overlay.OverlayPermission

/**
 * Debug-only screen for validating the BLE stack on a real device.
 *
 * It exposes the runtime permission request, a filtered scan, connection,
 * initialization and the raw TX/RX log. It is intentionally not part of the
 * product navigation.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(BlePermissions.hasScanPermissions(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = BlePermissions.hasScanPermissions(context)
        if (permissionsGranted) {
            viewModel.scan()
        } else {
            viewModel.onPermissionsDenied()
        }
    }
    var overlayGranted by remember {
        mutableStateOf(OverlayPermission.canDraw(context))
    }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }
    LifecycleResumeEffect(Unit) {
        overlayGranted = OverlayPermission.canDraw(context)
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    val closeScreen = {
        viewModel.disconnect()
        onBack()
    }
    BackHandler(onBack = closeScreen)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DiagnosticsHeader(onBack = closeScreen, onClear = viewModel::clearLog)
        }
        item {
            DiagnosticCard {
                PermissionSection(
                    granted = permissionsGranted,
                    onGrant = { permissionLauncher.launch(BlePermissions.scan) },
                )
            }
        }
        item {
            DiagnosticCard {
                ConnectionSection(
                    state = state,
                    onScan = {
                        if (BlePermissions.hasScanPermissions(context)) {
                            permissionsGranted = true
                            viewModel.scan()
                        } else {
                            permissionLauncher.launch(BlePermissions.scan)
                        }
                    },
                    onDisconnect = viewModel::disconnect,
                    onFilterChange = viewModel::setFilterByService,
                    onOpenBluetoothSettings = {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
        item {
            DiagnosticCard {
                OverlaySection(
                    overlayGranted = overlayGranted,
                    notificationsGranted = notificationsGranted,
                    needsNotificationPermission = Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU,
                    onOpenOverlaySettings = {
                        OverlayPermission.openSettings(context)
                    },
                    onGrantNotifications = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onShowOverlay = {
                        if (!overlayGranted) {
                            OverlayPermission.openSettings(context)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !notificationsGranted
                            ) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            BudsOverlayService.start(context)
                        }
                    },
                    onHideOverlay = { BudsOverlayService.stop(context) },
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.diagnostics_devices),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (state.devices.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.diagnostics_no_devices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(items = state.devices, key = { it.device.address }) { device ->
                DeviceRow(
                    device = device,
                    onConnect = { viewModel.connect(device.device) },
                )
            }
        }

        state.deviceInfo?.let { info ->
            item {
                DiagnosticCard {
                    DeviceInfoSection(info)
                }
            }
        }

        if (state.battery.isKnown) {
            item {
                DiagnosticCard {
                    BatterySection(state.battery)
                }
            }
        }

        state.yfyBattery?.let { status ->
            item {
                DiagnosticCard {
                    YfyBatterySection(status)
                }
            }
        }

        state.error?.let { error ->
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.diagnostics_logs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (state.logs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.diagnostics_no_logs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.logs) { line ->
                Text(
                    text = line,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DiagnosticsHeader(onBack: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.diagnostics_back),
            )
        }
        Text(
            text = stringResource(R.string.diagnostics_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
        )
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.diagnostics_clear))
        }
    }
}

@Composable
private fun PermissionSection(granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.diagnostics_permissions),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (granted) {
                    R.string.diagnostics_permissions_granted
                } else {
                    R.string.diagnostics_permissions_missing
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    if (!granted) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.diagnostics_grant))
        }
    }
}

@Composable
private fun ConnectionSection(
    state: DiagnosticsUiState,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    Text(
        text = stringResource(R.string.diagnostics_gatt_state),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = state.gattState.label(),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.filterByService,
            onCheckedChange = onFilterChange,
        )
        Text(
            text = stringResource(R.string.diagnostics_filter_service),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !state.scanning) {
            Text(
                stringResource(
                    if (state.scanning) {
                        R.string.diagnostics_scanning
                    } else {
                        R.string.diagnostics_scan
                    },
                ),
            )
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.diagnostics_disconnect))
        }
    }
    TextButton(onClick = onOpenBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostics_bluetooth_settings))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRow(device: ScannedDevice, onConnect: () -> Unit) {
    Surface(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name
                        ?: stringResource(R.string.diagnostics_unknown_device),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = device.device.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DeviceInfoSection(info: RaceDeviceInfo) {
    Text(
        text = stringResource(R.string.diagnostics_device_info),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(10.dp))
    InfoRow(stringResource(R.string.diagnostics_chip), info.chipName)
    InfoRow(stringResource(R.string.diagnostics_type), info.deviceType.name)
    InfoRow(stringResource(R.string.diagnostics_nvdm), info.nvdmVersion.toString())
    InfoRow(stringResource(R.string.diagnostics_role), info.role.label())
    InfoRow(
        stringResource(R.string.diagnostics_peer),
        info.peerId?.toString() ?: stringResource(R.string.diagnostics_none),
    )
}

@Composable
private fun BatterySection(battery: BudsBattery) {
    Text(
        text = stringResource(R.string.diagnostics_battery),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(10.dp))
    battery.agentPercent?.let {
        InfoRow(stringResource(R.string.diagnostics_battery_agent), "$it %")
    }
    battery.partnerPercent?.let {
        InfoRow(stringResource(R.string.diagnostics_battery_partner), "$it %")
    }
    battery.casePercent?.let {
        InfoRow(stringResource(R.string.diagnostics_battery_case), "$it %")
    }
}

@Composable
private fun YfyBatterySection(status: YfyBatteryStatus) {
    Text(
        text = stringResource(R.string.diagnostics_yfy_battery),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(10.dp))
    InfoRow(stringResource(R.string.diagnostics_battery_right), status.right.display())
    InfoRow(stringResource(R.string.diagnostics_battery_left), status.left.display())
    InfoRow(stringResource(R.string.diagnostics_battery_case), status.case.display())
}

private fun YfyBatteryLevel.display(): String {
    val value = percent?.let { "$it %" } ?: "?"
    return if (charging) "$value ⚡" else value
}

@Composable
private fun InfoRow(label: String, value: String) {    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun OverlaySection(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    needsNotificationPermission: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onGrantNotifications: () -> Unit,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
) {
    Text(
        text = stringResource(R.string.overlay_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.overlay_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    PermissionStatusRow(
        label = stringResource(R.string.overlay_permission),
        granted = overlayGranted,
    )
    if (!overlayGranted) {
        TextButton(onClick = onOpenOverlaySettings) {
            Text(stringResource(R.string.overlay_open_settings))
        }
    }
    if (needsNotificationPermission) {
        PermissionStatusRow(
            label = stringResource(R.string.overlay_notifications),
            granted = notificationsGranted,
        )
        if (!notificationsGranted) {
            TextButton(onClick = onGrantNotifications) {
                Text(stringResource(R.string.overlay_grant_notifications))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onShowOverlay, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.overlay_show))
        }
        OutlinedButton(onClick = onHideOverlay, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.overlay_hide))
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (granted) {
                    R.string.diagnostics_permissions_granted
                } else {
                    R.string.diagnostics_permissions_missing
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun GattState.label(): String = when (this) {
    GattState.Idle -> "Idle"
    is GattState.Connecting -> "Connecting ($address)"
    is GattState.Discovering -> "Discovering ($address)"
    is GattState.Configuring -> "Configuring ($address)"
    is GattState.Ready -> "Ready ($address, MTU $mtu)"
    is GattState.Disconnected -> "Disconnected${address?.let { " ($it)" } ?: ""}"
    is GattState.Failed -> "Failed: $reason"
}

private fun TwsRole.label(): String = when (this) {
    TwsRole.Normal -> "Normal"
    TwsRole.Master -> "Master"
    TwsRole.Slave -> "Slave"
    is TwsRole.Unknown -> "Unknown ($code)"
}

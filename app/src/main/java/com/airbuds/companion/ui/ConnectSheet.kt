package com.airbuds.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.airbuds.companion.R
import com.airbuds.companion.data.ble.BlePermissions
import com.airbuds.companion.data.ble.ScannedDevice

/**
 * Bottom sheet that requests Bluetooth permissions, scans for Airoha devices
 * and connects to the selected one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(
    visible: Boolean,
    state: BudsUiState,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(BlePermissions.hasScanPermissions(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = BlePermissions.hasScanPermissions(context)
        if (permissionsGranted) onScan()
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && state.devices.isEmpty() && !state.scanning) {
            onScan()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.connect_support_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (!state.bluetoothEnabled) {
                Text(
                    text = stringResource(R.string.bluetooth_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnableBluetooth) {
                    Text(stringResource(R.string.bluetooth_enable))
                }
            } else if (!permissionsGranted) {
                Text(
                    text = stringResource(R.string.connect_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(BlePermissions.scan) }) {
                    Text(stringResource(R.string.connect_grant))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onScan,
                        enabled = !state.scanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (state.scanning) {
                                    R.string.connect_scanning
                                } else {
                                    R.string.connect_scan
                                },
                            ),
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                }

                if (state.busy) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.connect_connecting),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                state.visibleError?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error.userMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                val connected = state.connectedDevices
                val last = state.lastDevice?.takeIf { device ->
                    connected.none { it.device.address == device.device.address }
                }
                val paired = state.pairedDevices.filterNot { device ->
                    connected.any { it.device.address == device.device.address } ||
                        last?.device?.address == device.device.address
                }
                val nearby = state.devices.filterNot { device ->
                    connected.any { it.device.address == device.device.address } ||
                        paired.any { it.device.address == device.device.address } ||
                        last?.device?.address == device.device.address
                }
                val empty = connected.isEmpty() && last == null &&
                    paired.isEmpty() && nearby.isEmpty()
                if (empty && !state.scanning) {
                    Text(
                        text = stringResource(R.string.connect_no_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        if (connected.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.connect_connected))
                            connected.forEach { device ->
                                DeviceRow(
                                    device = device,
                                    badge = stringResource(R.string.connect_connected_badge),
                                    onConnect = { onConnect(device) },
                                )
                            }
                        }
                        if (last != null) {
                            SectionLabel(stringResource(R.string.connect_last_used))
                            DeviceRow(
                                device = last,
                                badge = stringResource(R.string.connect_last_used_badge),
                                onConnect = { onConnect(last) },
                            )
                        }
                        if (paired.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.connect_paired))
                            paired.forEach { device ->
                                DeviceRow(
                                    device = device,
                                    badge = stringResource(R.string.connect_paired_label),
                                    onConnect = { onConnect(device) },
                                )
                            }
                        }
                        if (nearby.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.connect_nearby))
                            nearby.forEach { device ->
                                DeviceRow(
                                    device = device,
                                    badge = "${device.rssi} dBm",
                                    onConnect = { onConnect(device) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceRow(device: ScannedDevice, badge: String, onConnect: () -> Unit) {
    OutlinedButton(
        onClick = onConnect,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = device.name ?: stringResource(R.string.diagnostics_unknown_device),
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
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

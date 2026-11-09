package com.airbuds.companion.data.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permissions required by BLE scanning and connections.
 *
 * Android 12+ split Bluetooth access into `BLUETOOTH_SCAN` and
 * `BLUETOOTH_CONNECT`. Older versions only require location permission for
 * scanning; `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time permissions declared
 * in the manifest.
 */
object BlePermissions {

    val scan: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    val connect: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun hasScanPermissions(context: Context): Boolean = allGranted(context, scan)

    fun hasConnectPermissions(context: Context): Boolean = allGranted(context, connect)

    private fun allGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}

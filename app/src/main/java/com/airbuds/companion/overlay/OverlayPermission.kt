package com.airbuds.companion.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Special app access required to draw a window over other applications. */
object OverlayPermission {

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /** Opens the system "Display over other apps" screen when available. */
    fun openSettings(context: Context) {
        val intent = settingsIntent(context)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }
}

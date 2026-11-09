package com.airbuds.companion.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.airbuds.companion.MainActivity
import com.airbuds.companion.R
import com.airbuds.companion.model.BudsState
import com.airbuds.companion.ui.OverlayPopupContent
import com.airbuds.companion.ui.theme.BudsTheme

/**
 * Debug foreground service that hosts the popup in a system overlay.
 *
 * This is a spike: the card uses mocked [BudsState] so the window, permission
 * and notification path can be validated on a real device before wiring the
 * BLE state.
 */
class BudsOverlayService : Service() {

    private val windowManager: WindowManager
        get() = requireNotNull(getSystemService(WindowManager::class.java)) {
            "WindowManager is not available"
        }

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        } catch (error: SecurityException) {
            // Android 14 requires the connected-device FGS prerequisites.
            Log.e(TAG, "Foreground service could not start", error)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!OverlayPermission.canDraw(this) || !showOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showOverlay(): Boolean {
        if (overlayView != null) return true

        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                BudsTheme {
                    var state by remember { mutableStateOf(BudsState()) }
                    OverlayPopupContent(
                        state = state,
                        onAncChange = { state = state.copy(anc = it) },
                        onDismiss = { stopSelf() },
                    )
                }
            }
        }

        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        try {
            windowManager.addView(view, params)
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay token was rejected", error)
            owner.destroy()
            return false
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked", error)
            owner.destroy()
            return false
        }

        owner.create()
        owner.start()
        owner.resume()
        overlayView = view
        lifecycleOwner = owner
        return true
    }

    private fun removeOverlay() {
        val view = overlayView
        if (view != null && view.isAttachedToWindow) {
            windowManager.removeView(view)
        }
        lifecycleOwner?.destroy()
        overlayView = null
        lifecycleOwner = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_buds)
        .setContentTitle(getString(R.string.overlay_notification_title))
        .setContentText(getString(R.string.overlay_notification_text))
        .setContentIntent(launchIntent())
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun launchIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "BudsOverlayService"
        private const val CHANNEL_ID = "buds_overlay"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, BudsOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BudsOverlayService::class.java)
            context.stopService(intent)
        }
    }
}

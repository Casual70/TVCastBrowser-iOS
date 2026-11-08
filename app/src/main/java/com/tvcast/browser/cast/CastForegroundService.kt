package com.tvcast.browser.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tvcast.browser.MainActivity
import com.tvcast.browser.R

/**
 * Servizio Foreground Android (`mediaPlayback`) che mantiene:
 * 1. La connessione socket CASTv2 (porta 8009) e il Proxy HTTP 100MB (porta 8765) sempre attivi
 *    anche quando l'utente passa a un'altra app, apre le notifiche o blocca lo schermo.
 * 2. Una notifica proprietaria di TV Cast Browser con i controlli rapidi (Play/Pausa, -30s, +30s, Stop)
 *    che riporta immediatamente il focus su TV Cast Browser quando viene toccata.
 */
class CastForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val castManager = TvCastManager.getInstance(applicationContext)

        when (action) {
            ACTION_TOGGLE_PLAY -> {
                castManager.togglePlayPause()
                return START_STICKY
            }
            ACTION_REWIND_30 -> {
                castManager.seekRelative(-30.0)
                return START_STICKY
            }
            ACTION_FORWARD_30 -> {
                castManager.seekRelative(30.0)
                return START_STICKY
            }
            ACTION_STOP_CAST -> {
                castManager.stopCasting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "Smart TV"
        val mediaTitle = intent?.getStringExtra(EXTRA_MEDIA_TITLE) ?: "Trasmissione in corso"
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true

        createChannelIfNeeded()
        val notification = buildNotification(deviceName, mediaTitle, isPlaying)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
        }

        return START_STICKY
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Controllo Trasmissione TV Cast Browser",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene attiva e sincronizzata la trasmissione video verso la Smart TV"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(
        deviceName: String,
        mediaTitle: String,
        isPlaying: Boolean
    ): Notification {
        // Toccando la notifica si torna SEMPRE direttamente in focus dentro TV Cast Browser
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindPending = serviceActionPendingIntent(ACTION_REWIND_30, 101)
        val playPausePending = serviceActionPendingIntent(ACTION_TOGGLE_PLAY, 102)
        val forwardPending = serviceActionPendingIntent(ACTION_FORWARD_30, 103)
        val stopPending = serviceActionPendingIntent(ACTION_STOP_CAST, 104)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TV Cast Browser • $deviceName")
            .setContentText(mediaTitle)
            .setSubText("Tocca per aprire i controlli nel browser")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "-30s", rewindPending)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) getString(R.string.pause) else getString(R.string.play),
                playPausePending
            )
            .addAction(android.R.drawable.ic_media_ff, "+30s", forwardPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun serviceActionPendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, CastForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "tvcast_playback_channel"
        private const val NOTIFICATION_ID = 8765

        const val ACTION_START_OR_UPDATE = "com.tvcast.browser.action.START_OR_UPDATE"
        const val ACTION_TOGGLE_PLAY = "com.tvcast.browser.action.TOGGLE_PLAY"
        const val ACTION_REWIND_30 = "com.tvcast.browser.action.REWIND_30"
        const val ACTION_FORWARD_30 = "com.tvcast.browser.action.FORWARD_30"
        const val ACTION_STOP_CAST = "com.tvcast.browser.action.STOP_CAST"
        const val ACTION_STOP_SERVICE = "com.tvcast.browser.action.STOP_SERVICE"

        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        fun startOrUpdate(
            context: Context,
            deviceName: String,
            mediaTitle: String,
            isPlaying: Boolean
        ) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CastForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_DEVICE_NAME, deviceName)
                    putExtra(EXTRA_MEDIA_TITLE, mediaTitle)
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CastForegroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                appCtx.stopService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
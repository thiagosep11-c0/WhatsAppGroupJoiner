package com.groupjoiner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "ongroups_foreground"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.groupjoiner.STOP"
        var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        // WakeLock no service para manter CPU ativa mesmo com tela bloqueada
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, // PARTIAL = mantém CPU sem precisar de tela
            "OnGroups:ServiceWakeLock"
        )
        wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 horas máximo
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val stopPending = PendingIntent.getService(this, 0, stopIntent, stopFlags)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(this, 1, openIntent, stopFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("On Groups — Rodando em background")
            .setContentText("Toque para acompanhar o progresso")
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Parar", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIF_ID, notification)
        return START_STICKY // reinicia automaticamente se o Android matar
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "On Groups Background",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mantém o processo rodando mesmo com tela bloqueada"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

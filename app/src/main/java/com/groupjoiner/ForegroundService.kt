package com.groupjoiner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mantém o processo vivo e o celular acordado durante QUALQUER fase (entrada, mensagens, verificação)
 * e funciona como VIGIA: a cada 15 s confere se o processo está andando. Se travar, acende a tela,
 * reabre o app e manda o processo continuar de onde parou; se não conseguir, avisa com uma notificação.
 */
@Suppress("DEPRECATION")
class ForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0
    private var lastProgressSeen = 0L
    private var recoverStreak = 0
    private var lastRecoverAt = 0L
    private var lastScreenLogAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                watchdogTick()
            } catch (e: Exception) {
                RunLog.add(this@ForegroundService, "⚠ vigia: ${e.message}")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    companion object {
        const val CHANNEL_ID = "ongroups_foreground"
        const val ALERT_CHANNEL_ID = "ongroups_alert"
        const val NOTIF_ID = 1001
        const val ALERT_ID = 1002
        const val ACTION_STOP = "com.groupjoiner.STOP"
        const val TICK_MS = 15_000L
        const val PAUSE_SCREEN = "Tela bloqueada"
        const val PAUSE_SERVICE = "Serviço de acessibilidade desligado"
        var isRunning = false
        @Volatile private var statusText = "Toque para acompanhar o progresso"

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

        /** Atualiza o texto da notificação fixa (aparece também na tela de bloqueio). */
        fun updateStatus(context: Context, text: String) {
            statusText = text
            if (!isRunning) return
            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID, buildNotification(context, text))
            } catch (e: Exception) { }
        }

        /** Aviso de que o processo parou ou precisa de você (com som). */
        fun alert(context: Context, title: String, text: String) {
            try {
                ensureChannels(context)
                val n = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openPending(context))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                NotificationManagerCompat.from(context).notify(ALERT_ID, n)
            } catch (e: Exception) { }
        }

        fun clearAlert(context: Context) {
            try { NotificationManagerCompat.from(context).cancel(ALERT_ID) } catch (e: Exception) { }
        }

        fun hora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        private fun pendingFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT

        private fun openPending(context: Context): PendingIntent {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(context, 1, openIntent, pendingFlags())
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val fg = NotificationChannel(CHANNEL_ID, "On Groups Background", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mantém o processo rodando mesmo com tela bloqueada"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(fg)
                val al = NotificationChannel(ALERT_CHANNEL_ID, "On Groups Avisos", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisa quando o processo parou ou precisa de você"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(al)
            }
        }

        private fun buildNotification(context: Context, text: String): Notification {
            ensureChannels(context)
            val stopIntent = Intent(context, ForegroundService::class.java).apply { action = ACTION_STOP }
            val stopPending = PendingIntent.getService(context, 0, stopIntent, pendingFlags())
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("On Groups — rodando")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPending(context))
                .addAction(android.R.drawable.ic_delete, "Parar", stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannels(this)
        holdLocks(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RunLog.add(this, "⏹ Parado pelo botão da notificação")
            GroupJoinerService.serviceInstance?.abortCurrent() // não deixa terminar de digitar/enviar o grupo atual
            StorageManager.clearRun(this)
            MainActivity.instance?.stopRunFromOutside()
            clearAlert(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(this, statusText))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 5_000L)
        return START_STICKY // se o Android matar o processo, o serviço volta e o vigia retoma
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        try { screenLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
        screenLock = null
    }

    // ---------------- Vigia ----------------

    /** Mantém a CPU (sempre) e a tela (se [screen]) acordadas. As travas vencem sozinhas se o serviço morrer. */
    private fun holdLocks(screen: Boolean, cpu: Boolean = true) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpu) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnGroups:ServiceWakeLock")
                        .also { it.setReferenceCounted(false) }
                }
                wakeLock?.acquire(15 * 60 * 1000L)
            } else {
                wakeLock?.let { if (it.isHeld) it.release() } // pausado ou sem processo: não gasta bateria à toa
            }
            if (screen) {
                if (screenLock == null) {
                    screenLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "OnGroups:ScreenLock"
                    ).also { it.setReferenceCounted(false) }
                }
                screenLock?.acquire(10 * 60 * 1000L)
            } else {
                screenLock?.let { if (it.isHeld) it.release() }
            }
        } catch (e: Exception) { }
    }

    private fun watchdogTick() {
        val snap = StorageManager.loadRun(this)
        if (snap == null || !snap.active) {
            holdLocks(screen = false, cpu = false)
            idleTicks++
            if (idleTicks >= 8) stopSelf() // ~2 min sem nada rodando
            return
        }
        idleTicks = 0
        val now = System.currentTimeMillis()
        val paused = snap.pauseReason.isNotEmpty()

        holdLocks(screen = !paused, cpu = !paused)
        updateStatus(this, snap.summaryLine() + " · " + hora(snap.progressAt))

        if (snap.progressAt != lastProgressSeen) {
            lastProgressSeen = snap.progressAt
            recoverStreak = 0
        }

        if (paused) {
            // pausas automáticas se resolvem sozinhas: tela liberada ou serviço de acessibilidade de volta
            // só retoma sozinho se a pausa for recente (tela: até 3 h; serviço desligado: até 30 min); depois disso espera você
            val idade = now - snap.progressAt
            val libera = (snap.pauseReason.startsWith(PAUSE_SCREEN) && idade < 3 * 3_600_000L && ScreenGuard.isReady(this)) ||
                (snap.pauseReason.startsWith(PAUSE_SERVICE) && idade < 30 * 60_000L && GroupJoinerService.serviceInstance != null)
            if (libera) {
                RunLog.add(this, "🔓 Pausa resolvida: ${snap.pauseReason.take(50)}. Continuando")
                StorageManager.saveRun(this, snap.copy(pauseReason = "", progressAt = now, expectedBy = now + 90_000L))
                clearAlert(this)
                GroupJoinerService.serviceInstance?.relaunchApp(true)
            }
            return
        }

        // tela apagada durante o processo: acende
        if (!ScreenGuard.isScreenOn(this) && now - lastScreenLogAt > 60_000L) {
            lastScreenLogAt = now
            ScreenGuard.wake(this)
            RunLog.add(this, "🔆 A tela estava apagada durante o processo: acendi")
        }

        // travou?
        if (now > snap.expectedBy) recover(snap, now)
    }

    private fun recover(snap: RunSnapshot, now: Long) {
        if (now - lastRecoverAt < 60_000L) return
        lastRecoverAt = now
        recoverStreak++
        val atraso = (now - snap.expectedBy) / 1000
        RunLog.add(
            this,
            "⚠ Sem avanço: ${Phase.label(snap.phase)} grupo ${minOf(snap.index + 1, snap.links.size)}/${snap.links.size}, " +
                "etapa ${snap.stage}, ${atraso}s além do previsto, ${ScreenGuard.describe(this)}. Recuperação $recoverStreak/4"
        )
        if (recoverStreak > 4) {
            val motivo = "Travou 4 vezes seguidas na fase ${Phase.label(snap.phase)} " +
                "(grupo ${minOf(snap.index + 1, snap.links.size)}/${snap.links.size})"
            StorageManager.saveRun(this, snap.copy(pauseReason = motivo, progressAt = now, expectedBy = now + 24L * 3_600_000L))
            RunLog.add(this, "⛔ $motivo. Parei de tentar; abra o app para continuar")
            alert(this, "On Groups parou", "$motivo às ${hora(now)}. Toque para continuar.")
            return
        }
        val svc = GroupJoinerService.serviceInstance
        if (svc == null) {
            RunLog.add(this, "⛔ Serviço de acessibilidade desligado: não consigo reabrir o app")
            alert(
                this, "On Groups parou",
                "O serviço de acessibilidade está desligado. Ative em Configurações > Acessibilidade e toque aqui para continuar."
            )
            return
        }
        svc.relaunchApp(true)
    }
}

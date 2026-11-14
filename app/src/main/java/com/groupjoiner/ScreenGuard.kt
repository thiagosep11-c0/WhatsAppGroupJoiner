package com.groupjoiner

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Estado da tela do celular: o app só consegue operar o WhatsApp com a tela ligada e desbloqueada. */
@Suppress("DEPRECATION")
object ScreenGuard {

    fun isScreenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    fun isLocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= 22) km.isKeyguardLocked else km.inKeyguardRestrictedInputMode()
    }

    /** true = o bloqueio exige PIN/padrão/biometria (o app não consegue destravar sozinho). */
    fun isSecure(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= 23) km.isDeviceSecure else km.isKeyguardSecure
    }

    fun isReady(ctx: Context): Boolean = isScreenOn(ctx) && !isLocked(ctx)

    /** Acende a tela se estiver apagada. */
    fun wake(ctx: Context, ms: Long = 10_000L) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OnGroups:Wake"
            )
            wl.acquire(ms)
        } catch (e: Exception) { }
    }

    /** Texto curto para o registro. */
    fun describe(ctx: Context): String = when {
        !isScreenOn(ctx) -> "tela apagada"
        isLocked(ctx) && isSecure(ctx) -> "tela bloqueada (PIN/biometria)"
        isLocked(ctx) -> "tela bloqueada (deslizar)"
        else -> "tela ligada"
    }
}

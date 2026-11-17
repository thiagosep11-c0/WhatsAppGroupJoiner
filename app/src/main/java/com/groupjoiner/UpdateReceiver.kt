package com.groupjoiner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Recebe a resposta do instalador do Android à atualização do app (ver AppUpdater). */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        try {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    AppUpdater.askToConfirm(context, confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> AppUpdater.onInstalled(context)
                else -> AppUpdater.onInstallFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro $status")
            }
        } catch (e: Exception) { }
    }
}

package com.groupjoiner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Depois de reiniciar o celular ou de atualizar o app: se o Controle remoto estava ligado, o app volta sozinho a ficar
 * online no painel esperando comandos (o serviço de acessibilidade volta sozinho pelo sistema).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (RemoteControl.keepAlive(context)) ForegroundService.start(context)
        } catch (e: Exception) { }
    }
}

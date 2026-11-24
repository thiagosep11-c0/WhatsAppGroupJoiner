package com.groupjoiner

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.io.File

/**
 * Conversa entre o serviço de acessibilidade e o "teclado" do On Groups (OnGroupsKeyboard).
 * O serviço deixa aqui o pedido da foto ([pending]) e troca para o teclado; o teclado, ao chegar no campo de mensagem do
 * WhatsApp, entrega a foto e deixa o resultado em [result]. Tudo só enquanto o app está vivo (nada vai para o disco).
 */
object KeyboardBridge {
    /** Pedido do serviço: colocar a foto [file] no campo de mensagem do WhatsApp [pkg]. */
    class Request(val pkg: String, val file: File, val at: Long)

    /** Resposta do teclado: [ok] = o WhatsApp aceitou a foto; [mime] = formato usado; [accepted] = formatos que o campo aceita. */
    class Result(val ok: Boolean, val mime: String, val accepted: String, val note: String, val at: Long)

    @Volatile var pending: Request? = null
    @Volatile var result: Result? = null

    /** Identificador do teclado do On Groups no Android ("com.groupjoiner/.OnGroupsKeyboard"). */
    fun imeId(ctx: Context): String = ComponentName(ctx.applicationContext, OnGroupsKeyboard::class.java).flattenToShortString()

    /** O usuário já ativou o teclado do On Groups nas configurações do celular? (sem isso o app não consegue trocar para ele) */
    fun isEnabled(ctx: Context): Boolean {
        return try {
            val imm = ctx.applicationContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val id = imeId(ctx)
            imm.enabledInputMethodList.any { it.id == id }
        } catch (e: Exception) {
            false
        }
    }

    /** Teclado em uso agora (para voltar a ele depois da foto). */
    fun currentIme(ctx: Context): String {
        return try {
            Settings.Secure.getString(ctx.applicationContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

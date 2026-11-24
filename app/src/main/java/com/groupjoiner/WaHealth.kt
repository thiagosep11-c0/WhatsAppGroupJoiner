package com.groupjoiner

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Estado de cada WhatsApp do celular (WhatsApp e WhatsApp Business): funcionando, banido, sem conta, não instalado.
 * - Durante as rodadas: se o WhatsApp mostra a tela de conta banida ou a de cadastro, o app pausa (não adianta seguir errando).
 * - Cada grupo que dá certo marca o WhatsApp como funcionando.
 * - Comando "Verificar WhatsApp" do painel: abre cada um, olha a tela e volta.
 * Vai para o painel no sinal (só o estado e a hora, nada da conta).
 */
object WaHealth {
    const val OK = "ok"
    const val BANNED = "banido"
    const val NO_ACCOUNT = "sem_conta"
    const val NOT_INSTALLED = "nao_instalado"
    const val UNKNOWN = "desconhecido"
    val PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")
    private const val K = "wa_health"

    fun name(pkg: String): String = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"

    fun statusText(s: String): String = when (s) {
        OK -> "funcionando"
        BANNED -> "BANIDO"
        NO_ACCOUNT -> "sem conta conectada"
        NOT_INSTALLED -> "não instalado"
        else -> "não deu para ver"
    }

    private fun all(ctx: Context): JSONObject =
        try { JSONObject(StorageManager.getPref(ctx, K, "{}")) } catch (e: Exception) { JSONObject() }

    fun set(ctx: Context, pkg: String, status: String) {
        try {
            val o = all(ctx)
            o.put(pkg, JSONObject().put("s", status).put("at", System.currentTimeMillis()))
            StorageManager.setPref(ctx, K, o.toString())
        } catch (e: Exception) { }
    }

    /** Estado guardado e quando foi visto ("" e 0 = nunca). */
    fun get(ctx: Context, pkg: String): Pair<String, Long> {
        val o = all(ctx).optJSONObject(pkg) ?: return Pair("", 0L)
        return Pair(o.optString("s", ""), o.optLong("at", 0L))
    }

    @Suppress("DEPRECATION")
    fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** Para o sinal do painel: os dois WhatsApp, se estão instalados e o último estado visto. */
    fun toJson(ctx: Context): JSONArray {
        val a = JSONArray()
        for (pkg in PKGS) {
            val inst = installed(ctx, pkg)
            val (s, at) = get(ctx, pkg)
            a.put(JSONObject().put("pkg", pkg).put("installed", inst).put("status", if (!inst) NOT_INSTALLED else s.ifEmpty { UNKNOWN }).put("at", at))
        }
        return a
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /**
     * Olha a tela do WhatsApp: [BANNED] (tela de conta banida), [NO_ACCOUNT] (tela de boas-vindas/cadastro) ou null (nada disso).
     * Dentro de uma conversa nunca diz banido (uma mensagem de alguém falando de banimento não conta).
     */
    fun classify(root: AccessibilityNodeInfo): String? {
        val textos = mutableListOf<String>()
        var temChat = false
        try {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var n = 0
            while (q.isNotEmpty() && n < 500) {
                val node = q.removeFirst()
                n++
                if ((node.viewIdResourceName ?: "").endsWith("/entry")) temChat = true
                val t = node.text?.toString() ?: ""
                val d = node.contentDescription?.toString() ?: ""
                if (t.isNotEmpty()) textos.add(norm(t))
                if (d.isNotEmpty()) textos.add(norm(d))
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
        } catch (e: Exception) { }
        if (temChat) return null
        // frases da própria tela de banimento (regras de grupo como "será banido do grupo" não contam)
        val banido = textos.any {
            it.contains("banido do whatsapp") || it.contains("banida do whatsapp") || it.contains("banned from using whatsapp") ||
                it.contains("nao tem mais permissao para usar o whatsapp") || it.contains("not allowed to use whatsapp") ||
                it.contains("conta foi banida") || it.contains("account has been banned")
        }
        val apoio = textos.any { it.contains("analise") || it.contains("review") || it.contains("suporte") || it.contains("support") }
        if (banido && apoio) return BANNED
        val cadastro = textos.any {
            it.contains("concordar e continuar") || it.contains("agree and continue") || it.contains("insira seu numero") ||
                it.contains("digite seu numero") || it.contains("enter your phone number") || it.contains("verifique seu numero") ||
                it.contains("verify your phone number")
        }
        if (cadastro) return NO_ACCOUNT
        return null
    }

    /** Motivo da pausa quando a rodada encontra um WhatsApp sem condição de uso. */
    fun pauseText(pkg: String, status: String): String = when (status) {
        BANNED -> "${name(pkg)} BANIDO: o WhatsApp mostrou a tela de conta banida. Rodada pausada"
        NO_ACCOUNT -> "${name(pkg)} sem conta: o WhatsApp mostrou a tela de cadastro. Rodada pausada"
        else -> "${name(pkg)}: ${statusText(status)}"
    }

    /** Resumo curto do "Verificar WhatsApp" (vai para o painel e para o Registro). */
    fun summary(res: Map<String, String>): String =
        PKGS.joinToString(" · ") { pkg -> name(pkg) + ": " + statusText(res[pkg] ?: UNKNOWN) }
}

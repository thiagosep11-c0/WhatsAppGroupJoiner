package com.groupjoiner

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controle remoto (painel online, fase 2): recebe do painel os comandos (Pausar, Continuar, Finalizar, Escanear,
 * Verificar aprovados, Novo envio e Pedir registro) e responde a cada um com "feito" ou com o motivo de não ter feito.
 *
 * - Só age se a chave "Controle remoto" da aba Config estiver LIGADA (vem desligada de fábrica).
 * - Cada comando tem um número: se o painel mandar o mesmo duas vezes (resposta perdida), ele roda uma vez só.
 * - O que precisa da tela do app (continuar, escanear, verificar, novo envio) é guardado numa fila curta, o app é trazido
 *   para a frente e a tela cumpre o comando. Se o app não abrir em 20 s o comando falha com o motivo.
 * - Tudo fica no arquivo panel_prefs (fora do backup do Android), inclusive a lista de grupos e a mensagem de um Novo envio
 *   enquanto esperam o app abrir.
 */
object RemoteControl {
    private const val PREFS = "panel_prefs"
    private const val K_ENABLED = "remote_enabled"
    private const val K_SEEN = "remote_seen"      // números dos comandos já tratados
    private const val K_ACKS = "remote_acks"      // respostas que ainda não chegaram ao painel
    private const val K_QUEUE = "remote_queue"    // comandos esperando a tela do app abrir
    private const val K_LAST = "remote_last"      // texto do último comando (aparece na Config)
    private const val MAX_SEEN = 80
    private const val QUEUE_TTL_MS = 120_000L     // comando que esperou mais que isto na fila é descartado (nunca roda atrasado)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()
        if (!on) prefs(ctx).edit().remove(K_QUEUE).apply()
    }

    /** true = o serviço do app fica ligado mesmo sem nada rodando, online no painel e esperando comandos. */
    fun keepAlive(ctx: Context): Boolean = isEnabled(ctx) && PanelClient.isPaired(ctx)

    fun lastText(ctx: Context): String = prefs(ctx).getString(K_LAST, "").orEmpty()

    fun typeName(type: String): String = when (type) {
        "pause" -> "Pausar"
        "resume" -> "Continuar"
        "finalize" -> "Finalizar"
        "scan" -> "Escanear"
        "approvals" -> "Verificar aprovados"
        "start" -> "Novo envio"
        "log" -> "Pedir registro"
        else -> type
    }

    fun okNote(type: String): String = when (type) {
        "pause" -> "pausado"
        "resume" -> "retomado"
        "finalize" -> "finalizado"
        "scan" -> "escaneamento iniciado"
        "approvals" -> "verificação iniciada"
        "start" -> "envio iniciado"
        "log" -> "registro enviado"
        else -> "feito"
    }

    private fun hora(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // ---------- números dos comandos já tratados ----------

    private fun seenList(ctx: Context): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray((prefs(ctx).getString(K_SEEN, "[]") ?: "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        return out
    }

    /** true = comando novo (ainda não tratado). */
    private fun markSeen(ctx: Context, id: String): Boolean {
        return synchronized(this) {
            val l = seenList(ctx)
            if (l.contains(id)) {
                false
            } else {
                l.add(id)
                val keep: List<String> = if (l.size > MAX_SEEN) l.subList(l.size - MAX_SEEN, l.size) else l
                prefs(ctx).edit().putString(K_SEEN, JSONArray(keep).toString()).apply()
                true
            }
        }
    }

    // ---------- respostas para o painel ----------

    fun pendingAcks(ctx: Context): JSONArray {
        return try { JSONArray((prefs(ctx).getString(K_ACKS, "[]") ?: "[]")) } catch (e: Exception) { JSONArray() }
    }

    private fun addAck(ctx: Context, id: String, ok: Boolean, note: String) {
        synchronized(this) {
            val a = pendingAcks(ctx)
            a.put(JSONObject().put("id", id).put("ok", ok).put("note", note.take(180)))
            val keep = JSONArray()
            val start = if (a.length() > 30) a.length() - 30 else 0
            for (i in start until a.length()) keep.put(a.get(i))
            prefs(ctx).edit().putString(K_ACKS, keep.toString()).apply()
        }
    }

    /** O painel já recebeu estas respostas: tira da lista de pendentes. */
    fun acksDelivered(ctx: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        synchronized(this) {
            val a = pendingAcks(ctx)
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (!ids.contains(o.optString("id"))) keep.put(o)
            }
            prefs(ctx).edit().putString(K_ACKS, keep.toString()).apply()
        }
    }

    /** Encerra um comando: [error] null = deu certo. A resposta vai ao painel no próximo sinal (que sai já). */
    fun finish(ctx: Context, id: String, type: String, error: String?, okNote: String) {
        val app = ctx.applicationContext
        val ok = error == null
        val note = error ?: okNote
        addAck(app, id, ok, note)
        val text = typeName(type) + ": " + (if (ok) "feito" else "não feito ($note)")
        prefs(app).edit().putString(K_LAST, "$text · ${hora()}").apply()
        RunLog.add(app, "☁ Painel: $text")
        PanelClient.beat(app, true)
        MainActivity.instance?.refreshRemoteInfo()
    }

    // ---------- fila dos comandos que precisam da tela do app ----------

    private fun readQueue(ctx: Context): JSONArray {
        return try { JSONArray((prefs(ctx).getString(K_QUEUE, "[]") ?: "[]")) } catch (e: Exception) { JSONArray() }
    }

    private fun enqueue(ctx: Context, cmd: JSONObject) {
        synchronized(this) {
            val q = readQueue(ctx)
            cmd.put("queuedAt", System.currentTimeMillis())
            q.put(cmd)
            prefs(ctx).edit().putString(K_QUEUE, q.toString()).apply()
        }
    }

    /** A tela do app abriu: entrega os comandos que esperavam. Os que esperaram demais falham (nunca rodam atrasados). */
    fun takeQueue(ctx: Context): List<JSONObject> {
        val fresh = mutableListOf<JSONObject>()
        val stale = mutableListOf<JSONObject>()
        synchronized(this) {
            val q = readQueue(ctx)
            val now = System.currentTimeMillis()
            for (i in 0 until q.length()) {
                val o = q.optJSONObject(i) ?: continue
                if (now - o.optLong("queuedAt", 0L) > QUEUE_TTL_MS) stale.add(o) else fresh.add(o)
            }
            prefs(ctx).edit().remove(K_QUEUE).apply()
        }
        for (o in stale) finish(ctx, o.optString("id"), o.optString("type"), "o app demorou demais para abrir; o comando foi descartado", "")
        return fresh
    }

    private fun expireIfStuck(ctx: Context, id: String, type: String) {
        var stuck = false
        synchronized(this) {
            val q = readQueue(ctx)
            val keep = JSONArray()
            for (i in 0 until q.length()) {
                val o = q.optJSONObject(i) ?: continue
                if (o.optString("id") == id) stuck = true else keep.put(o)
            }
            if (stuck) prefs(ctx).edit().putString(K_QUEUE, keep.toString()).apply()
        }
        if (stuck) {
            finish(
                ctx, id, type,
                "o app não abriu na frente (o celular pode estar bloqueando janelas em segundo plano ou a tela está bloqueada)", ""
            )
        }
    }

    // ---------- chegada dos comandos ----------

    /** Chamado pelo PanelClient (fora da tela principal) quando a resposta do painel traz comandos. */
    fun onCommands(ctx: Context, cmds: JSONArray) {
        val app = ctx.applicationContext
        for (i in 0 until cmds.length()) {
            val cmd = cmds.optJSONObject(i) ?: continue
            val id = cmd.optString("id")
            if (id.isEmpty() || !markSeen(app, id)) continue
            Handler(Looper.getMainLooper()).post {
                try {
                    execute(app, cmd)
                } catch (e: Exception) {
                    finish(app, id, cmd.optString("type"), "erro no app: ${e.message}", "")
                }
            }
        }
    }

    private fun execute(ctx: Context, cmd: JSONObject) {
        val id = cmd.optString("id")
        val type = cmd.optString("type")
        if (!isEnabled(ctx)) {
            finish(ctx, id, type, "Controle remoto desligado neste celular", "")
            return
        }
        when (type) {
            "log" -> {
                PanelClient.requestFullLog()
                finish(ctx, id, type, null, okNote(type))
            }
            "finalize" -> finalizeNow(ctx, id, type)
            "pause", "resume", "start", "scan", "approvals" -> viaApp(ctx, cmd)
            else -> finish(ctx, id, type, "comando desconhecido neste app", "")
        }
    }

    private fun finalizeNow(ctx: Context, id: String, type: String) {
        val act = MainActivity.instance
        if (act != null) {
            val tinha = act.remoteFinalize()
            finish(ctx, id, type, null, if (tinha) okNote(type) else "nada estava em andamento")
            return
        }
        // a tela do app não existe agora: faz o mesmo que o botão Finalizar da notificação
        val snap = StorageManager.loadRun(ctx)
        val tinha = (snap != null && snap.active) || RunRuntime.scanning
        GroupJoinerService.serviceInstance?.abortCurrent()
        StorageManager.clearRun(ctx)
        RunRuntime.step = ""
        RunRuntime.scanning = false
        ForegroundService.clearAlert(ctx)
        ForegroundService.stop(ctx)
        if (tinha) {
            PanelClient.setLastRun(ctx, "Finalizada pelo painel · " + hora())
            RunLog.add(ctx, "⏹ Finalizado pelo painel")
        }
        finish(ctx, id, type, null, if (tinha) okNote(type) else "nada estava em andamento")
    }

    private fun viaApp(ctx: Context, cmd: JSONObject) {
        val id = cmd.optString("id")
        val type = cmd.optString("type")
        val act = MainActivity.instance
        if (type == "pause" && act != null) {
            finish(ctx, id, type, act.remotePause(), okNote(type))
            return
        }
        val svc = GroupJoinerService.serviceInstance
        if (svc == null) {
            finish(ctx, id, type, "serviço de acessibilidade desligado (ative em Configurações > Acessibilidade)", "")
            return
        }
        // acende a tela e traz o app para a frente: a tela do app cumpre o comando assim que abrir
        ScreenGuard.wake(ctx, 30_000L)
        enqueue(ctx, cmd)
        svc.relaunchForRemote()
        Handler(Looper.getMainLooper()).postDelayed({ expireIfStuck(ctx, id, type) }, 20_000L)
    }
}

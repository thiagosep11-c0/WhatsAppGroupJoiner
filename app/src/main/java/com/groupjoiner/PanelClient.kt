package com.groupjoiner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Painel online: manda ao painel o "sinal de vida" deste celular — etapa, grupo, porcentagem, contagens, bateria, estado da
 * tela e as últimas linhas do Registro. Só fala HTTPS e só depois de parear com o código gerado no painel.
 * Nunca manda links de grupos nem o texto das mensagens. Todo erro de rede é engolido: o painel nunca atrapalha os envios.
 *
 * Fase 2: a resposta do painel pode trazer comandos (ver RemoteControl); as respostas aos comandos e, quando o painel pede,
 * o registro completo (sem links) vão no sinal seguinte.
 */
object PanelClient {
    const val DEFAULT_URL = "https://ongroups-painel.pages.dev"
    private const val PREFS = "panel_prefs"   // arquivo próprio, fora do backup do Android (o token é uma credencial)
    private const val K_URL = "url"
    private const val K_TOKEN = "token"
    private const val K_NAME = "name"
    private const val K_LAST_RUN = "last_run"
    // 7 = manda ao painel o resultado de cada grupo (listas salvas: Reenviar mensagem e Retentar falhas) e separa
    //     "link inválido" de "falha" (error);
    // 6 = ler e trocar a mensagem no meio do envio (msgget/setmsg);
    // 5 = trocar o WhatsApp no meio do envio; 4 = texto como legenda da foto; 3 = foto pelo teclado do On Groups, mensagens
    // intercaladas, estado dos WhatsApp e "Verificar WhatsApp" (2 era a foto pela galeria, que não funcionou).
    // O painel só manda o que o app avisa que sabe fazer.
    const val FEATURE_LEVEL = 7
    private const val MIN_GAP_MS = 6_000L     // sinais comuns: no máximo um a cada 6 s
    private const val FORCED_GAP_MS = 1_500L  // sinais importantes (fim de rodada, app aberto): no máximo um a cada 1,5 s

    @Volatile private var inFlight = false
    @Volatile private var pendingForce = false
    @Volatile private var lastSentAt = 0L
    @Volatile private var lastLogSent = ""
    @Volatile var lastOkAt = 0L
    @Volatile var lastError = ""
    @Volatile private var pollSec = 15          // de quanto em quanto tempo o painel quer sinal (ele muda isto na resposta)
    @Volatile private var wantFullLog = false   // o painel pediu o registro completo

    private class Built(val json: String, val lastLog: String, val ackIds: List<String>, val fullLog: Boolean)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** O painel pediu o registro completo: ele vai junto com o próximo sinal. */
    fun requestFullLog() {
        wantFullLog = true
    }

    /** Intervalo que o painel pediu entre um sinal e outro (entre 5 e 120 s). */
    fun pollMs(): Long = pollSec.coerceIn(5, 120) * 1000L

    /** Manda um sinal só se já passou o intervalo pedido pelo painel (usado pelo serviço enquanto espera comandos). */
    fun beatIfDue(context: Context) {
        if (System.currentTimeMillis() - lastSentAt >= pollMs()) beat(context)
    }

    fun isPaired(context: Context): Boolean = prefs(context).getString(K_TOKEN, "").orEmpty().isNotEmpty()

    /** Cabeçalho de autorização deste celular (para baixar a atualização do app do painel); null se não está conectado. */
    fun authHeader(context: Context): String? {
        val t = prefs(context).getString(K_TOKEN, "").orEmpty()
        return if (t.isEmpty()) null else "Bearer $t"
    }

    fun url(context: Context): String = prefs(context).getString(K_URL, DEFAULT_URL).orEmpty().ifEmpty { DEFAULT_URL }

    fun name(context: Context): String = prefs(context).getString(K_NAME, "").orEmpty()

    /** Resumo da última rodada que terminou (aparece no cartão do celular quando ele está ocioso). */
    fun setLastRun(context: Context, text: String) {
        prefs(context).edit().putString(K_LAST_RUN, text.take(200)).apply()
    }

    fun unpair(context: Context) {
        prefs(context).edit().remove(K_TOKEN).remove(K_NAME).apply()
        lastOkAt = 0L
        RemoteControl.setEnabled(context, false)   // sem painel não há controle remoto (ao conectar de novo, liga de novo na Config)
        MainActivity.instance?.refreshRemoteInfo()
    }

    /** Texto para a aba Config: conectado ou não, e o último problema (se houver). */
    fun statusText(context: Context): String {
        if (!isPaired(context)) {
            return "Não conectado." + (if (lastError.isNotEmpty()) "\n⚠ $lastError" else "")
        }
        val sb = StringBuilder("✅ Conectado como ${name(context)}")
        if (lastOkAt > 0L) {
            val s = (System.currentTimeMillis() - lastOkAt) / 1000
            sb.append("\nÚltimo sinal aceito pelo painel há ").append(if (s < 90) "$s s" else "${s / 60} min")
        } else {
            sb.append("\nAinda não mandei nenhum sinal (toque em Enviar sinal agora).")
        }
        if (lastError.isNotEmpty()) sb.append("\n⚠ ").append(lastError)
        return sb.toString()
    }

    /** Liga este celular ao painel com o código de 6 dígitos que o painel mostra em "Adicionar celular". */
    fun pair(context: Context, baseUrlRaw: String, codeRaw: String, done: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val base = baseUrlRaw.trim().trimEnd('/')
        val code = codeRaw.filter { it.isDigit() }
        if (!base.startsWith("https://")) {
            done(false, "Use o endereço completo do painel, começando com https://")
            return
        }
        if (code.length != 6) {
            done(false, "O código tem 6 dígitos.")
            return
        }
        val ui = Handler(Looper.getMainLooper())
        Thread {
            var ok = false
            val msg: String = try {
                val body = JSONObject().put("code", code).put("model", modelName()).put("appVersion", appVersion(app))
                val r = postJson(base + "/api/pair", body.toString(), null)
                val j = try { JSONObject(r.second) } catch (e: Exception) { JSONObject() }
                if (r.first == 200 && j.optString("token").isNotEmpty()) {
                    prefs(app).edit()
                        .putString(K_URL, base)
                        .putString(K_TOKEN, j.getString("token"))
                        .putString(K_NAME, j.optString("name", "Celular"))
                        .apply()
                    lastError = ""
                    ok = true
                    "Conectado ao painel como " + j.optString("name", "Celular")
                } else {
                    j.optString("erro", "O painel respondeu com erro ${r.first}.")
                }
            } catch (e: Exception) {
                "Sem conexão com o painel: ${e.message ?: "erro de rede"}"
            }
            ui.post { done(ok, msg) }
        }.start()
    }

    /**
     * Manda o sinal de vida. [force] = momento importante (fim de rodada, app aberto): fura o intervalo normal.
     * Sinais comuns que cheguem cedo demais são simplesmente ignorados (o vigia manda outro em até 15 s).
     */
    fun beat(context: Context, force: Boolean = false) {
        try {
            val app = context.applicationContext
            if (!isPaired(app)) return
            if (inFlight) {
                if (force) pendingForce = true
                return
            }
            val gap = System.currentTimeMillis() - lastSentAt
            if (gap < (if (force) FORCED_GAP_MS else MIN_GAP_MS)) {
                if (force) Handler(Looper.getMainLooper()).postDelayed({ beat(app, true) }, FORCED_GAP_MS - gap + 100L)
                return
            }
            sendNow(app)
        } catch (e: Exception) {
            inFlight = false
        }
    }

    private fun sendNow(app: Context) {
        inFlight = true
        lastSentAt = System.currentTimeMillis()
        val base = url(app)
        val token = prefs(app).getString(K_TOKEN, "").orEmpty()
        // tudo (inclusive montar o sinal, que lê o Registro) roda fora da tela principal
        Thread {
            try {
                val built: Built? = try { buildBeat(app) } catch (e: Exception) { null }
                if (built == null) {
                    lastError = "Não consegui montar o sinal."
                    return@Thread
                }
                val r = postJson(base + "/api/device/beat", built.json, token)
                when {
                    r.first in 200..299 -> {
                        lastOkAt = System.currentTimeMillis()
                        lastError = ""
                        lastLogSent = built.lastLog
                        RemoteControl.acksDelivered(app, built.ackIds)   // o painel já tem estas respostas
                        if (built.fullLog) wantFullLog = false
                        handleResponse(app, r.second)
                        ListasPainel.descarregar(app)   // resultado de lista que ficou esperando conexão
                    }
                    r.first == 401 -> {
                        unpair(app)
                        lastError = "Este celular foi removido do painel. Conecte de novo na aba Config."
                    }
                    else -> lastError = "O painel respondeu com erro ${r.first}."
                }
            } catch (e: Exception) {
                lastError = "Sem conexão com o painel (${e.message ?: "rede"})."
            } finally {
                inFlight = false
                if (pendingForce) {
                    pendingForce = false
                    if (isPaired(app)) sendNow(app)
                }
            }
        }.start()
    }

    /** Lê a resposta do painel: o intervalo pedido e os comandos (se houver). */
    private fun handleResponse(app: Context, text: String) {
        try {
            val j = JSONObject(text)
            pollSec = j.optInt("pollSec", 15).coerceIn(5, 120)
            AppUpdater.onHint(app, j.optJSONObject("update"))   // oferta de atualização do app (ou nada, se já está na versão mais nova)
            val cmds = j.optJSONArray("commands")
            if (cmds != null && cmds.length() > 0) RemoteControl.onCommands(app, cmds)
        } catch (e: Exception) { }
    }

    /** Defesa extra: nenhuma linha do registro sai daqui com link de grupo ou de outro site. */
    private fun cleanLine(s: String): String =
        s.replace(Regex("""https?://\S+"""), "[link]").replace(Regex("""chat\.whatsapp\.com/\S*"""), "[link]")

    /** Resultado de cada grupo de uma fase (listas salvas no painel). Devolve o código HTTP (-1 = sem conexão). Chame fora da tela principal. */
    fun postLista(context: Context, body: String): Int {
        val app = context.applicationContext
        val token = prefs(app).getString(K_TOKEN, "").orEmpty()
        if (token.isEmpty()) return 401
        return try { postJson(url(app) + "/api/device/lista", body, token).first } catch (e: Exception) { -1 }
    }

    private fun postJson(address: String, body: String, token: String?): Pair<Int, String> {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Baixa uma foto de um Novo envio (o painel entrega em texto base64). Devolve os bytes ou null e o motivo.
     * Chame fora da tela principal (é rede).
     */
    fun downloadPhoto(context: Context, id: String): Pair<ByteArray?, String> {
        val app = context.applicationContext
        val auth = authHeader(app) ?: return Pair(null, "este celular não está conectado ao painel")
        if (!id.matches(Regex("[0-9a-f]{16}"))) return Pair(null, "número de foto inválido")
        val c = URL(url(app) + "/api/device/media?id=" + id).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10_000
            c.readTimeout = 40_000
            c.setRequestProperty("Authorization", auth)
            val code = c.responseCode
            if (code != 200) return Pair(null, "o painel respondeu $code")
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bytes = try {
                android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return Pair(null, "a foto veio estragada")
            }
            if (bytes.size < 200) return Pair(null, "a foto veio vazia")
            return Pair(bytes, "")
        } catch (e: Exception) {
            return Pair(null, "sem conexão com o painel (" + (e.message ?: "rede") + ")")
        } finally {
            c.disconnect()
        }
    }

    private fun waName(pkg: String): String = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"

    private fun modelName(): String {
        val fab = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val mod = Build.MODEL.orEmpty()
        return (if (mod.startsWith(fab, ignoreCase = true)) mod else "$fab $mod").trim().take(60)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "v" + (pi.versionName ?: "?") + " · instalado " +
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(pi.lastUpdateTime))
    } catch (e: Exception) {
        "?"
    }

    /** Monta o sinal (JSON); devolve também a última linha do Registro enviada (para só mandar o registro quando ele mudar). */
    private fun buildBeat(ctx: Context): Built {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        val snap = StorageManager.loadRun(ctx)?.takeIf { it.active }
        val state = when {
            snap != null && snap.pauseReason.isNotEmpty() -> "paused"
            snap != null -> "running"
            RunRuntime.scanning -> "scanning"
            else -> "idle"
        }
        o.put("state", state)
        if (snap != null) {
            val total = snap.links.size
            // os grupos já tratados são os que vêm ANTES do índice (os que ainda não foram tratados também guardam um status padrão)
            val feitos = snap.statuses.take(snap.index.coerceIn(0, total))
            o.put("phase", snap.phase)
            o.put("stage", snap.stage)
            o.put("pkg", snap.pkg)
            o.put("wa", waName(snap.pkg))
            o.put("total", total)
            o.put("current", minOf(snap.index + 1, total))
            o.put("done", feitos.size)
            o.put(
                "counts",
                JSONObject()
                    .put("ok", feitos.count { it == "joined" || it == "message_sent" || it == "already_member" })
                    .put("wait", feitos.count { it == "requested" || it == "pending" })
                    .put("bad", feitos.count { it == "invalid" || it == "error" || it == "error_env" || it == "admin_only" })
                    .put("skipped", feitos.count { it == "skipped_recent" })
            )
            o.put("pauseReason", snap.pauseReason)
            o.put("elapsedSec", maxOf(0L, (now - snap.startedAt) / 1000))
            o.put("sinceProgressSec", maxOf(0L, (now - snap.progressAt) / 1000))
        } else {
            o.put("phase", if (state == "scanning") "SCAN" else "")
            o.put("total", 0)
            o.put("done", 0)
            o.put("current", 0)
        }
        o.put("step", RunRuntime.step)
        o.put("mention", JSONObject().put("ok", RunRuntime.mentionOk).put("total", RunRuntime.mentionTotal))
        o.put("lastRun", prefs(ctx).getString(K_LAST_RUN, "").orEmpty())

        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        o.put(
            "battery",
            JSONObject()
                .put("pct", if (level >= 0 && scale > 0) level * 100 / scale else 0)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        )
        o.put("screenOn", ScreenGuard.isScreenOn(ctx))
        // tela: o app só opera o WhatsApp com a tela ligada e desbloqueada (PIN/biometria o app não destrava sozinho)
        o.put(
            "screen",
            JSONObject()
                .put("on", ScreenGuard.isScreenOn(ctx))
                .put("locked", ScreenGuard.isLocked(ctx))
                .put("secure", ScreenGuard.isSecure(ctx))
        )
        o.put("accessibility", GroupJoinerService.serviceInstance != null)
        o.put("remote", JSONObject().put("supported", true).put("enabled", RemoteControl.isEnabled(ctx)))
        o.put("model", modelName())
        o.put("appVersion", appVersion(ctx))
        o.put("build", AppUpdater.installedBuild(ctx))   // número do build instalado: o painel diz se há um mais novo
        o.put("feat", FEATURE_LEVEL)                     // este app entende fotos no pedido e o "só preencher" (app antigo não manda isto)
        o.put("photoOk", PhotoStore.supported())         // o Android deste celular deixa mandar foto pelo teclado (11 ou mais novo)
        o.put("kbd", KeyboardBridge.isEnabled(ctx))      // o teclado do On Groups está ativado (sem ele a foto não vai)
        o.put("waList", WaHealth.toJson(ctx))            // WhatsApp e Business: instalado? funcionando, banido, sem conta?
        o.put("android", Build.VERSION.RELEASE ?: "")    // versão do Android (aparece em Informação no painel)

        // respostas dos comandos que o painel mandou e ainda não recebeu
        val acks = RemoteControl.pendingAcks(ctx)
        val ackIds = mutableListOf<String>()
        for (i in 0 until acks.length()) ackIds.add(acks.getJSONObject(i).optString("id"))
        if (acks.length() > 0) o.put("acks", acks)

        val lines = RunLog.lines(ctx, 15)
        val ultima = lines.lastOrNull() ?: ""
        if (ultima != lastLogSent) o.put("log", JSONArray(lines.map { cleanLine(it) }))
        // o painel pediu o registro completo
        val queroLog = wantFullLog
        if (queroLog) o.put("fullLog", JSONArray(RunLog.lines(ctx, 200).map { cleanLine(it) }))
        return Built(o.toString(), ultima, ackIds, queroLog)
    }
}

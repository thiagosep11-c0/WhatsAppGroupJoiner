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
 * Painel online (fase 1, só leitura): manda ao painel o "sinal de vida" deste celular — etapa, grupo, porcentagem, contagens,
 * bateria e as últimas linhas do Registro. Só fala HTTPS e só depois de parear com o código gerado no painel.
 * Nunca manda links de grupos nem o texto das mensagens. Todo erro de rede é engolido: o painel nunca atrapalha os envios.
 */
object PanelClient {
    const val DEFAULT_URL = "https://ongroups-painel.pages.dev"
    private const val PREFS = "panel_prefs"   // arquivo próprio, fora do backup do Android (o token é uma credencial)
    private const val K_URL = "url"
    private const val K_TOKEN = "token"
    private const val K_NAME = "name"
    private const val K_LAST_RUN = "last_run"
    private const val MIN_GAP_MS = 6_000L     // sinais comuns: no máximo um a cada 6 s
    private const val FORCED_GAP_MS = 1_500L  // sinais importantes (fim de rodada, app aberto): no máximo um a cada 1,5 s

    @Volatile private var inFlight = false
    @Volatile private var pendingForce = false
    @Volatile private var lastSentAt = 0L
    @Volatile private var lastLogSent = ""
    @Volatile var lastOkAt = 0L
    @Volatile var lastError = ""

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(context: Context): Boolean = prefs(context).getString(K_TOKEN, "").orEmpty().isNotEmpty()

    fun url(context: Context): String = prefs(context).getString(K_URL, DEFAULT_URL).orEmpty().ifEmpty { DEFAULT_URL }

    fun name(context: Context): String = prefs(context).getString(K_NAME, "").orEmpty()

    /** Resumo da última rodada que terminou (aparece no cartão do celular quando ele está ocioso). */
    fun setLastRun(context: Context, text: String) {
        prefs(context).edit().putString(K_LAST_RUN, text.take(200)).apply()
    }

    fun unpair(context: Context) {
        prefs(context).edit().remove(K_TOKEN).remove(K_NAME).apply()
        lastOkAt = 0L
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
                val built: Pair<String, String>? = try { buildBeat(app) } catch (e: Exception) { null }
                if (built == null) {
                    lastError = "Não consegui montar o sinal."
                    return@Thread
                }
                val r = postJson(base + "/api/device/beat", built.first, token)
                when {
                    r.first in 200..299 -> {
                        lastOkAt = System.currentTimeMillis()
                        lastError = ""
                        lastLogSent = built.second
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

    /** Monta o sinal (JSON) e devolve também a última linha do Registro enviada (para só mandar o registro quando ele mudar). */
    private fun buildBeat(ctx: Context): Pair<String, String> {
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
                    .put("bad", feitos.count { it == "invalid" || it == "error_env" || it == "admin_only" })
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
        o.put("accessibility", GroupJoinerService.serviceInstance != null)
        o.put("model", modelName())
        o.put("appVersion", appVersion(ctx))

        val lines = RunLog.lines(ctx, 15)
        val ultima = lines.lastOrNull() ?: ""
        if (ultima != lastLogSent) o.put("log", JSONArray(lines))
        return Pair(o.toString(), ultima)
    }
}

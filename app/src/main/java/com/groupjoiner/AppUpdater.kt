package com.groupjoiner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Atualização do app pelo próprio app (sem mandar o APK por fora): o painel diz qual é o build mais novo (na resposta do sinal),
 * o app baixa o APK do painel (só celulares conectados conseguem), confere o tamanho e o SHA-256 e entrega ao instalador do
 * Android, que pede UM toque de confirmação ("Atualizar"). O Android só aceita atualização assinada com a MESMA chave deste app
 * (a chave não fica no painel). Nunca atualiza com um processo rodando: a atualização reinicia o app.
 */
object AppUpdater {
    class Info(val build: Int, val name: String, val size: Long, val sha256: String, val notes: String)

    private const val PREFS = "panel_prefs"           // arquivo próprio, fora do backup do Android
    private const val K_LATEST = "update_latest"      // última oferta que o painel mandou (JSON)
    private const val K_NOTIFIED = "update_notified"  // build para o qual já avisei na barra de notificações
    private const val BUSY_MAX_MS = 10 * 60_000L      // atualização "em andamento" que não responde em 10 min é esquecida
    private const val NOTIF_AVAILABLE = 1010
    private const val NOTIF_CONFIRM = 1011
    const val ACTION_RESULT = "com.groupjoiner.UPDATE_RESULT"
    private const val NEEDS_PERMISSION =
        "libere \"Instalar apps desconhecidos\" para o On Groups (Configurações > Apps > On Groups) e tente de novo"

    @Volatile var status: String = ""      // texto para a aba Config ("Baixando...", "Confirme na tela do Android...", erro)
    @Volatile private var busy = false
    @Volatile private var busySince = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- versão instalada ----------

    @Suppress("DEPRECATION")
    private fun buildOf(pi: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode

    fun installedBuild(ctx: Context): Int = try {
        buildOf(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) { 0 }

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    // ---------- oferta do painel ----------

    private fun parse(o: JSONObject): Info? {
        val build = o.optInt("build", 0)
        if (build <= 0) return null
        return Info(build, o.optString("name", "?"), o.optLong("size", 0L), o.optString("sha256", ""), o.optString("notes", ""))
    }

    /** Última oferta guardada (pode já estar instalada). */
    fun latest(ctx: Context): Info? {
        val raw = prefs(ctx).getString(K_LATEST, "") ?: ""
        if (raw.isEmpty()) return null
        return try { parse(JSONObject(raw)) } catch (e: Exception) { null }
    }

    /** A atualização que dá para instalar agora (build mais novo que o instalado), ou null. */
    fun available(ctx: Context): Info? {
        val l = latest(ctx) ?: return null
        return if (l.build > installedBuild(ctx)) l else null
    }

    /** Chamado a cada resposta do painel: [hint] é a oferta (ou null quando este celular já está na versão mais nova). */
    fun onHint(ctx: Context, hint: JSONObject?) {
        val app = ctx.applicationContext
        val info = if (hint != null) parse(hint) else null
        val antes = prefs(app).getString(K_LATEST, "") ?: ""
        if (hint == null || info == null) {
            if (antes.isNotEmpty()) {
                prefs(app).edit().remove(K_LATEST).apply()
                MainActivity.instance?.refreshUpdateInfo()
            }
            return
        }
        val novo = hint.toString()
        if (antes != novo) {
            prefs(app).edit().putString(K_LATEST, novo).apply()
            MainActivity.instance?.refreshUpdateInfo()
        }
        // avisa uma vez por build
        if (info.build > installedBuild(app) && prefs(app).getInt(K_NOTIFIED, 0) != info.build) {
            prefs(app).edit().putInt(K_NOTIFIED, info.build).apply()
            RunLog.add(app, "⬆ Atualização disponível: v${info.name} (build ${info.build})")
            try {
                val abrir = PendingIntent.getActivity(
                    app, 78,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                ForegroundService.alertWithIntent(
                    app, NOTIF_AVAILABLE, "Atualização do On Groups disponível",
                    "v${info.name}. Abra o app > Config > Atualizar agora (ou peça pelo painel).", abrir
                )
            } catch (e: Exception) { }
        }
    }

    /** Texto da aba Config: versão instalada, se há atualização e o andamento. */
    fun describe(ctx: Context): String {
        val sb = StringBuilder("Versão instalada: v${installedName(ctx)} (build ${installedBuild(ctx)})")
        val av = available(ctx)
        if (av != null) {
            val mb = String.format(Locale.getDefault(), "%.1f", av.size / 1048576.0)
            sb.append("\n⬆ Atualização disponível: v${av.name} (build ${av.build}, $mb MB)")
            if (av.notes.isNotEmpty()) sb.append("\n").append(av.notes)
        } else if (!PanelClient.isPaired(ctx)) {
            sb.append("\nConecte ao painel para receber atualizações.")
        } else {
            sb.append("\n✅ Nenhuma atualização disponível (toque em Verificar para olhar de novo).")
        }
        if (!installAllowed(ctx)) sb.append("\n🔒 Falta liberar \"Instalar apps desconhecidos\" para o On Groups (toque em Liberar instalação).")
        if (status.isNotEmpty()) sb.append("\n").append(status)
        return sb.toString()
    }

    // ---------- atualizar ----------

    private fun isBusy(): Boolean {
        if (busy && System.currentTimeMillis() - busySince > BUSY_MAX_MS) busy = false
        return busy
    }

    /** O Android deixa este app instalar atualizações? (Configurações > Apps > On Groups > Instalar apps desconhecidos) */
    fun installAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Motivo de não poder atualizar agora (null = pode). */
    fun blocker(ctx: Context): String? {
        if (isBusy()) return "a atualização já está em andamento"
        if (available(ctx) == null) return "nenhuma atualização disponível"
        val snap = StorageManager.loadRun(ctx)
        if ((snap != null && snap.active) || RunRuntime.scanning) {
            return "há um processo rodando: finalize antes (a atualização reinicia o app)"
        }
        if (!installAllowed(ctx)) return NEEDS_PERMISSION
        return null
    }

    /** Abre a tela do Android em que se libera "Instalar apps desconhecidos" para este app. */
    fun openInstallSettings(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        } catch (e: Exception) { }
    }

    /**
     * Começa a atualização: baixa, confere e entrega ao instalador do Android. Devolve null se começou ou o motivo de não ter
     * começado. [fromPanel] = veio de um comando do painel (não abre telas do Android sozinho).
     */
    fun start(ctx: Context, fromPanel: Boolean): String? {
        val app = ctx.applicationContext
        val motivo = blocker(app)
        if (motivo != null) {
            if (motivo == NEEDS_PERMISSION && !fromPanel) openInstallSettings(ctx)
            return motivo
        }
        val info = available(app) ?: return "nenhuma atualização disponível"
        busy = true
        busySince = System.currentTimeMillis()
        setStatus(app, "Baixando a atualização...")
        RunLog.add(app, "⬆ Atualização: baixando v${info.name} (build ${info.build})")
        Thread { runUpdate(app, info) }.start()
        return null
    }

    private fun setStatus(ctx: Context, text: String) {
        status = text
        MainActivity.instance?.refreshUpdateInfo()
    }

    private fun fail(ctx: Context, text: String) {
        busy = false
        RunLog.add(ctx, "⬆ Atualização: $text")
        setStatus(ctx, text)
    }

    private fun runUpdate(app: Context, info: Info) {
        val file = File(app.cacheDir, "on_groups_update.apk")
        try {
            if (file.exists()) file.delete()
            val auth = PanelClient.authHeader(app) ?: throw Exception("este celular não está conectado ao painel")
            val c = URL(PanelClient.url(app) + "/api/app/apk").openConnection() as HttpURLConnection
            val md = MessageDigest.getInstance("SHA-256")
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Authorization", auth)
                val code = c.responseCode
                if (code != 200) throw Exception("o painel respondeu $code")
                val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: info.size
                var got = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else 0
                            if (pct / 10 != lastPct / 10) {
                                lastPct = pct
                                setStatus(app, "Baixando a atualização: $pct%")
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            // confere o arquivo antes de entregar ao Android
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotEmpty() && !hex.equals(info.sha256, ignoreCase = true)) {
                throw Exception("o arquivo baixado não confere (SHA-256 diferente)")
            }
            if (info.size > 0 && file.length() != info.size) throw Exception("o tamanho do arquivo baixado não confere")
            val pkg = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (pkg == null || pkg.packageName != app.packageName) throw Exception("o arquivo baixado não é o On Groups")
            if (buildOf(pkg) != info.build) throw Exception("o arquivo baixado é de outro build (${buildOf(pkg)})")
            install(app, file)
        } catch (e: Exception) {
            try { file.delete() } catch (x: Exception) { }
            fail(app, "Não consegui atualizar: ${e.message ?: "erro"}")
        }
    }

    /** Entrega o APK ao instalador do Android; ele chama o UpdateReceiver (toque de confirmação, erro ou sucesso). */
    private fun install(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        // Android 12+: se as condições do sistema permitirem, atualiza sem pedir toque; senão o instalador pede a confirmação
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            FileInputStream(file).use { input ->
                session.openWrite("on_groups.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateReceiver::class.java).setAction(ACTION_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, id, intent, flags)
            setStatus(app, "Pronto: confirme a atualização na tela do Android")
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            try { session.abandon() } catch (x: Exception) { }
            throw e
        } finally {
            try { session.close() } catch (x: Exception) { }
            try { file.delete() } catch (x: Exception) { }
        }
    }

    // ---------- respostas do instalador (UpdateReceiver) ----------

    /** O Android pediu a confirmação do usuário: abre a tela dele (e deixa um aviso na barra para tocar, se o sistema não deixar abrir). */
    fun askToConfirm(ctx: Context, confirm: Intent?) {
        val app = ctx.applicationContext
        if (confirm == null) {
            fail(app, "O Android não mandou a tela de confirmação.")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ScreenGuard.wake(app, 30_000L)
        try { app.startActivity(confirm) } catch (e: Exception) { }
        try {
            val abrir = PendingIntent.getActivity(
                app, 77, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            ForegroundService.alertWithIntent(app, NOTIF_CONFIRM, "Atualização do On Groups", "Toque para confirmar a instalação.", abrir)
        } catch (e: Exception) { }
        setStatus(app, "Toque em Atualizar na tela do Android para concluir")
        RunLog.add(app, "⬆ Atualização: aguardando o toque de confirmação no celular")
    }

    fun onInstalled(ctx: Context) {
        busy = false
        RunLog.add(ctx, "⬆ Atualização instalada")
        setStatus(ctx, "Atualização instalada")
    }

    fun onInstallFailed(ctx: Context, message: String) {
        fail(ctx.applicationContext, "A instalação não foi concluída: $message")
    }
}

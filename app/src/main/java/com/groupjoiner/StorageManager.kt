package com.groupjoiner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object StorageManager {

    private const val PREFS_NAME = "ongroups_prefs"
    private const val KEY_HISTORY = "history"
    private const val KEY_PENDING_LINKS = "pending_links"
    private const val KEY_RESUME_LINKS = "resume_links"
    private const val KEY_RESUME_INDEX = "resume_index"
    private const val KEY_RESUME_PACKAGE = "resume_package"
    private const val KEY_RESUME_MESSAGE = "resume_message"

    // Salvar histórico
    fun saveHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        HistoryManager.entries.take(500).forEach { entry ->
            val obj = JSONObject()
            obj.put("number", entry.number)
            obj.put("link", entry.link)
            obj.put("status", entry.status)
            obj.put("timestamp", entry.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // Carregar histórico
    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            HistoryManager.entries.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                HistoryManager.entries.add(
                    HistoryEntry(
                        number = obj.getInt("number"),
                        link = obj.getString("link"),
                        status = obj.getString("status"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) { }
    }

    // Salvar links pendentes
    fun savePendingLinks(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        HistoryManager.pendingLinks.forEach { arr.put(it) }
        prefs.edit().putString(KEY_PENDING_LINKS, arr.toString()).apply()
    }

    // Carregar links pendentes
    fun loadPendingLinks(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_LINKS, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            HistoryManager.pendingLinks.clear()
            for (i in 0 until arr.length()) {
                HistoryManager.pendingLinks.add(arr.getString(i))
            }
        } catch (e: Exception) { }
    }

    // Salvar estado para retomar
    fun saveResumeState(context: Context, links: List<String>, index: Int, pkg: String, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        links.forEach { arr.put(it) }
        prefs.edit()
            .putString(KEY_RESUME_LINKS, arr.toString())
            .putInt(KEY_RESUME_INDEX, index)
            .putString(KEY_RESUME_PACKAGE, pkg)
            .putString(KEY_RESUME_MESSAGE, message)
            .apply()
    }

    // Carregar estado para retomar
    fun loadResumeState(context: Context): ResumeState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RESUME_LINKS, null) ?: return null
        val index = prefs.getInt(KEY_RESUME_INDEX, 0)
        val pkg = prefs.getString(KEY_RESUME_PACKAGE, "com.whatsapp") ?: "com.whatsapp"
        val message = prefs.getString(KEY_RESUME_MESSAGE, "") ?: ""
        return try {
            val arr = JSONArray(json)
            val links = mutableListOf<String>()
            for (i in 0 until arr.length()) links.add(arr.getString(i))
            if (links.isEmpty() || index >= links.size) null
            else ResumeState(links, index, pkg, message)
        } catch (e: Exception) { null }
    }

    // Limpar estado de retomada
    fun clearResumeState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_RESUME_LINKS)
            .remove(KEY_RESUME_INDEX)
            .remove(KEY_RESUME_PACKAGE)
            .remove(KEY_RESUME_MESSAGE)
            .apply()
    }

    // Limpar tudo
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    // Ajustes da fase 2 (digitação humana e pausa entre grupos).
    // Ficam em um arquivo à parte para "Limpar histórico" não apagar os ajustes.
    private const val SETTINGS_PREFS = "ongroups_settings"

    fun saveTypingSettings(context: Context, human: Boolean, wpm: Int, pauseMin: Int, pauseMax: Int) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("human_typing", human)
            .putInt("typing_wpm", wpm)
            .putInt("pause_min", pauseMin)
            .putInt("pause_max", pauseMax)
            .apply()
    }

    fun loadTypingSettings(context: Context): TypingSettings {
        val p = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        return TypingSettings(
            human = p.getBoolean("human_typing", true),
            wpm = p.getInt("typing_wpm", 70),
            pauseMin = p.getInt("pause_min", 8),
            pauseMax = p.getInt("pause_max", 20)
        )
    }

    // ---- Processo em andamento (fase, grupo, resultados): salvo a cada passo ----
    private const val KEY_RUN = "run_snapshot"

    fun saveRun(context: Context, s: RunSnapshot) {
        try {
            val o = JSONObject()
            o.put("active", s.active)
            o.put("phase", s.phase)
            o.put("links", JSONArray(s.links))
            o.put("statuses", JSONArray(s.statuses))
            o.put("index", s.index)
            o.put("stage", s.stage)
            o.put("groupSeq", s.groupSeq)
            o.put("attempt", s.attempt)
            o.put("message", s.message)
            o.put("pkg", s.pkg)
            o.put("progressAt", s.progressAt)
            o.put("expectedBy", s.expectedBy)
            o.put("pauseReason", s.pauseReason)
            o.put("startedAt", s.startedAt)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RUN, o.toString()).apply()
        } catch (e: Exception) { }
    }

    fun loadRun(context: Context): RunSnapshot? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RUN, null) ?: return null
        return try {
            val o = JSONObject(json)
            val la = o.getJSONArray("links")
            val links = List(la.length()) { la.getString(it) }
            val sa = o.getJSONArray("statuses")
            val st = List(sa.length()) { sa.getString(it) }
            RunSnapshot(
                active = o.optBoolean("active", false),
                phase = o.optString("phase", Phase.JOIN),
                links = links,
                statuses = if (st.size == links.size) st else List(links.size) { "" },
                index = o.optInt("index", 0),
                stage = o.optString("stage", "WAIT"),
                groupSeq = o.optLong("groupSeq", 0L),
                attempt = o.optInt("attempt", 1),
                message = o.optString("message", ""),
                pkg = o.optString("pkg", "com.whatsapp"),
                progressAt = o.optLong("progressAt", 0L),
                expectedBy = o.optLong("expectedBy", 0L),
                pauseReason = o.optString("pauseReason", ""),
                startedAt = o.optLong("startedAt", 0L)
            )
        } catch (e: Exception) { null }
    }

    fun clearRun(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_RUN).apply()
    }

    // ---- Envios: para não mandar a mesma mensagem duas vezes no mesmo grupo PELO MESMO WhatsApp ----
    // A chave inclui o WhatsApp (normal ou Business): são contas diferentes, então um envio feito pelo
    // WhatsApp errado não bloqueia o envio pelo certo. Registros antigos (só o link, sem WhatsApp) deixam de valer.
    private fun sentKey(pkg: String, link: String): String = "$pkg|$link"

    fun markSent(context: Context, link: String, pkg: String) {
        try {
            val p = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            val o = JSONObject(p.getString("sent_log", "{}") ?: "{}")
            val now = System.currentTimeMillis()
            val velhos = mutableListOf<String>()
            val iter = o.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                if (now - o.optLong(k, 0L) > 7L * 24 * 3_600_000L) velhos.add(k)
            }
            velhos.forEach { o.remove(it) }
            o.put(sentKey(pkg, link), now)
            p.edit().putString("sent_log", o.toString()).apply()
        } catch (e: Exception) { }
    }

    fun lastSent(context: Context, link: String, pkg: String): Long {
        return try {
            val p = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            JSONObject(p.getString("sent_log", "{}") ?: "{}").optLong(sentKey(pkg, link), 0L)
        } catch (e: Exception) { 0L }
    }

    // ---- Preferências simples (arquivo de ajustes, não apagado por "Limpar histórico") ----
    fun setPref(context: Context, key: String, value: String) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    fun getPref(context: Context, key: String, def: String = ""): String {
        return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getString(key, def) ?: def
    }
}

data class TypingSettings(val human: Boolean, val wpm: Int, val pauseMin: Int, val pauseMax: Int)

data class ResumeState(
    val links: MutableList<String>,
    val index: Int,
    val pkg: String,
    val message: String
)

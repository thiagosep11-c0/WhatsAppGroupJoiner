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
}

data class ResumeState(
    val links: MutableList<String>,
    val index: Int,
    val pkg: String,
    val message: String
)

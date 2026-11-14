package com.groupjoiner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Uma conversa lida na lista do WhatsApp: nome e quantas mensagens não lidas tem. */
data class ScanRow(val name: String, val unread: Int, val approx: Boolean)

/** Resultado de um escaneamento (só leitura): conversas, observações e um diagnóstico sem nomes. */
data class ScanResult(val rows: List<ScanRow>, val note: String, val diag: String, val finishedAt: Long)

/** Guarda o último escaneamento (fica salvo mesmo se o app for fechado). */
object ScanStore {
    private const val KEY = "scan_last"

    fun save(context: Context, r: ScanResult) {
        try {
            val arr = JSONArray()
            r.rows.forEach { row ->
                val o = JSONObject()
                o.put("n", row.name)
                o.put("u", row.unread)
                o.put("a", row.approx)
                arr.put(o)
            }
            val o = JSONObject()
            o.put("rows", arr)
            o.put("note", r.note)
            o.put("diag", r.diag)
            o.put("at", r.finishedAt)
            StorageManager.setPref(context, KEY, o.toString())
        } catch (e: Exception) { }
    }

    fun load(context: Context): ScanResult? {
        val json = StorageManager.getPref(context, KEY, "")
        if (json.isEmpty()) return null
        return try {
            val o = JSONObject(json)
            val a = o.getJSONArray("rows")
            val rows = List(a.length()) { i ->
                val x = a.getJSONObject(i)
                ScanRow(x.getString("n"), x.getInt("u"), x.optBoolean("a", false))
            }
            ScanResult(rows, o.optString("note", ""), o.optString("diag", ""), o.optLong("at", 0L))
        } catch (e: Exception) { null }
    }
}

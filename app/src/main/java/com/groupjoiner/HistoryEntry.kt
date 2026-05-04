package com.groupjoiner

import java.text.SimpleDateFormat
import java.util.*

data class HistoryEntry(
    val number: Int,
    val link: String,
    val status: String, // "joined", "requested", "invalid", "error"
    val timestamp: Long = System.currentTimeMillis()
) {
    val timeFormatted: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val shortLink: String
        get() = if (link.length > 40) "...${link.takeLast(37)}" else link

    val statusIcon: String
        get() = when (status) {
            "joined"    -> "✅"
            "requested" -> "⏳"
            "invalid"   -> "⚠️"
            else        -> "❌"
        }

    val statusText: String
        get() = when (status) {
            "joined"    -> "Entrou no grupo"
            "requested" -> "Aguardando pedido"
            "invalid"   -> "Inválido ou já membro"
            else        -> "Erro ao abrir"
        }
}

object HistoryManager {
    val entries = mutableListOf<HistoryEntry>()
    fun add(entry: HistoryEntry) { entries.add(0, entry) }
    fun clear() { entries.clear() }
}

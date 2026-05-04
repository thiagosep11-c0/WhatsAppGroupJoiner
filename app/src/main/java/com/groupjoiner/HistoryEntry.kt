package com.groupjoiner

import java.text.SimpleDateFormat
import java.util.*

data class HistoryEntry(
    val number: Int,
    val link: String,
    val status: String, // "success", "invalid", "error"
    val timestamp: Long = System.currentTimeMillis()
) {
    val timeFormatted: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val shortLink: String
        get() = if (link.length > 40) "...${link.takeLast(37)}" else link

    val statusIcon: String
        get() = when (status) {
            "success" -> "✅"
            "invalid" -> "⚠️"
            else -> "❌"
        }

    val statusText: String
        get() = when (status) {
            "success" -> "Entrou no grupo"
            "invalid" -> "Inválido ou já membro"
            else -> "Erro ao abrir"
        }
}

object HistoryManager {
    val entries = mutableListOf<HistoryEntry>()

    fun add(entry: HistoryEntry) {
        entries.add(0, entry) // mais recente primeiro
    }

    fun clear() {
        entries.clear()
    }
}

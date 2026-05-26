package com.groupjoiner

import java.text.SimpleDateFormat
import java.util.*

data class HistoryEntry(
    val number: Int,
    val link: String,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val timeFormatted: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val shortLink: String
        get() = if (link.length > 40) "...${link.takeLast(37)}" else link

    val statusIcon: String
        get() = when (status) {
            "joined"         -> "✅"
            "requested"      -> "⏳"
            "pending"        -> "🔒"
            "already_member" -> "👥"
            else             -> "❌"
        }

    val statusText: String
        get() = when (status) {
            "joined"         -> "Entrou no grupo"
            "requested"      -> "Pedido enviado"
            "pending"        -> "Aguardando liberação"
            "already_member" -> "Já é membro"
            else             -> "Inválido / Erro"
        }
}

object HistoryManager {
    val entries = mutableListOf<HistoryEntry>()
    val pendingLinks = mutableListOf<String>() // grupos com pedido enviado

    fun add(entry: HistoryEntry) {
        entries.add(0, entry)
        // Adiciona à lista de pendentes se for pedido enviado
        if (entry.status == "requested" && !pendingLinks.contains(entry.link)) {
            pendingLinks.add(entry.link)
        }
        // Remove dos pendentes se foi aprovado
        if (entry.status == "joined") {
            pendingLinks.remove(entry.link)
        }
    }

    fun clear() {
        entries.clear()
        pendingLinks.clear()
    }
}

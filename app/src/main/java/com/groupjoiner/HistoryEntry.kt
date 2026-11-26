package com.groupjoiner

import java.text.SimpleDateFormat
import java.util.*

/** Ícone e texto de cada status (usado no histórico). */
object StatusInfo {
    fun icon(status: String): String = when (status) {
        "joined"         -> "✅"
        "requested"      -> "⏳"
        "pending"        -> "🔒"
        "already_member" -> "👥"
        "message_sent"   -> "💬"
        "admin_only"     -> "👑"
        "left_group"     -> "🚪"
        "skipped_recent" -> "⏭"
        "error_env"      -> "⚠️"
        "error"          -> "⚠️"
        else             -> "❌"
    }

    fun text(status: String): String = when (status) {
        "joined"         -> "Entrou no grupo"
        "requested"      -> "Pedido enviado"
        "pending"        -> "Aguardando liberação"
        "already_member" -> "Já é membro"
        "message_sent"   -> "Mensagem enviada"
        "admin_only"     -> "Só admin pode enviar"
        "left_group"     -> "Saiu do grupo (admin)"
        "skipped_recent" -> "Pulado (enviado há pouco)"
        "error_env"      -> "Falha: tela/WhatsApp"
        "error"          -> "Falha (dá para tentar de novo)"
        "invalid"        -> "Link inválido"
        else             -> "Inválido / Erro"
    }
}

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
        get() = StatusInfo.icon(status)

    val statusText: String
        get() = StatusInfo.text(status)
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

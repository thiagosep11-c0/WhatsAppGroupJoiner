package com.groupjoiner

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class LinkItem(
    val number: Int,
    val url: String,
    var status: String = "pending",
    var countdown: String = ""
)

class LinkAdapter(private val items: MutableList<LinkItem>) :
    RecyclerView.Adapter<LinkAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNum: TextView = v.findViewById(R.id.tvLinkNumber)
        val tvUrl: TextView = v.findViewById(R.id.tvLinkUrl)
        val tvStatus: TextView = v.findViewById(R.id.tvLinkStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_link, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvNum.text = "#${item.number}"
        h.tvUrl.text = item.url.removePrefix("https://chat.whatsapp.com/")
            .let { if (it.length > 24) it.take(24) + "…" else it }

        when (item.status) {
            "current" -> {
                h.tvStatus.text = if (item.countdown.isNotEmpty()) "⏱ ${item.countdown}" else "🔗 Abrindo..."
                h.tvStatus.setTextColor(Color.parseColor("#FF9800"))
                h.itemView.setBackgroundColor(Color.parseColor("#FFF8E1"))
            }
            "joined" -> {
                h.tvStatus.text = "✅ Entrou"
                h.tvStatus.setTextColor(Color.parseColor("#075E54"))
                h.itemView.setBackgroundColor(Color.parseColor("#E8F5E9"))
            }
            "message_sent" -> {
                h.tvStatus.text = "💬 Msg enviada"
                h.tvStatus.setTextColor(Color.parseColor("#6A1B9A"))
                h.itemView.setBackgroundColor(Color.parseColor("#F3E5F5"))
            }
            "admin_only" -> {
                h.tvStatus.text = "👑 Admin - Sem envio"
                h.tvStatus.setTextColor(Color.parseColor("#E65100"))
                h.itemView.setBackgroundColor(Color.parseColor("#FFF3E0"))
            }
            "left_group" -> {
                h.tvStatus.text = "🚪 Admin - Saiu"
                h.tvStatus.setTextColor(Color.parseColor("#455A64"))
                h.itemView.setBackgroundColor(Color.parseColor("#ECEFF1"))
            }
            "requested" -> {
                h.tvStatus.text = if (item.countdown.isNotEmpty()) "⏱ ${item.countdown}" else "⏳ Aguard. aprovação"
                h.tvStatus.setTextColor(Color.parseColor("#1565C0"))
                h.itemView.setBackgroundColor(Color.parseColor("#E3F2FD"))
            }
            "pending" -> {
                h.tvStatus.text = "🔒 Aguard. liberação"
                h.tvStatus.setTextColor(Color.parseColor("#6A1B9A"))
                h.itemView.setBackgroundColor(Color.parseColor("#F3E5F5"))
            }
            "already_member" -> {
                h.tvStatus.text = "👥 Já é membro"
                h.tvStatus.setTextColor(Color.parseColor("#00695C"))
                h.itemView.setBackgroundColor(Color.parseColor("#E0F2F1"))
            }
            "invalid" -> {
                h.tvStatus.text = "❌ Inválido"
                h.tvStatus.setTextColor(Color.parseColor("#B71C1C"))
                h.itemView.setBackgroundColor(Color.parseColor("#FFEBEE"))
            }
            else -> {
                h.tvStatus.text = if (item.countdown.isNotEmpty()) "⏱ ${item.countdown}" else "⬜ Pendente"
                h.tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
                h.itemView.setBackgroundColor(Color.WHITE)
            }
        }
    }
}

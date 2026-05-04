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
    var status: String = "pending", // pending, current, joined, requested, invalid, error
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
                h.tvStatus.text = if (item.countdown.isNotEmpty()) "⏳ ${item.countdown}" else "🔗 Abrindo..."
                h.tvStatus.setTextColor(Color.parseColor("#FF9800"))
                h.itemView.setBackgroundColor(Color.parseColor("#FFF8E1"))
            }
            "joined" -> {
                h.tvStatus.text = "✅ Entrou"
                h.tvStatus.setTextColor(Color.parseColor("#075E54"))
                h.itemView.setBackgroundColor(Color.parseColor("#E8F5E9"))
            }
            "requested" -> {
                h.tvStatus.text = "⏳ Aguard."
                h.tvStatus.setTextColor(Color.parseColor("#1565C0"))
                h.itemView.setBackgroundColor(Color.parseColor("#E3F2FD"))
            }
            "invalid" -> {
                h.tvStatus.text = "⚠️ Inválido"
                h.tvStatus.setTextColor(Color.parseColor("#F57F17"))
                h.itemView.setBackgroundColor(Color.parseColor("#FFF9C4"))
            }
            "error" -> {
                h.tvStatus.text = "❌ Erro"
                h.tvStatus.setTextColor(Color.RED)
                h.itemView.setBackgroundColor(Color.parseColor("#FFEBEE"))
            }
            else -> {
                h.tvStatus.text = "⬜ Pendente"
                h.tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
                h.itemView.setBackgroundColor(Color.WHITE)
            }
        }
    }
}

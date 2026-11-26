package com.groupjoiner

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(private val entries: List<HistoryEntry>) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNumber: TextView = view.findViewById(R.id.tvNumber)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvLink: TextView = view.findViewById(R.id.tvLink)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvNumber.text = "#${entry.number}"
        holder.tvStatus.text = "${entry.statusIcon} ${entry.statusText}"
        holder.tvLink.text = entry.shortLink
        holder.tvTime.text = entry.timeFormatted

        val bgColor = when (entry.status) {
            "joined", "message_sent", "already_member" -> Color.parseColor("#E8F5E9")
            "requested", "pending", "skipped_recent" -> Color.parseColor("#E3F2FD")
            "admin_only", "left_group", "error_env", "error" -> Color.parseColor("#FFF3E0")
            else -> Color.parseColor("#FFEBEE")
        }
        holder.itemView.setBackgroundColor(bgColor)
    }

    override fun getItemCount() = entries.size
}

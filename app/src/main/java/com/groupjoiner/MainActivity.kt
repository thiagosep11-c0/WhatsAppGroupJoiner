package com.groupjoiner

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var editTextLinks: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPermission: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var radioNormal: RadioButton
    private lateinit var radioBusiness: RadioButton

    // Abas
    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var pageHome: View
    private lateinit var pageHistory: View

    // Histórico
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var tvHistoryCount: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var historyAdapter: HistoryAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var linkList = mutableListOf<String>()
    private var currentIndex = 0
    private var countdownRunnable: Runnable? = null

    private val intervals = listOf(30_000L, 2_000L, 60_000L)

    companion object {
        var instance: MainActivity? = null
        var selectedPackage = "com.whatsapp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)

        editTextLinks   = findViewById(R.id.editTextLinks)
        btnStart        = findViewById(R.id.btnStart)
        btnPermission   = findViewById(R.id.btnPermission)
        tvStatus        = findViewById(R.id.tvStatus)
        tvCountdown     = findViewById(R.id.tvCountdown)
        radioNormal     = findViewById(R.id.radioNormal)
        radioBusiness   = findViewById(R.id.radioBusiness)
        tabHome         = findViewById(R.id.tabHome)
        tabHistory      = findViewById(R.id.tabHistory)
        pageHome        = findViewById(R.id.pageHome)
        pageHistory     = findViewById(R.id.pageHistory)
        recyclerHistory = findViewById(R.id.recyclerHistory)
        tvHistoryCount  = findViewById(R.id.tvHistoryCount)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        // Setup RecyclerView
        historyAdapter = HistoryAdapter(HistoryManager.entries)
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = historyAdapter

        // Abas
        tabHome.setOnClickListener { showTab("home") }
        tabHistory.setOnClickListener {
            showTab("history")
            updateHistoryCount()
        }

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar histórico")
                .setMessage("Deseja apagar todo o histórico?")
                .setPositiveButton("Sim") { _, _ ->
                    HistoryManager.clear()
                    historyAdapter.notifyDataSetChanged()
                    updateHistoryCount()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão necessária")
                    .setMessage("Ative o 'Group Joiner Service' em Configurações > Acessibilidade.")
                    .setPositiveButton("Ir para Configurações") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@setOnClickListener
            }

            selectedPackage = if (radioBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

            val raw = editTextLinks.text.toString().trim()
            val prefixRegex = Regex("""^\d+\s*[-.):]?\s*""")

            linkList = raw.split("\n")
                .map { it.trim() }
                .map { line -> prefixRegex.replace(line, "").trim() }
                .filter { it.startsWith("http") }
                .toMutableList()

            if (linkList.isEmpty()) {
                Toast.makeText(this, "Cole pelo menos um link válido!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentIndex = 0
            btnStart.isEnabled = false
            tvCountdown.text = ""
            tvStatus.text = "Iniciando... ${linkList.size} links encontrados"
            openNextLink()
        }
    }

    private fun showTab(tab: String) {
        if (tab == "home") {
            pageHome.visibility = View.VISIBLE
            pageHistory.visibility = View.GONE
            tabHome.setTextColor(Color.parseColor("#075E54"))
            tabHome.setBackgroundColor(Color.parseColor("#E8F5E9"))
            tabHistory.setTextColor(Color.parseColor("#888888"))
            tabHistory.setBackgroundColor(Color.WHITE)
        } else {
            pageHome.visibility = View.GONE
            pageHistory.visibility = View.VISIBLE
            tabHistory.setTextColor(Color.parseColor("#075E54"))
            tabHistory.setBackgroundColor(Color.parseColor("#E8F5E9"))
            tabHome.setTextColor(Color.parseColor("#888888"))
            tabHome.setBackgroundColor(Color.WHITE)
        }
    }

    private fun updateHistoryCount() {
        val total = HistoryManager.entries.size
        val success = HistoryManager.entries.count { it.status == "success" }
        val invalid = HistoryManager.entries.count { it.status == "invalid" }
        tvHistoryCount.text = "$total entradas  ✅$success  ⚠️$invalid"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any { it.id.contains(packageName) }
    }

    fun openNextLink() {
        if (currentIndex >= linkList.size) {
            runOnUiThread {
                tvStatus.text = "✅ Concluído! ${linkList.size} grupos processados."
                tvCountdown.text = ""
                btnStart.isEnabled = true
            }
            return
        }

        val link = linkList[currentIndex]
        runOnUiThread {
            tvStatus.text = "🔗 Abrindo ${currentIndex + 1}/${linkList.size}..."
            tvCountdown.text = ""
        }

        GroupJoinerService.serviceInstance?.setWaitingForGroup(true)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setPackage(selectedPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            addToHistory(link, "error")
            runOnUiThread { tvStatus.text = "❌ Erro no link ${currentIndex + 1}. Pulando..." }
            GroupJoinerService.serviceInstance?.setWaitingForGroup(false)
            scheduleNext()
        }
    }

    fun onGroupProcessed(success: Boolean) {
        val link = if (currentIndex < linkList.size) linkList[currentIndex] else ""
        val status = if (success) "success" else "invalid"
        addToHistory(link, status)

        val msg = if (success)
            "✅ Entrou no grupo ${currentIndex + 1}/${linkList.size}"
        else
            "⚠️ Grupo ${currentIndex + 1} inválido ou já é membro"

        runOnUiThread { tvStatus.text = msg }
        scheduleNext()
    }

    private fun addToHistory(link: String, status: String) {
        val entry = HistoryEntry(
            number = currentIndex + 1,
            link = link,
            status = status
        )
        HistoryManager.add(entry)
        runOnUiThread {
            historyAdapter.notifyItemInserted(0)
            recyclerHistory.scrollToPosition(0)
            updateHistoryCount()
        }
    }

    private fun scheduleNext() {
        val delay = intervals[currentIndex % intervals.size]
        currentIndex++
        startCountdown(delay)
        handler.postDelayed({ openNextLink() }, delay)
    }

    private fun startCountdown(totalMs: Long) {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        val startTime = System.currentTimeMillis()

        fun tick() {
            val remaining = totalMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) {
                runOnUiThread { tvCountdown.text = "" }
                return
            }
            val secs = (remaining / 1000).toInt()
            val timeStr = if (secs >= 60) String.format("%d:%02d", secs / 60, secs % 60) else "${secs}s"
            runOnUiThread { tvCountdown.text = "⏱ Próximo grupo em: $timeStr" }
            val next = Runnable { tick() }
            countdownRunnable = next
            handler.postDelayed(next, 500)
        }

        tick()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}

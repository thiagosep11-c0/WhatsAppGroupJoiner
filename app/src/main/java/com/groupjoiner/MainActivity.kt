package com.groupjoiner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // Views - Home
    private lateinit var editTextLinks: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnRetry: Button
    private lateinit var btnPermission: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var radioNormal: RadioButton
    private lateinit var radioBusiness: RadioButton

    // Views - Settings
    private lateinit var etInterval1: EditText
    private lateinit var etInterval2: EditText
    private lateinit var etInterval3: EditText
    private lateinit var etMaxGroups: EditText
    private lateinit var switchDarkMode: Switch
    private lateinit var switchNotifications: Switch

    // Abas
    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabSettings: TextView
    private lateinit var pageHome: View
    private lateinit var pageHistory: View
    private lateinit var pageSettings: View

    // Histórico
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var tvHistoryCount: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var historyAdapter: HistoryAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var linkList = mutableListOf<String>()
    private var failedLinks = mutableListOf<Pair<Int, String>>() // index, link
    private var currentIndex = 0
    private var countdownRunnable: Runnable? = null
    private var isPaused = false
    private var isRunning = false
    private var darkMode = false

    private var interval1 = 30_000L
    private var interval2 = 2_000L
    private var interval3 = 60_000L
    private var maxGroups = 0 // 0 = sem limite

    companion object {
        var instance: MainActivity? = null
        var selectedPackage = "com.whatsapp"
        const val CHANNEL_ID = "group_joiner_channel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)
        createNotificationChannel()
        bindViews()
        setupTabs()
        setupButtons()
        setupSettings()
    }

    private fun bindViews() {
        editTextLinks   = findViewById(R.id.editTextLinks)
        btnStart        = findViewById(R.id.btnStart)
        btnPause        = findViewById(R.id.btnPause)
        btnRetry        = findViewById(R.id.btnRetry)
        btnPermission   = findViewById(R.id.btnPermission)
        tvStatus        = findViewById(R.id.tvStatus)
        tvCountdown     = findViewById(R.id.tvCountdown)
        progressBar     = findViewById(R.id.progressBar)
        tvProgress      = findViewById(R.id.tvProgress)
        radioNormal     = findViewById(R.id.radioNormal)
        radioBusiness   = findViewById(R.id.radioBusiness)
        tabHome         = findViewById(R.id.tabHome)
        tabHistory      = findViewById(R.id.tabHistory)
        tabSettings     = findViewById(R.id.tabSettings)
        pageHome        = findViewById(R.id.pageHome)
        pageHistory     = findViewById(R.id.pageHistory)
        pageSettings    = findViewById(R.id.pageSettings)
        recyclerHistory = findViewById(R.id.recyclerHistory)
        tvHistoryCount  = findViewById(R.id.tvHistoryCount)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        etInterval1     = findViewById(R.id.etInterval1)
        etInterval2     = findViewById(R.id.etInterval2)
        etInterval3     = findViewById(R.id.etInterval3)
        etMaxGroups     = findViewById(R.id.etMaxGroups)
        switchDarkMode  = findViewById(R.id.switchDarkMode)
        switchNotifications = findViewById(R.id.switchNotifications)

        historyAdapter = HistoryAdapter(HistoryManager.entries)
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = historyAdapter

        btnPause.visibility = View.GONE
        btnRetry.visibility = View.GONE
    }

    private fun setupTabs() {
        tabHome.setOnClickListener { showTab("home") }
        tabHistory.setOnClickListener { showTab("history"); updateHistoryCount() }
        tabSettings.setOnClickListener { showTab("settings") }
    }

    private fun setupButtons() {
        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { startProcessing(linkList = null) }

        btnPause.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                countdownRunnable?.let { handler.removeCallbacks(it) }
                handler.removeCallbacksAndMessages(null)
                btnPause.text = "▶ Continuar"
                btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                tvStatus.text = "⏸ Pausado no grupo ${currentIndex}/${linkList.size}"
                tvCountdown.text = ""
            } else {
                btnPause.text = "⏸ Pausar"
                btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                openNextLink()
            }
        }

        btnRetry.setOnClickListener {
            if (failedLinks.isEmpty()) {
                Toast.makeText(this, "Nenhuma falha para retentar!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Retentar falhas")
                .setMessage("Retentar ${failedLinks.size} grupos que falharam?")
                .setPositiveButton("Sim") { _, _ ->
                    linkList = failedLinks.map { it.second }.toMutableList()
                    failedLinks.clear()
                    startProcessing(linkList = linkList)
                }
                .setNegativeButton("Cancelar", null)
                .show()
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
    }

    private fun setupSettings() {
        etInterval1.setText("30")
        etInterval2.setText("2")
        etInterval3.setText("60")
        etMaxGroups.setText("0")

        switchDarkMode.setOnCheckedChangeListener { _, checked ->
            darkMode = checked
            applyTheme()
        }
    }

    private fun applyTheme() {
        val bg = if (darkMode) Color.parseColor("#121212") else Color.WHITE
        val text = if (darkMode) Color.WHITE else Color.parseColor("#333333")
        val header = if (darkMode) Color.parseColor("#1E1E1E") else Color.parseColor("#075E54")

        findViewById<View>(R.id.rootLayout).setBackgroundColor(bg)
        tvStatus.setTextColor(text)
        tvCountdown.setTextColor(if (darkMode) Color.parseColor("#25D366") else Color.parseColor("#075E54"))
    }

    private fun startProcessing(linkList: MutableList<String>?) {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage("Ative o 'Group Joiner Service' em Configurações > Acessibilidade.")
                .setPositiveButton("Ir para Configurações") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        // Ler intervalos das configurações
        interval1 = (etInterval1.text.toString().toLongOrNull() ?: 30) * 1000L
        interval2 = (etInterval2.text.toString().toLongOrNull() ?: 2) * 1000L
        interval3 = (etInterval3.text.toString().toLongOrNull() ?: 60) * 1000L
        maxGroups = etMaxGroups.text.toString().toIntOrNull() ?: 0

        selectedPackage = if (radioBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

        if (linkList == null) {
            val raw = editTextLinks.text.toString().trim()
            val prefixRegex = Regex("""^\d+\s*[-.):]?\s*""")
            this.linkList = raw.split("\n")
                .map { it.trim() }
                .map { line -> prefixRegex.replace(line, "").trim() }
                .filter { it.startsWith("http") }
                .let { if (maxGroups > 0) it.take(maxGroups) else it }
                .toMutableList()
        } else {
            this.linkList = linkList
        }

        if (this.linkList.isEmpty()) {
            Toast.makeText(this, "Cole pelo menos um link válido!", Toast.LENGTH_SHORT).show()
            return
        }

        currentIndex = 0
        isPaused = false
        isRunning = true
        failedLinks.clear()
        btnStart.isEnabled = false
        btnPause.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        btnPause.text = "⏸ Pausar"
        progressBar.max = this.linkList.size
        progressBar.progress = 0
        tvProgress.text = "0/${this.linkList.size}"
        tvCountdown.text = ""
        tvStatus.text = "Iniciando... ${this.linkList.size} links"
        openNextLink()
    }

    private fun showTab(tab: String) {
        val tabs = mapOf("home" to tabHome, "history" to tabHistory, "settings" to tabSettings)
        val pages = mapOf("home" to pageHome, "history" to pageHistory, "settings" to pageSettings)
        tabs.forEach { (key, view) ->
            if (key == tab) {
                view.setTextColor(Color.parseColor("#075E54"))
                view.setBackgroundColor(Color.parseColor("#E8F5E9"))
            } else {
                view.setTextColor(Color.parseColor("#888888"))
                view.setBackgroundColor(Color.WHITE)
            }
        }
        pages.forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
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
        if (isPaused) return

        if (currentIndex >= linkList.size) {
            runOnUiThread {
                val success = HistoryManager.entries.count { it.status == "success" }
                val failed = HistoryManager.entries.count { it.status != "success" }
                tvStatus.text = "✅ Concluído! ${linkList.size} grupos.\n✅ $success entrou  ⚠️ $failed falhou"
                tvCountdown.text = ""
                progressBar.progress = linkList.size
                tvProgress.text = "${linkList.size}/${linkList.size}"
                btnStart.isEnabled = true
                btnPause.visibility = View.GONE
                if (failedLinks.isNotEmpty()) btnRetry.visibility = View.VISIBLE
                isRunning = false
                if (switchNotifications.isChecked) sendFinishedNotification(success, failed)
            }
            return
        }

        val link = linkList[currentIndex]
        runOnUiThread {
            tvStatus.text = "🔗 Abrindo ${currentIndex + 1}/${linkList.size}..."
            tvCountdown.text = ""
            progressBar.progress = currentIndex
            tvProgress.text = "${currentIndex}/${linkList.size}"
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
            failedLinks.add(Pair(currentIndex, link))
            runOnUiThread { tvStatus.text = "❌ Erro no link ${currentIndex + 1}." }
            GroupJoinerService.serviceInstance?.setWaitingForGroup(false)
            scheduleNext()
        }
    }

    fun onGroupProcessed(success: Boolean) {
        val link = if (currentIndex < linkList.size) linkList[currentIndex] else ""
        val status = if (success) "success" else "invalid"
        if (!success) failedLinks.add(Pair(currentIndex, link))
        addToHistory(link, status)

        val msg = if (success)
            "✅ Entrou no grupo ${currentIndex + 1}/${linkList.size}"
        else
            "⚠️ Grupo ${currentIndex + 1} inválido ou já é membro"

        runOnUiThread { tvStatus.text = msg }
        scheduleNext()
    }

    private fun addToHistory(link: String, status: String) {
        val entry = HistoryEntry(number = currentIndex + 1, link = link, status = status)
        HistoryManager.add(entry)
        runOnUiThread {
            historyAdapter.notifyItemInserted(0)
            updateHistoryCount()
        }
    }

    private fun scheduleNext() {
        val intervals = listOf(interval1, interval2, interval3)
        val delay = intervals[currentIndex % intervals.size]
        currentIndex++
        runOnUiThread {
            progressBar.progress = currentIndex
            tvProgress.text = "${currentIndex}/${linkList.size}"
        }
        startCountdown(delay)
        handler.postDelayed({ openNextLink() }, delay)
    }

    private fun startCountdown(totalMs: Long) {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        val startTime = System.currentTimeMillis()
        fun tick() {
            val remaining = totalMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) { runOnUiThread { tvCountdown.text = "" }; return }
            val secs = (remaining / 1000).toInt()
            val timeStr = if (secs >= 60) String.format("%d:%02d", secs / 60, secs % 60) else "${secs}s"
            runOnUiThread { tvCountdown.text = "⏱ Próximo grupo em: $timeStr" }
            val next = Runnable { tick() }
            countdownRunnable = next
            handler.postDelayed(next, 500)
        }
        tick()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Group Joiner", NotificationManager.IMPORTANCE_DEFAULT)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendFinishedNotification(success: Int, failed: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Group Joiner — Concluído!")
            .setContentText("✅ $success entrou  ⚠️ $failed falhou")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}

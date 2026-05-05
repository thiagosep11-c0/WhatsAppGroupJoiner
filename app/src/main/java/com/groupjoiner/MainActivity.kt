package com.groupjoiner

import android.app.NotificationChannel
import android.os.PowerManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
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
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnRetry: Button
    private lateinit var btnPermission: Button
    private lateinit var btnEditLinks: Button
    private lateinit var tvLinkCount: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLinkListTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var radioNormal: RadioButton
    private lateinit var radioBusiness: RadioButton

    private lateinit var editTextLinks: EditText
    private lateinit var btnCloseEdit: Button
    private lateinit var btnPasteLinks: Button
    private lateinit var btnClearLinks: Button
    private lateinit var pageEditLinks: View

    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabSettings: TextView
    private lateinit var pageHome: View
    private lateinit var pageHistory: View
    private lateinit var pageSettings: View

    private lateinit var recyclerHistory: RecyclerView
    private lateinit var recyclerLinks: RecyclerView
    private lateinit var tvHistoryCount: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var linkAdapter: LinkAdapter
    private val linkItems = mutableListOf<LinkItem>()

    private lateinit var etMaxGroups: EditText
    private lateinit var etInterval1: EditText
    private lateinit var etInterval2: EditText
    private lateinit var etInterval3: EditText
    private lateinit var switchDarkMode: Switch
    private lateinit var switchNotifications: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var linkList = mutableListOf<String>()
    private var failedLinks = mutableListOf<Pair<Int, String>>()
    private var currentIndex = 0
    private var countdownRunnable: Runnable? = null
    private var isPaused = false
    private var isRunning = false
    private var processed = false
    private var maxGroups = 0
    private val defaultIntervals = listOf(5_000L, 30_000L, 40_000L, 50_000L, 60_000L)

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
    }

    private fun bindViews() {
        btnStart        = findViewById(R.id.btnStart)
        btnPause        = findViewById(R.id.btnPause)
        btnRetry        = findViewById(R.id.btnRetry)
        btnPermission   = findViewById(R.id.btnPermission)
        btnEditLinks    = findViewById(R.id.btnEditLinks)
        tvLinkCount     = findViewById(R.id.tvLinkCount)
        tvCountdown     = findViewById(R.id.tvCountdown)
        tvProgress      = findViewById(R.id.tvProgress)
        tvLinkListTitle = findViewById(R.id.tvLinkListTitle)
        progressBar     = findViewById(R.id.progressBar)
        radioNormal     = findViewById(R.id.radioNormal)
        radioBusiness   = findViewById(R.id.radioBusiness)
        editTextLinks   = findViewById(R.id.editTextLinks)
        btnCloseEdit    = findViewById(R.id.btnCloseEdit)
        btnPasteLinks   = findViewById(R.id.btnPasteLinks)
        btnClearLinks   = findViewById(R.id.btnClearLinks)
        pageEditLinks   = findViewById(R.id.pageEditLinks)
        tabHome         = findViewById(R.id.tabHome)
        tabHistory      = findViewById(R.id.tabHistory)
        tabSettings     = findViewById(R.id.tabSettings)
        pageHome        = findViewById(R.id.pageHome)
        pageHistory     = findViewById(R.id.pageHistory)
        pageSettings    = findViewById(R.id.pageSettings)
        recyclerLinks   = findViewById(R.id.recyclerLinks)
        recyclerHistory = findViewById(R.id.recyclerHistory)
        tvHistoryCount  = findViewById(R.id.tvHistoryCount)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        etMaxGroups     = findViewById(R.id.etMaxGroups)
        etInterval1     = findViewById(R.id.etInterval1)
        etInterval2     = findViewById(R.id.etInterval2)
        etInterval3     = findViewById(R.id.etInterval3)
        switchDarkMode  = findViewById(R.id.switchDarkMode)
        switchNotifications = findViewById(R.id.switchNotifications)

        linkAdapter = LinkAdapter(linkItems)
        recyclerLinks.layoutManager = LinearLayoutManager(this)
        recyclerLinks.adapter = linkAdapter

        historyAdapter = HistoryAdapter(HistoryManager.entries)
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = historyAdapter

        btnPause.visibility = View.GONE
        btnRetry.visibility = View.GONE
        etMaxGroups.setText("0")
    }

    private fun setupTabs() {
        tabHome.setOnClickListener { showTab("home") }
        tabHistory.setOnClickListener { showTab("history"); updateHistoryCount() }
        tabSettings.setOnClickListener { showTab("settings") }
    }

    private fun setupButtons() {
        btnEditLinks.setOnClickListener {
            pageHome.visibility = View.GONE
            pageEditLinks.visibility = View.VISIBLE
            editTextLinks.requestFocus()
        }

        btnCloseEdit.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editTextLinks.windowToken, 0)
            pageEditLinks.visibility = View.GONE
            pageHome.visibility = View.VISIBLE
            updateLinkCount()
        }

        btnPasteLinks.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                val current = editTextLinks.text.toString()
                editTextLinks.setText(if (current.isBlank()) text else "$current\n$text")
                editTextLinks.setSelection(editTextLinks.text.length)
                Toast.makeText(this, "Links colados!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Area de transferencia vazia", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLinks.setOnClickListener {
            editTextLinks.setText("")
            tvLinkCount.text = "0 links"
        }

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { startProcessing() }

        btnPause.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                countdownRunnable?.let { handler.removeCallbacks(it) }
                handler.removeCallbacksAndMessages(null)
                btnPause.text = "▶ Continuar"
                btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                tvCountdown.text = "⏸ Pausado"
            } else {
                btnPause.text = "⏸ Pausar"
                btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                tvCountdown.text = ""
                openNextLink()
            }
        }

        btnRetry.setOnClickListener {
            if (failedLinks.isEmpty()) { Toast.makeText(this, "Nenhuma falha!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Retentar ${failedLinks.size} falhas?")
                .setPositiveButton("Sim") { _, _ ->
                    linkList = failedLinks.map { it.second }.toMutableList()
                    failedLinks.clear()
                    startProcessing(linkList)
                }.setNegativeButton("Cancelar", null).show()
        }

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Limpar historico?")
                .setPositiveButton("Sim") { _, _ ->
                    HistoryManager.clear()
                    historyAdapter.notifyDataSetChanged()
                    updateHistoryCount()
                }.setNegativeButton("Cancelar", null).show()
        }

        switchDarkMode.setOnCheckedChangeListener { _, checked -> applyTheme(checked) }
    }

    private fun updateLinkCount() {
        val prefixRegex = Regex("""^\d+\s*[-.):]?\s*""")
        val count = editTextLinks.text.toString().trim().split("\n")
            .map { prefixRegex.replace(it.trim(), "").trim() }
            .count { it.startsWith("http") }
        tvLinkCount.text = "$count links"
    }

    private fun startProcessing(existingList: MutableList<String>? = null) {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this).setTitle("Permissao necessaria")
                .setMessage("Ative o 'Group Joiner Service' em Configuracoes > Acessibilidade.")
                .setPositiveButton("Configuracoes") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Cancelar", null).show()
            return
        }

        maxGroups = etMaxGroups.text.toString().toIntOrNull() ?: 0
        selectedPackage = if (radioBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

        if (existingList == null) {
            val prefixRegex = Regex("""^\d+\s*[-.):]?\s*""")
            linkList = editTextLinks.text.toString().trim().split("\n")
                .map { prefixRegex.replace(it.trim(), "").trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .let { if (maxGroups > 0) it.take(maxGroups) else it }
                .toMutableList()
        } else {
            linkList = existingList
        }

        if (linkList.isEmpty()) {
            Toast.makeText(this, "Nenhum link valido! Use o botao Editar Links.", Toast.LENGTH_LONG).show()
            return
        }

        linkItems.clear()
        linkList.forEachIndexed { i, url -> linkItems.add(LinkItem(i + 1, url)) }
        linkAdapter.notifyDataSetChanged()
        tvLinkListTitle.text = "Grupos (${linkList.size})"
        tvLinkCount.text = "${linkList.size} links"

        currentIndex = 0
        processed = false
        isPaused = false
        isRunning = true
        failedLinks.clear()
        btnStart.isEnabled = false
        btnPause.visibility = View.VISIBLE
        // Manter tela acesa durante o processo
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.release()
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "OnGroups:WakeLock")
        wakeLock?.acquire(60 * 60 * 1000L) // máximo 1 hora
        btnRetry.visibility = View.GONE
        btnPause.text = "⏸ Pausar"
        progressBar.max = linkList.size
        progressBar.progress = 0
        tvProgress.text = "0/${linkList.size}"
        tvCountdown.text = ""
        openNextLink()
    }

    fun openNextLink() {
        if (isPaused) return
        processed = false

        if (currentIndex >= linkList.size) {
            val joined = linkItems.count { it.status == "joined" }
            val requested = linkItems.count { it.status == "requested" }
            val pendingC = linkItems.count { it.status == "pending" }
            val failed = linkItems.count { it.status == "invalid" }
            runOnUiThread {
                tvCountdown.text = "✅ Concluido!"
                tvProgress.text = "${linkList.size}/${linkList.size}"
                progressBar.progress = linkList.size
                btnStart.isEnabled = true
                btnPause.visibility = View.GONE
                if (failedLinks.isNotEmpty()) btnRetry.visibility = View.VISIBLE
                isRunning = false
                wakeLock?.release()
                wakeLock = null
                if (switchNotifications.isChecked) sendFinishedNotification(joined, requested + pendingC, failed)
            }
            return
        }

        val link = linkList[currentIndex]
        runOnUiThread {
            if (currentIndex < linkItems.size) {
                linkItems[currentIndex].status = "current"
                linkItems[currentIndex].countdown = ""
                linkAdapter.notifyItemChanged(currentIndex)
                recyclerLinks.scrollToPosition(currentIndex)
            }
            progressBar.progress = currentIndex
            tvProgress.text = "${currentIndex + 1}/${linkList.size}"
            tvCountdown.text = "🔗 Abrindo..."
        }

        GroupJoinerService.serviceInstance?.setWaiting(true)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setPackage(selectedPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            updateLinkStatus(currentIndex, "invalid")
            failedLinks.add(Pair(currentIndex, link))
            GroupJoinerService.serviceInstance?.setWaiting(false)
            scheduleNext()
        }
    }

    fun onGroupProcessed(status: String) {
        if (processed) return
        processed = true

        val link = if (currentIndex < linkList.size) linkList[currentIndex] else ""
        if (status == "invalid") failedLinks.add(Pair(currentIndex, link))
        updateLinkStatus(currentIndex, status)
        addToHistory(link, status)
        scheduleNext()
    }

    private fun updateLinkStatus(index: Int, status: String) {
        runOnUiThread {
            if (index < linkItems.size) {
                linkItems[index].status = status
                linkItems[index].countdown = ""
                linkAdapter.notifyItemChanged(index)
            }
        }
    }

    private fun scheduleNext() {
        val delay = defaultIntervals[currentIndex % defaultIntervals.size]
        val nextIdx = currentIndex + 1
        currentIndex++
        runOnUiThread {
            progressBar.progress = currentIndex
            tvProgress.text = "$currentIndex/${linkList.size}"
        }
        startCountdown(delay, nextIdx) { openNextLink() }
    }

    private fun startCountdown(totalMs: Long, nextIdx: Int, onFinish: () -> Unit) {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        val startTime = System.currentTimeMillis()
        fun tick() {
            if (isPaused) return
            val remaining = totalMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) {
                runOnUiThread {
                    tvCountdown.text = ""
                    if (nextIdx < linkItems.size) { linkItems[nextIdx].countdown = ""; linkAdapter.notifyItemChanged(nextIdx) }
                }
                onFinish()
                return
            }
            val secs = (remaining / 1000).toInt()
            val timeStr = if (secs >= 60) String.format("%d:%02d", secs / 60, secs % 60) else "${secs}s"
            runOnUiThread {
                tvCountdown.text = "⏱ $timeStr"
                if (nextIdx < linkItems.size) { linkItems[nextIdx].countdown = timeStr; linkAdapter.notifyItemChanged(nextIdx) }
            }
            val next = Runnable { tick() }
            countdownRunnable = next
            handler.postDelayed(next, 500)
        }
        tick()
    }

    private fun addToHistory(link: String, status: String) {
        val entry = HistoryEntry(number = currentIndex, link = link, status = status)
        HistoryManager.add(entry)
        runOnUiThread { historyAdapter.notifyItemInserted(0); updateHistoryCount() }
    }

    private fun updateHistoryCount() {
        val total = HistoryManager.entries.size
        val joined = HistoryManager.entries.count { it.status == "joined" }
        val requested = HistoryManager.entries.count { it.status == "requested" }
        val pending = HistoryManager.entries.count { it.status == "pending" }
        val invalid = HistoryManager.entries.count { it.status == "invalid" }
        tvHistoryCount.text = "$total  ✅$joined  ⏳$requested  🔒$pending  ❌$invalid"
    }

    private fun showTab(tab: String) {
        listOf("home" to pageHome, "history" to pageHistory, "settings" to pageSettings).forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        listOf("home" to tabHome, "history" to tabHistory, "settings" to tabSettings).forEach { (key, tv) ->
            if (key == tab) { tv.setTextColor(Color.parseColor("#075E54")); tv.setBackgroundColor(Color.parseColor("#E8F5E9")) }
            else { tv.setTextColor(Color.parseColor("#888888")); tv.setBackgroundColor(Color.WHITE) }
        }
    }

    private fun applyTheme(dark: Boolean) {
        findViewById<View>(R.id.rootLayout).setBackgroundColor(if (dark) Color.parseColor("#121212") else Color.WHITE)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains(packageName) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "On Groups", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendFinishedNotification(joined: Int, waiting: Int, failed: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("On Groups — Concluido!")
            .setContentText("✅ $joined entrou  ⏳ $waiting aguardando  ❌ $failed falhou")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        nm.notify(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wakeLock?.release()
        wakeLock = null
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}

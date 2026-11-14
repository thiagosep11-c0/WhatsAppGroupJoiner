package com.groupjoiner

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // --- Tela inicial ---
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var runBanner: View
    private lateinit var tvBannerStatus: TextView
    private lateinit var btnBannerStop: Button
    private lateinit var btnRetry: Button
    private lateinit var btnPermission: Button
    private lateinit var btnEditLinks: Button
    private lateinit var tvLinkCount: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvRunStatus: TextView
    private lateinit var tvLinkListTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var radioNormal: RadioButton
    private lateinit var radioBusiness: RadioButton

    private lateinit var editTextLinks: EditText
    private lateinit var btnCloseEdit: Button
    private lateinit var btnPasteLinks: Button
    private lateinit var btnClearLinks: Button
    private lateinit var pageEditLinks: View

    // --- Abas ---
    private lateinit var tabHome: TextView
    private lateinit var tabMessages: TextView
    private lateinit var tabAuto: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabLog: TextView
    private lateinit var tabSettings: TextView
    private lateinit var pageHome: View
    private lateinit var pageMessages: View
    private lateinit var pageAuto: View
    private lateinit var pageHistory: View
    private lateinit var pageLog: View
    private lateinit var pageSettings: View

    // --- Aba Mensagens ---
    private lateinit var editMsgLinks: EditText
    private lateinit var etMsgOnly: EditText
    private lateinit var tvMsgLinkCount: TextView
    private lateinit var btnMsgPaste: Button
    private lateinit var btnMsgFromHistory: Button
    private lateinit var btnMsgClear: Button
    private lateinit var btnMsgStart: Button
    private lateinit var etMaxUnread: EditText
    private lateinit var btnScan: Button
    private lateinit var tvScanResult: TextView
    private lateinit var btnScanCopy: Button
    private var scanInProgress = false
    private lateinit var radioAutoNormal: RadioButton
    private lateinit var radioAutoBusiness: RadioButton
    private lateinit var radioAutoBoth: RadioButton
    private val scanQueue = mutableListOf<String>()
    private var scanSkipNote = ""
    private lateinit var radioMsgNormal: RadioButton
    private lateinit var radioMsgBusiness: RadioButton
    private var scanSafetyRunnable: Runnable? = null

    // --- Aba Registro ---
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnLogRefresh: Button
    private lateinit var btnLogCopy: Button
    private lateinit var btnLogClear: Button

    // --- Histórico e lista ---
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var recyclerLinks: RecyclerView
    private lateinit var tvHistoryCount: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var linkAdapter: LinkAdapter
    private val linkItems = mutableListOf<LinkItem>()

    // --- Config ---
    private lateinit var etMaxGroups: EditText
    private lateinit var etInterval1: EditText
    private lateinit var etInterval2: EditText
    private lateinit var etInterval3: EditText
    private lateinit var switchDarkMode: Switch
    private lateinit var switchNotifications: Switch
    private lateinit var switchSendMessage: Switch
    private lateinit var etMessage: EditText
    private lateinit var switchAutoCheck: Switch
    private lateinit var etCheckInterval: EditText
    private lateinit var btnCheckNow: Button
    private lateinit var switchHumanTyping: Switch
    private lateinit var etTypingWpm: EditText
    private lateinit var etPauseMin: EditText
    private lateinit var etPauseMax: EditText
    private lateinit var etCooldownHours: EditText
    private lateinit var btnBattery: Button

    private var phase2Links = mutableListOf<String>()
    private var isPhase2 = false
    private var isCheckingApproval = false
    private var isLeavingGroup = false
    private var approvalCheckRunnable: Runnable? = null
    private var autoCheckIntervalMs = 30 * 60 * 1000L // 30 minutos padrão

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var linkList = mutableListOf<String>()
    private var failedLinks = mutableListOf<Pair<Int, String>>()
    private var currentIndex = 0
    private var countdownRunnable: Runnable? = null
    private var screenWaitRunnable: Runnable? = null
    private var chainRunnable: Runnable? = null
    private var isPaused = false
    private var isRunning = false
    private var processed = false
    private var maxGroups = 0
    private val defaultIntervals = listOf(5_000L, 30_000L, 40_000L, 50_000L, 60_000L)

    // --- Estado do processo (salvo a cada passo em um RunSnapshot) ---
    private var phase = Phase.JOIN
    private var lastRunPhase = Phase.JOIN
    private var lastRunMessage = ""
    private var runMessage = ""
    private var runActive = false
    private var stage = "WAIT"          // GROUP = grupo em andamento; WAIT = esperando o próximo
    private var attempt = 1
    private var envFailures = 0
    private var screenWaits = 0
    private var pauseReason = ""
    private var runStartedAt = 0L

    companion object {
        var instance: MainActivity? = null
        var selectedPackage = "com.whatsapp"
        const val CHANNEL_ID = "group_joiner_channel"
        const val EXTRA_STATUS = "grp_status"
        const val EXTRA_SEQ = "grp_seq"
        const val EXTRA_RECOVER = "recover"
        const val EXTRA_SCAN_DONE = "scan_done"
        const val MAX_ATTEMPTS = 3
        const val STALE_MS = 15 * 60 * 1000L
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)
        createNotificationChannel()
        bindViews()
        loadTypingSettings()
        loadSavedSettings()
        setupTabs()
        setupButtons()
        showTab("home")
        // Carregar histórico e pendentes salvos
        StorageManager.loadHistory(this)
        StorageManager.loadPendingLinks(this)
        historyAdapter.notifyDataSetChanged()
        updateHistoryCount()
        askNotificationPermission()
        renderIdleStatus()
        // Se havia um processo em andamento (ou o sistema reabriu o app com um resultado), continua de onde parou
        handleIncoming(intent)
        updateRunBanner()
        handler.postDelayed({ maybeAskBatteryOptimization() }, 1500L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
        updateRunBanner()
    }

    override fun onResume() {
        super.onResume()
        if (!runActive) renderIdleStatus()
        updateRunBanner()
    }

    override fun onPause() {
        super.onPause()
        // Salvar tudo ao minimizar
        saveTypingSettings()
        saveSavedSettings()
        StorageManager.saveHistory(this)
        StorageManager.savePendingLinks(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        // Salvar estado antes de fechar
        StorageManager.saveHistory(this)
        StorageManager.savePendingLinks(this)
        if (runActive) {
            RunLog.add(this, "ℹ A tela do app foi fechada (sistema ou usuário). O vigia reabre sozinho e o processo continua de onde parou")
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (!runActive) ForegroundService.stop(this)
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }

    // =====================================================================
    // Montagem da tela
    // =====================================================================

    private fun bindViews() {
        btnStart        = findViewById(R.id.btnStart)
        btnPause        = findViewById(R.id.btnPause)
        runBanner       = findViewById(R.id.runBanner)
        tvBannerStatus  = findViewById(R.id.tvBannerStatus)
        btnBannerStop   = findViewById(R.id.btnBannerStop)
        btnRetry        = findViewById(R.id.btnRetry)
        btnPermission   = findViewById(R.id.btnPermission)
        btnEditLinks    = findViewById(R.id.btnEditLinks)
        tvLinkCount     = findViewById(R.id.tvLinkCount)
        tvCountdown     = findViewById(R.id.tvCountdown)
        tvProgress      = findViewById(R.id.tvProgress)
        tvRunStatus     = findViewById(R.id.tvRunStatus)
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
        tabMessages     = findViewById(R.id.tabMessages)
        tabAuto         = findViewById(R.id.tabAuto)
        tabHistory      = findViewById(R.id.tabHistory)
        tabLog          = findViewById(R.id.tabLog)
        tabSettings     = findViewById(R.id.tabSettings)
        pageHome        = findViewById(R.id.pageHome)
        pageMessages    = findViewById(R.id.pageMessages)
        pageAuto        = findViewById(R.id.pageAuto)
        pageHistory     = findViewById(R.id.pageHistory)
        pageLog         = findViewById(R.id.pageLog)
        pageSettings    = findViewById(R.id.pageSettings)
        editMsgLinks    = findViewById(R.id.editMsgLinks)
        etMsgOnly       = findViewById(R.id.etMsgOnly)
        tvMsgLinkCount  = findViewById(R.id.tvMsgLinkCount)
        btnMsgPaste     = findViewById(R.id.btnMsgPaste)
        btnMsgFromHistory = findViewById(R.id.btnMsgFromHistory)
        btnMsgClear     = findViewById(R.id.btnMsgClear)
        btnMsgStart     = findViewById(R.id.btnMsgStart)
        etMaxUnread     = findViewById(R.id.etMaxUnread)
        btnScan         = findViewById(R.id.btnScan)
        tvScanResult    = findViewById(R.id.tvScanResult)
        btnScanCopy     = findViewById(R.id.btnScanCopy)
        radioAutoNormal   = findViewById(R.id.radioAutoNormal)
        radioAutoBusiness = findViewById(R.id.radioAutoBusiness)
        radioAutoBoth     = findViewById(R.id.radioAutoBoth)
        radioMsgNormal    = findViewById(R.id.radioMsgNormal)
        radioMsgBusiness  = findViewById(R.id.radioMsgBusiness)
        tvLog           = findViewById(R.id.tvLog)
        scrollLog       = findViewById(R.id.scrollLog)
        btnLogRefresh   = findViewById(R.id.btnLogRefresh)
        btnLogCopy      = findViewById(R.id.btnLogCopy)
        btnLogClear     = findViewById(R.id.btnLogClear)
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
        switchSendMessage = findViewById(R.id.switchSendMessage)
        etMessage = findViewById(R.id.etMessage)
        switchAutoCheck = findViewById(R.id.switchAutoCheck)
        etCheckInterval = findViewById(R.id.etCheckInterval)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        switchHumanTyping = findViewById(R.id.switchHumanTyping)
        etTypingWpm = findViewById(R.id.etTypingWpm)
        etPauseMin = findViewById(R.id.etPauseMin)
        etPauseMax = findViewById(R.id.etPauseMax)
        etCooldownHours = findViewById(R.id.etCooldownHours)
        btnBattery = findViewById(R.id.btnBattery)

        linkAdapter = LinkAdapter(linkItems)
        recyclerLinks.layoutManager = LinearLayoutManager(this)
        recyclerLinks.adapter = linkAdapter

        historyAdapter = HistoryAdapter(HistoryManager.entries)
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = historyAdapter

        btnPause.visibility = View.GONE
        btnRetry.visibility = View.GONE
        runBanner.visibility = View.GONE
        etMaxGroups.setText("0")
    }

    private fun setupTabs() {
        listOf(
            tabHome to "🏠\nInício",
            tabMessages to "💬\nMensagens",
            tabHistory to "📋\nHistórico",
            tabLog to "📜\nRegistro",
            tabSettings to "⚙️\nConfig"
        ).forEach { (tv, label) ->
            tv.text = label
            tv.textSize = 11f
            tv.maxLines = 2
        }
        tabHome.setOnClickListener { showTab("home") }
        tabMessages.setOnClickListener { showTab("messages") }
        tabHistory.setOnClickListener { showTab("history") }
        tabLog.setOnClickListener { showTab("log") }
        tabSettings.setOnClickListener { showTab("settings") }
        // seis abas: a de mensagens por lista e a automática (sem lista) ficam separadas
        tabMessages.text = "📝\nPor lista"
        tabAuto.text = "🤖\nAutomático"
        tabAuto.maxLines = 2
        tabAuto.setOnClickListener { showTab("auto") }
        listOf(tabHome, tabMessages, tabAuto, tabHistory, tabLog, tabSettings).forEach { it.textSize = 10f }
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
            if (!isPaused) pauseRunByUser() else resumeAfterPause()
        }

        // Finalizar (faixa vermelha, visível em todas as abas): pede confirmação e encerra o que estiver em andamento
        btnBannerStop.setOnClickListener { confirmFinalize() }

        btnRetry.setOnClickListener {
            if (failedLinks.isEmpty()) { Toast.makeText(this, "Nenhuma falha!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Retentar ${failedLinks.size} falhas (${Phase.label(lastRunPhase)})?")
                .setPositiveButton("Sim") { _, _ ->
                    val links = failedLinks.map { it.second }.distinct().toMutableList()
                    failedLinks.clear()
                    // refaz a MESMA fase em que as falhas aconteceram
                    if (lastRunPhase == Phase.MESSAGES && lastRunMessage.isNotEmpty()) beginMessages(links, lastRunMessage)
                    else startProcessing(links)
                }.setNegativeButton("Cancelar", null).show()
        }

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Limpar historico?")
                .setPositiveButton("Sim") { _, _ ->
                    HistoryManager.clear()
                    historyAdapter.notifyDataSetChanged()
                    updateHistoryCount()
                    StorageManager.clearAll(this)
                }.setNegativeButton("Cancelar", null).show()
        }

        switchDarkMode.setOnCheckedChangeListener { _, checked -> applyTheme(checked) }

        btnCheckNow.setOnClickListener { startApprovalCheck() }

        switchAutoCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) scheduleAutoCheck()
            else stopAutoCheck()
        }

        // --- Aba Mensagens ---
        btnMsgPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                val current = editMsgLinks.text.toString()
                editMsgLinks.setText(if (current.isBlank()) text else "$current\n$text")
                editMsgLinks.setSelection(editMsgLinks.text.length)
                updateMsgLinkCount()
                Toast.makeText(this, "Links colados!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Area de transferencia vazia", Toast.LENGTH_SHORT).show()
            }
        }

        btnMsgFromHistory.setOnClickListener {
            val doHistorico = HistoryManager.entries
                .filter { it.status == "joined" || it.status == "already_member" || it.status == "message_sent" }
                .map { it.link }
                .distinct()
            val jaTem = parseLinks(editMsgLinks.text.toString())
            val novos = doHistorico.filter { it !in jaTem }
            if (novos.isEmpty()) {
                Toast.makeText(this, "Nenhum grupo novo no histórico", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = editMsgLinks.text.toString()
            editMsgLinks.setText((if (current.isBlank()) "" else "$current\n") + novos.joinToString("\n"))
            updateMsgLinkCount()
            Toast.makeText(this, "${novos.size} grupos adicionados do histórico", Toast.LENGTH_SHORT).show()
        }

        btnMsgClear.setOnClickListener {
            editMsgLinks.setText("")
            updateMsgLinkCount()
        }

        btnMsgStart.setOnClickListener { startMessagesOnly() }

        // O WhatsApp escolhido vale para tudo (Início e Por lista): as duas escolhas andam juntas
        radioMsgNormal.isChecked = !radioBusiness.isChecked
        radioMsgBusiness.isChecked = radioBusiness.isChecked
        radioMsgNormal.setOnClickListener { radioNormal.isChecked = true }
        radioMsgBusiness.setOnClickListener { radioBusiness.isChecked = true }
        radioNormal.setOnCheckedChangeListener { _, marcado -> if (marcado) radioMsgNormal.isChecked = true }
        radioBusiness.setOnCheckedChangeListener { _, marcado -> if (marcado) radioMsgBusiness.isChecked = true }

        // --- Maneira 2: escanear (só leitura) ---
        btnScan.setOnClickListener { startScan() }
        btnScanCopy.setOnClickListener {
            val texto = scanDiagnosticText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("diagnostico", texto))
            Toast.makeText(this, "Diagnóstico copiado (sem nomes)!", Toast.LENGTH_SHORT).show()
        }
        etMaxUnread.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderScan()
            }
        })

        // --- Aba Registro ---
        btnLogRefresh.setOnClickListener { refreshLogView() }
        btnLogCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("registro", RunLog.lines(this, 800).joinToString("\n")))
            Toast.makeText(this, "Registro copiado!", Toast.LENGTH_SHORT).show()
        }
        btnLogClear.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Limpar o registro?")
                .setPositiveButton("Sim") { _, _ -> RunLog.clear(this); refreshLogView() }
                .setNegativeButton("Cancelar", null).show()
        }

        btnBattery.setOnClickListener { requestIgnoreBattery() }
    }

    // =====================================================================
    // Links
    // =====================================================================

    private fun parseLinks(text: String): List<String> {
        val prefixRegex = Regex("""^\d+\s*[-.):]?\s*""")
        return text.trim().split("\n")
            .map { prefixRegex.replace(it.trim(), "").trim() }
            .filter { it.startsWith("http") }
            .distinct()
    }

    private fun updateLinkCount() {
        tvLinkCount.text = "${parseLinks(editTextLinks.text.toString()).size} links"
    }

    private fun updateMsgLinkCount() {
        tvMsgLinkCount.text = "${parseLinks(editMsgLinks.text.toString()).size} grupos"
    }

    private fun requireAccessibility(): Boolean {
        if (isAccessibilityEnabled()) return true
        AlertDialog.Builder(this).setTitle("Permissao necessaria")
            .setMessage("Ative o 'Group Joiner Service' em Configuracoes > Acessibilidade.")
            .setPositiveButton("Configuracoes") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Cancelar", null).show()
        return false
    }

    // =====================================================================
    // Início de cada processo
    // =====================================================================

    private fun startProcessing(existingList: MutableList<String>? = null) {
        if (isRunning || runActive || scanInProgress) {
            askFinalizeThen { startProcessing(existingList) }
            return
        }
        if (!requireAccessibility()) return

        maxGroups = etMaxGroups.text.toString().toIntOrNull() ?: 0
        selectedPackage = if (radioBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

        linkList = existingList ?: parseLinks(editTextLinks.text.toString())
            .let { if (maxGroups > 0) it.take(maxGroups) else it }
            .toMutableList()

        if (linkList.isEmpty()) {
            Toast.makeText(this, "Nenhum link valido! Use o botao Editar Links.", Toast.LENGTH_LONG).show()
            return
        }

        resetListUi("Grupos (${linkList.size})")
        tvLinkCount.text = "${linkList.size} links"
        acquireScreenLock()
        val msg = if (switchSendMessage.isChecked) etMessage.text.toString().trim() else ""
        beginRun(Phase.JOIN, msg)
        openNextLink()
    }

    /** Zera a lista e os controles da tela inicial para um novo processo. */
    private fun resetListUi(title: String) {
        linkItems.clear()
        linkList.forEachIndexed { i, url -> linkItems.add(LinkItem(i + 1, url)) }
        linkAdapter.notifyDataSetChanged()
        tvLinkListTitle.text = title
        currentIndex = 0
        processed = false
        isPaused = false
        failedLinks.clear()
        btnStart.isEnabled = false
        btnPause.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        clearPauseUi()
        progressBar.max = linkList.size
        progressBar.progress = 0
        tvProgress.text = "0/${linkList.size}"
        tvCountdown.text = ""
    }

    /** Marca o início de uma fase: liga o serviço que mantém tudo vivo e salva o primeiro retrato. */
    private fun beginRun(newPhase: String, message: String) {
        phase = newPhase
        lastRunPhase = newPhase
        runMessage = message
        runActive = true
        isRunning = true
        isPaused = false
        runStartedAt = System.currentTimeMillis()
        attempt = 1
        envFailures = 0
        screenWaits = 0
        pauseReason = ""
        stage = "WAIT"
        RunRuntime.groupDone = true
        ForegroundService.start(this)
        ForegroundService.clearAlert(this)
        applyServiceMode()
        RunRuntime.currentPkg = selectedPackage
        RunLog.add(this, "▶ Iniciado: ${Phase.label(phase)} (${linkList.size} grupos, ${appName(selectedPackage)})")
        persistRun("WAIT", 60_000L)
    }

    /** Garante que o serviço de acessibilidade está no modo da fase atual (ele perde o modo se reiniciar). */
    private fun applyServiceMode() {
        val svc = GroupJoinerService.serviceInstance ?: return
        when (phase) {
            Phase.MESSAGES -> {
                svc.setCheckApproval(false)
                svc.setMessageMode(runMessage, switchHumanTyping.isChecked, readTypingWpm())
            }
            Phase.APPROVAL -> {
                svc.clearMessageMode()
                svc.setCheckApproval(true)
            }
            else -> {
                svc.clearMessageMode()
                svc.setCheckApproval(false)
            }
        }
    }

    private fun acquireScreenLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "OnGroups:WakeLock")
            wl.acquire(60 * 60 * 1000L) // máximo 1 hora, renovado a cada grupo
            wakeLock = wl
        } catch (e: Exception) { }
    }

    // Processos longos (a fase de mensagens digitando demora mais): renova a trava de tela a cada grupo
    private fun refreshWakeLock() {
        if (wakeLock == null) return
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "OnGroups:WakeLock")
            wl.acquire(60 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) { }
    }

    // =====================================================================
    // Condução dos grupos
    // =====================================================================

    /** Tempo máximo que um grupo pode levar antes de o vigia considerar que travou. */
    private fun groupBudgetMs(): Long {
        return if (phase == Phase.MESSAGES && switchHumanTyping.isChecked) {
            val palavras = HumanTyping.splitWords(runMessage).size
            90_000L + palavras * 6_000L
        } else 50_000L
    }

    fun openNextLink() {
        if (isPaused) return
        processed = false

        if (currentIndex >= linkList.size) {
            finishOrChain()
            return
        }

        // sem o serviço de acessibilidade não há o que fazer
        if (GroupJoinerService.serviceInstance == null) {
            pauseRun("${ForegroundService.PAUSE_SERVICE}: ative 'Group Joiner Service' em Configurações > Acessibilidade")
            return
        }

        // tela apagada ou bloqueada: não abre o WhatsApp agora
        if (!screenReadyOrWait()) return

        val link = linkList[currentIndex]

        // mensagens: não repete no mesmo grupo dentro do prazo (protege também contra envio em dobro depois de uma retomada)
        if (isPhase2 && skipIfSentRecently(link)) return

        applyServiceMode()
        RunRuntime.currentLink = link
        RunRuntime.currentPkg = selectedPackage
        RunRuntime.groupSeq = System.currentTimeMillis()
        RunRuntime.groupDone = false
        persistRun("GROUP", groupBudgetMs())
        RunLog.add(
            this,
            "▶ ${Phase.label(phase)} ${currentIndex + 1}/${linkList.size}" +
                (if (attempt > 1) " (tentativa $attempt/$MAX_ATTEMPTS)" else "")
        )

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

        refreshWakeLock()
        GroupJoinerService.serviceInstance?.setWaiting(true)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setPackage(selectedPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            RunLog.add(this, "⚠ Não consegui abrir o WhatsApp: ${e.message}")
            GroupJoinerService.serviceInstance?.setWaiting(false)
            RunRuntime.groupDone = true
            handleEnvRetry("o WhatsApp não abriu")
        }
    }

    /** Devolve true se a tela está pronta. Se não estiver, tenta acender/liberar, avisa e tenta de novo em 5 s. */
    private fun screenReadyOrWait(): Boolean {
        val travadaSemSaida = screenWaits >= 4 && !ScreenGuard.isSecure(this)
        if (ScreenGuard.isReady(this) || travadaSemSaida) {
            if (pauseReason.startsWith(ForegroundService.PAUSE_SCREEN)) {
                pauseReason = ""
                ForegroundService.clearAlert(this)
                RunLog.add(this, "🔓 Tela liberada: continuando")
            }
            screenWaits = 0
            return true
        }
        screenWaits++
        val motivo = ScreenGuard.describe(this)
        if (!ScreenGuard.isScreenOn(this)) ScreenGuard.wake(this)
        if (screenWaits == 1) RunLog.add(this, "🔆 $motivo: tentando acender a tela")
        if (ScreenGuard.isLocked(this) && ScreenGuard.isSecure(this) && screenWaits >= 3 && pauseReason.isEmpty()) {
            pauseReason = "${ForegroundService.PAUSE_SCREEN}: desbloqueie o celular para continuar"
            RunLog.add(this, "🔒 $pauseReason")
            ForegroundService.alert(
                this, "On Groups aguardando",
                "${Phase.label(phase)} ${minOf(currentIndex + 1, linkList.size)}/${linkList.size}: $pauseReason"
            )
        }
        persistRun("WAIT", 120_000L)
        runOnUiThread { tvCountdown.text = "🔒 $motivo — aguardando" }
        screenWaitRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            screenWaitRunnable = null
            if (isRunning && !isPaused) openNextLink()
        }
        screenWaitRunnable = r
        handler.postDelayed(r, 5_000L)
        return false
    }

    private fun readCooldownHours(): Int =
        (etCooldownHours.text.toString().trim().toIntOrNull() ?: 3).coerceIn(0, 720)

    /**
     * Mensagens: pula o grupo se a mensagem já foi enviada há pouco tempo POR ESTE MESMO WhatsApp.
     * A conta é separada: um envio feito pelo WhatsApp errado não bloqueia o envio pelo certo.
     * Devolve true se pulou.
     */
    private fun skipIfSentRecently(link: String): Boolean {
        val horas = readCooldownHours()
        if (horas <= 0) return false
        val ultimo = StorageManager.lastSent(this, link, selectedPackage)
        if (ultimo <= 0L) return false
        val idade = System.currentTimeMillis() - ultimo
        if (idade >= horas * 3_600_000L) return false
        val min = idade / 60_000L
        val faz = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        RunLog.add(this, "⏭ Mensagens ${currentIndex + 1}/${linkList.size}: pulado (já enviou pelo ${appName(selectedPackage)} há $faz; ajuste em Config)")
        RunRuntime.groupSeq = System.currentTimeMillis()
        RunRuntime.groupDone = true
        updateLinkStatus(currentIndex, "skipped_recent")
        addToHistory(link, "skipped_recent")
        scheduleNext(skipped = true)
        return true
    }

    private fun finishOrChain() {
        val joined = linkItems.count { it.status == "joined" }
        val msgSent = linkItems.count { it.status == "message_sent" }
        val requested = linkItems.count { it.status == "requested" }
        val pendingC = linkItems.count { it.status == "pending" }
        val failed = linkItems.count { it.status == "invalid" || it.status == "error_env" || it.status == "admin_only" }
        runOnUiThread {
            tvCountdown.text = if (msgSent > 0) "💬 ${msgSent} mensagens enviadas!" else "✅ Concluido!"
            tvProgress.text = "${linkList.size}/${linkList.size}"
            progressBar.progress = linkList.size
            btnStart.isEnabled = true
            btnPause.visibility = View.GONE
            if (failedLinks.isNotEmpty()) btnRetry.visibility = View.VISIBLE

            // Depois de ENTRAR (ou de verificar aprovados), manda a mensagem nos grupos em que entrou
            val shouldStartPhase2 = phase != Phase.MESSAGES && switchSendMessage.isChecked && !isPhase2 && joined > 0
            val msg = etMessage.text.toString().trim()

            if (shouldStartPhase2 && msg.isNotEmpty()) {
                // Mantém tudo ativo para a fase de mensagens
                tvCountdown.text = "💬 Iniciando envio de mensagens em 3s..."
                RunLog.add(this, "✔ ${Phase.label(phase)} concluída: $joined entraram. Iniciando MENSAGENS em 3 s")
                isRunning = true
                persistRun("WAIT", 60_000L)
                chainRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { chainRunnable = null; startPhase2(msg) }
                chainRunnable = r
                handler.postDelayed(r, 3000L)
                return@runOnUiThread
            }

            // Só libera tudo se não tem fase de mensagens
            finishRun(joined, requested + pendingC, failed, msgSent)
        }
    }

    private fun finishRun(joined: Int, waiting: Int, failed: Int, msgSent: Int) {
        val fase = phase
        runActive = false
        isRunning = false
        isPaused = false
        pauseReason = ""
        StorageManager.clearRun(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isPhase2 = false
        isCheckingApproval = false
        isLeavingGroup = false
        GroupJoinerService.serviceInstance?.clearMessageMode()
        GroupJoinerService.serviceInstance?.setCheckApproval(false)
        GroupJoinerService.serviceInstance?.setLeaveMode(false)
        ForegroundService.stop(this)
        ForegroundService.clearAlert(this)
        clearScreenFlags()
        RunLog.add(this, "✔ Concluído (${Phase.label(fase)}): ✅$joined entraram, 💬$msgSent mensagens, ⏳$waiting aguardando, ❌$failed falhas")
        renderIdleStatus()
        updateRunBanner()
        if (switchNotifications.isChecked) {
            if (fase == Phase.MESSAGES) sendFinishedNotification("💬 $msgSent mensagens enviadas  ❌ $failed falharam")
            else sendFinishedNotification("✅ $joined entrou  ⏳ $waiting aguardando  ❌ $failed falhou")
        }
    }

    /** Resultado de um grupo. [seq] identifica o grupo: resultados velhos ou repetidos são ignorados. */
    fun onGroupProcessed(status: String, seq: Long) {
        if (seq != RunRuntime.groupSeq || RunRuntime.groupDone) return
        RunRuntime.groupDone = true
        processed = true

        // Problema do ambiente (tela apagada, WhatsApp não respondeu): tenta o MESMO grupo de novo
        if (status == "retry_env") {
            handleEnvRetry("WhatsApp/tela não respondeu")
            return
        }
        attempt = 1
        envFailures = 0

        val link = if (currentIndex < linkList.size) linkList[currentIndex] else ""

        when (status) {
            "approved" -> {
                HistoryManager.pendingLinks.remove(link)
                updateLinkStatus(currentIndex, "joined")
                addToHistory(link, "joined")
                runOnUiThread {
                    tvCountdown.text = "✅ Aprovado! Grupo ${currentIndex + 1}"
                }
            }
            "still_pending" -> {
                // Mantém como requested na lista
                updateLinkStatus(currentIndex, "requested")
            }
            "invalid" -> {
                failedLinks.add(Pair(currentIndex, link))
                HistoryManager.pendingLinks.remove(link)
                updateLinkStatus(currentIndex, "invalid")
                addToHistory(link, status)
            }
            "admin_only" -> {
                // Admin sem envio — adiciona nos falhos para retentar
                failedLinks.add(Pair(currentIndex, link))
                updateLinkStatus(currentIndex, "admin_only")
                addToHistory(link, "admin_only")
            }
            "left_group" -> {
                // Saiu do grupo com sucesso
                updateLinkStatus(currentIndex, "left_group")
                addToHistory(link, "left_group")
            }
            else -> {
                updateLinkStatus(currentIndex, status)
                addToHistory(link, status)
            }
        }
        RunLog.add(
            this,
            "${StatusInfo.icon(status)} ${Phase.label(phase)} ${currentIndex + 1}/${linkList.size}: ${StatusInfo.text(status)}"
        )
        scheduleNext()
    }

    /** Falha por causa do ambiente: tenta o mesmo grupo de novo (até MAX_ATTEMPTS); depois marca e segue. */
    private fun handleEnvRetry(motivo: String) {
        val n = currentIndex + 1
        val link = linkList.getOrNull(currentIndex) ?: ""
        GroupJoinerService.serviceInstance?.abortCurrent()
        if (attempt < MAX_ATTEMPTS) {
            attempt++
            RunLog.add(
                this,
                "↻ ${Phase.label(phase)} $n/${linkList.size}: $motivo (${ScreenGuard.describe(this)}). Tentativa $attempt/$MAX_ATTEMPTS em 8 s"
            )
            persistRun("WAIT", 8_000L + 60_000L)
            runOnUiThread { tvCountdown.text = "↻ Tentando de novo ($attempt/$MAX_ATTEMPTS)" }
            startCountdown(8_000L, currentIndex) { openNextLink() }
            return
        }
        // desistiu deste grupo
        RunLog.add(this, "⚠ ${Phase.label(phase)} $n/${linkList.size}: falhou $MAX_ATTEMPTS vezes ($motivo). Marquei como falha e sigo")
        failedLinks.add(Pair(currentIndex, link))
        updateLinkStatus(currentIndex, "error_env")
        addToHistory(link, "error_env")
        attempt = 1
        envFailures++
        if (envFailures >= 3) {
            currentIndex++
            pauseRun("O WhatsApp/tela não respondeu em 3 grupos seguidos (${ScreenGuard.describe(this)})")
            return
        }
        scheduleNext()
    }

    private fun scheduleNext(skipped: Boolean = false) {
        // Entrar nos grupos: ciclo fixo 5/30/40/50/60 s. Mensagens: pausa menor e aleatória (Config)
        val delay = when {
            skipped -> 800L
            isPhase2 -> HumanTyping.pauseBetweenGroupsMs(readPauseMin(), readPauseMax())
            else -> defaultIntervals[currentIndex % defaultIntervals.size]
        }
        val nextIdx = currentIndex + 1
        currentIndex++
        persistRun("WAIT", delay + 60_000L)
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

    // =====================================================================
    // Estado salvo, pausa, parada, retomada
    // =====================================================================

    private fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun renderIdleStatus() {
        tvRunStatus.text = "Última atividade: " + RunLog.last(this)
        tvRunStatus.setTextColor(Color.parseColor("#455A64"))
    }

    /** Salva o retrato do processo (fase, grupo, resultados) e atualiza a linha de status e a notificação. */
    private fun persistRun(newStage: String, expectedInMs: Long) {
        stage = newStage
        val now = System.currentTimeMillis()
        val snap = RunSnapshot(
            active = runActive,
            phase = phase,
            links = linkList.toList(),
            statuses = linkItems.map { if (it.status == "current") "" else it.status },
            index = currentIndex,
            stage = stage,
            groupSeq = RunRuntime.groupSeq,
            attempt = attempt,
            message = runMessage,
            pkg = selectedPackage,
            progressAt = now,
            expectedBy = now + expectedInMs,
            pauseReason = pauseReason,
            startedAt = runStartedAt
        )
        StorageManager.saveRun(this, snap)
        updateRunBanner() // enquanto há processo salvo, a faixa com o Finalizar aparece em todas as abas
        val linha = snap.summaryLine()
        ForegroundService.updateStatus(this, linha + " · " + timeNow())
        tvRunStatus.text = linha + "  ·  " + timeNow()
        tvRunStatus.setTextColor(Color.parseColor(if (pauseReason.isNotEmpty()) "#B71C1C" else "#455A64"))
    }

    private fun showPausedUi(text: String) {
        btnPause.text = "▶ Continuar"
        btnPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        tvCountdown.text = text
    }

    private fun clearPauseUi() {
        btnPause.text = "⏸ Pausar"
        btnPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
        tvCountdown.text = ""
    }

    private fun cancelRunTimers() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        screenWaitRunnable?.let { handler.removeCallbacks(it) }
        chainRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun pauseRunByUser() {
        isPaused = true
        pauseReason = "Pausado por você"
        cancelRunTimers()
        persistRun(stage, 24L * 3_600_000L)
        RunLog.add(this, "⏸ Pausado por você")
        showPausedUi("⏸ Pausado")
    }

    /** Pausa automática (ex.: serviço de acessibilidade desligado, 3 falhas seguidas). O vigia avisa e, se possível, retoma sozinho. */
    private fun pauseRun(reason: String) {
        isPaused = true
        pauseReason = reason
        cancelRunTimers()
        persistRun("WAIT", 24L * 3_600_000L)
        RunLog.add(this, "⏸ Pausado: $reason")
        ForegroundService.alert(
            this, "On Groups pausado",
            "${Phase.label(phase)} ${minOf(currentIndex + 1, linkList.size)}/${linkList.size}: $reason"
        )
        runOnUiThread { showPausedUi("⏸ $reason") }
    }

    private fun resumeAfterPause() {
        isPaused = false
        pauseReason = ""
        screenWaits = 0
        envFailures = 0
        ForegroundService.clearAlert(this)
        clearPauseUi()
        RunLog.add(this, "▶ Retomado")
        if (stage == "GROUP" && !RunRuntime.groupDone) {
            // o grupo em andamento ainda vai responder
            persistRun("GROUP", groupBudgetMs())
        } else {
            openNextLink()
        }
    }

    /**
     * Finaliza TUDO o que estiver em andamento (entrada, mensagens, verificação ou escaneamento), mesmo sem ter terminado.
     * O que já foi feito continua feito; o que faltava não é processado e nada recomeça sozinho.
     */
    private fun stopRun(reason: String) {
        cancelRunTimers()
        // escaneamento em andamento: cancela também (o serviço derruba tudo o que estava agendado)
        val estavaEscaneando = scanInProgress
        if (scanInProgress) {
            scanSafetyRunnable?.let { handler.removeCallbacks(it) }
            scanSafetyRunnable = null
            scanQueue.clear()
            scanInProgress = false
            btnScan.isEnabled = true
        }
        GroupJoinerService.serviceInstance?.abortCurrent()
        runActive = false
        isRunning = false
        isPaused = false
        pauseReason = ""
        RunRuntime.groupDone = true
        StorageManager.clearRun(this)
        RunLog.add(this, "⏹ $reason")
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isPhase2 = false
        isCheckingApproval = false
        isLeavingGroup = false
        GroupJoinerService.serviceInstance?.clearMessageMode()
        GroupJoinerService.serviceInstance?.setCheckApproval(false)
        GroupJoinerService.serviceInstance?.setLeaveMode(false)
        ForegroundService.stop(this)
        ForegroundService.clearAlert(this)
        clearScreenFlags()
        // o grupo que estava em andamento volta a "pendente" (senão fica girando como se ainda estivesse rodando)
        linkItems.forEachIndexed { i, item ->
            if (item.status == "current") {
                item.status = "pending"
                item.countdown = ""
                linkAdapter.notifyItemChanged(i)
            }
        }
        btnStart.isEnabled = true
        btnPause.visibility = View.GONE
        clearPauseUi()
        tvCountdown.text = "⏹ Finalizado"
        renderIdleStatus()
        if (estavaEscaneando) renderScan()
        updateRunBanner()
    }

    /** Chamado pelo botão "Finalizar" da notificação. */
    fun stopRunFromOutside() {
        runOnUiThread { stopRun("Finalizado pelo botão da notificação") }
    }

    /** Uma linha dizendo o que está em andamento (faixa vermelha e caixas de confirmação). */
    private fun busyDescription(): String {
        if (scanInProgress) return "🔍 Escaneando as conversas do ${appName(selectedPackage)}"
        val total = linkList.size
        val pos = minOf(currentIndex + 1, total)
        val base = "${Phase.label(phase)} · grupo $pos de $total · ${appName(selectedPackage)}"
        return when {
            pauseReason.isNotEmpty() -> "⚠ $pauseReason — $base"
            isPaused -> "⏸ Pausado — $base"
            else -> base
        }
    }

    /** Mostra ou esconde a faixa vermelha "em andamento" (vale para todas as abas). */
    private fun updateRunBanner() {
        runOnUiThread {
            val busy = isRunning || runActive || scanInProgress
            if (busy) tvBannerStatus.text = busyDescription()
            runBanner.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    /** Botão Finalizar: pede confirmação e encerra o processo em andamento, mesmo sem ter terminado. */
    private fun confirmFinalize() {
        if (!(isRunning || runActive || scanInProgress)) {
            updateRunBanner()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Finalizar agora?")
            .setMessage(
                "Em andamento: ${busyDescription()}\n\n" +
                    "Os grupos que faltam NÃO serão processados e o app não continua sozinho. " +
                    "O que já foi enviado continua enviado."
            )
            .setPositiveButton("Finalizar") { _, _ ->
                if (isRunning || runActive || scanInProgress) stopRun("Finalizado por você")
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    /**
     * Já existe um processo em andamento e o usuário pediu outro: oferece finalizar o anterior (com confirmação)
     * e, se aceitar, refaz o pedido novo na sequência (que mostra a sua própria confirmação).
     */
    private fun askFinalizeThen(action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Já há um processo em andamento")
            .setMessage(
                "${busyDescription()}\n\n" +
                    "Para fazer este novo pedido, o anterior precisa terminar. Finalizar o anterior agora? " +
                    "Os grupos que faltam nele NÃO serão processados; o que já foi enviado continua enviado."
            )
            .setPositiveButton("Finalizar o anterior") { _, _ ->
                if (isRunning || runActive || scanInProgress) stopRun("Finalizado por você para fazer um novo pedido")
                // dá um instante para o WhatsApp assentar antes do pedido novo
                handler.postDelayed({ action() }, 800L)
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    /**
     * Entrada de tudo que chega de fora: resultado de um grupo (vindo do serviço de acessibilidade),
     * pedido de recuperação do vigia, ou simples abertura do app. Se havia um processo em andamento e esta
     * tela é nova, reconstrói o estado a partir do retrato salvo e continua na FASE certa.
     */
    private fun handleIncoming(inc: Intent?) {
        val status = inc?.getStringExtra(EXTRA_STATUS)
        val seq = inc?.getLongExtra(EXTRA_SEQ, 0L) ?: 0L
        val recover = inc?.getBooleanExtra(EXTRA_RECOVER, false) ?: false
        inc?.removeExtra(EXTRA_STATUS)
        inc?.removeExtra(EXTRA_SEQ)
        inc?.removeExtra(EXTRA_RECOVER)
        val scanDone = inc?.getBooleanExtra(EXTRA_SCAN_DONE, false) ?: false
        inc?.removeExtra(EXTRA_SCAN_DONE)
        if (scanDone) {
            onScanFinished()
            return
        }
        if (status != null || recover) showOverLockScreen()

        // Esta tela já está conduzindo o processo
        if (isRunning || runActive) {
            if (status != null) {
                onGroupProcessed(status, seq)
                return
            }
            if (recover) {
                when {
                    isPaused && pauseReason.startsWith(ForegroundService.PAUSE_SERVICE) -> {
                        RunLog.add(this, "🔓 Serviço de acessibilidade de volta: retomando")
                        resumeAfterPause()
                    }
                    pauseReason.startsWith(ForegroundService.PAUSE_SCREEN) -> {
                        pauseReason = ""
                        screenWaitRunnable?.let { handler.removeCallbacks(it) }
                        ForegroundService.clearAlert(this)
                        openNextLink()
                    }
                    else -> {
                        val s = StorageManager.loadRun(this)
                        if (s == null || System.currentTimeMillis() >= s.expectedBy) recoverStalled("vigia")
                    }
                }
            }
            return
        }

        // Tela nova: havia processo em andamento?
        val snap = StorageManager.loadRun(this) ?: return
        if (!snap.active) return
        restoreRun(snap)
        val velho = System.currentTimeMillis() - snap.progressAt > STALE_MS
        when {
            status != null && snap.stage == "GROUP" && seq == snap.groupSeq -> {
                RunRuntime.groupSeq = snap.groupSeq
                RunRuntime.groupDone = false
                RunLog.add(this, "↻ App reaberto: recebi o resultado do grupo ($status) e sigo")
                onGroupProcessed(status, seq)
            }
            snap.pauseReason.isNotEmpty() && !recover -> {
                isPaused = true
                pauseReason = snap.pauseReason
                showPausedUi("⏸ ${snap.pauseReason}")
                RunLog.add(this, "ℹ Processo estava pausado: ${snap.pauseReason}")
            }
            velho && !recover -> askResume(snap)
            else -> recoverStalled(if (recover) "vigia" else "retomada automática")
        }
    }

    /** Reconstrói na memória o processo salvo (fase, lista, resultados, posição). */
    private fun restoreRun(snap: RunSnapshot) {
        phase = snap.phase
        lastRunPhase = snap.phase
        linkList = snap.links.toMutableList()
        linkItems.clear()
        snap.links.forEachIndexed { i, url ->
            val st = snap.statuses.getOrNull(i) ?: ""
            linkItems.add(LinkItem(i + 1, url, if (st.isEmpty()) "pending" else st))
        }
        linkAdapter.notifyDataSetChanged()
        currentIndex = snap.index.coerceIn(0, linkList.size)
        attempt = snap.attempt
        stage = snap.stage
        runMessage = snap.message
        lastRunMessage = snap.message
        runStartedAt = snap.startedAt
        selectedPackage = snap.pkg
        if (snap.pkg == "com.whatsapp.w4b") radioBusiness.isChecked = true else radioNormal.isChecked = true
        if (snap.phase == Phase.JOIN && snap.message.isNotEmpty()) {
            etMessage.setText(snap.message)
            switchSendMessage.isChecked = true
        }
        runActive = true
        isRunning = true
        isPaused = false
        pauseReason = ""
        isPhase2 = snap.phase == Phase.MESSAGES
        isCheckingApproval = snap.phase == Phase.APPROVAL
        failedLinks.clear()
        snap.statuses.forEachIndexed { i, s ->
            if (s == "invalid" || s == "error_env" || s == "admin_only") failedLinks.add(Pair(i, snap.links[i]))
        }
        RunRuntime.currentLink = linkList.getOrNull(currentIndex)
        RunRuntime.currentPkg = selectedPackage
        applyServiceMode()
        tvLinkListTitle.text = when (snap.phase) {
            Phase.MESSAGES -> "💬 Enviando mensagens (${linkList.size})"
            Phase.APPROVAL -> "🔍 Verificando aprovacoes (${linkList.size})"
            else -> "Grupos (${linkList.size})"
        }
        progressBar.max = linkList.size
        progressBar.progress = currentIndex
        tvProgress.text = "${minOf(currentIndex + 1, linkList.size)}/${linkList.size}"
        btnStart.isEnabled = false
        btnPause.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        clearPauseUi()
        acquireScreenLock()
        ForegroundService.start(this)
        RunLog.add(this, "↻ Estado restaurado: ${Phase.label(snap.phase)}, grupo ${minOf(currentIndex + 1, linkList.size)}/${linkList.size}, etapa ${snap.stage}")
        updateRunBanner()
    }

    /** Continua de onde parou: o grupo que estava em andamento conta como tentativa perdida. */
    private fun recoverStalled(reason: String) {
        if (!runActive) return
        cancelRunTimers()
        GroupJoinerService.serviceInstance?.abortCurrent()
        RunLog.add(
            this,
            "⚠ Recuperando ($reason): ${Phase.label(phase)} ${minOf(currentIndex + 1, linkList.size)}/${linkList.size}, etapa $stage"
        )
        if (isPaused) {
            isPaused = false
            pauseReason = ""
            clearPauseUi()
        }
        if (stage == "GROUP") {
            RunRuntime.groupDone = true
            handleEnvRetry("sem resposta ($reason)")
        } else {
            openNextLink()
        }
    }

    private fun askResume(snap: RunSnapshot) {
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snap.progressAt))
        AlertDialog.Builder(this)
            .setTitle("Continuar de onde parou?")
            .setMessage(
                "O app parou na fase ${Phase.label(snap.phase)}, no grupo ${minOf(snap.index + 1, snap.links.size)} de ${snap.links.size}, " +
                    "às $hora. Deseja continuar?"
            )
            .setPositiveButton("Continuar") { _, _ -> recoverStalled("confirmado por você") }
            .setNegativeButton("Descartar") { _, _ -> stopRun("Processo anterior descartado por você") }
            .setCancelable(false)
            .show()
    }

    // =====================================================================
    // Verificação de aprovados
    // =====================================================================

    private fun scheduleAutoCheck() {
        stopAutoCheck()
        val intervalMin = etCheckInterval.text.toString().toLongOrNull() ?: 30
        autoCheckIntervalMs = intervalMin * 60 * 1000L
        approvalCheckRunnable = object : Runnable {
            override fun run() {
                if (HistoryManager.pendingLinks.isNotEmpty() && !isRunning && !runActive && !scanInProgress) {
                    startApprovalCheck(fromTimer = true)
                }
                handler.postDelayed(this, autoCheckIntervalMs)
            }
        }
        handler.postDelayed(approvalCheckRunnable!!, autoCheckIntervalMs)
        Toast.makeText(this, "Verificacao automatica ativa a cada ${intervalMin}min", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutoCheck() {
        approvalCheckRunnable?.let { handler.removeCallbacks(it) }
        approvalCheckRunnable = null
    }

    private fun startApprovalCheck(fromTimer: Boolean = false) {
        val pending = HistoryManager.pendingLinks.toMutableList()
        if (pending.isEmpty()) {
            Toast.makeText(this, "Nenhum pedido pendente!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRunning || runActive || scanInProgress) {
            // a verificação automática (timer) nunca interrompe nada nem abre pergunta; só o pedido feito por você oferece finalizar
            if (!fromTimer) askFinalizeThen { startApprovalCheck() }
            return
        }

        isCheckingApproval = true
        linkList = pending
        resetListUi("🔍 Verificando aprovacoes (${linkList.size})")
        linkItems.forEach { it.status = "requested" } // começa como pendente
        linkAdapter.notifyDataSetChanged()

        // Vai para aba Inicio para acompanhar
        showTab("home")
        acquireScreenLock()
        beginRun(Phase.APPROVAL, "")
        openNextLink()
    }

    // =====================================================================
    // Fase de mensagens
    // =====================================================================

    /** Depois de entrar nos grupos: manda a mensagem só nos grupos em que entrou. */
    private fun startPhase2(message: String) {
        val links = linkItems.filter { it.status == "joined" }.map { it.url }
        if (links.isEmpty()) {
            finishRun(0, 0, 0, 0)
            return
        }
        beginMessages(links, message)
    }

    /** Aba Mensagens: manda a mensagem nos grupos da lista, sem entrar em nenhum. */
    private fun startMessagesOnly() {
        if (isRunning || runActive || scanInProgress) {
            askFinalizeThen { startMessagesOnly() }
            return
        }
        if (!requireAccessibility()) return
        val links = parseLinks(editMsgLinks.text.toString())
        val msg = etMsgOnly.text.toString().trim()
        if (links.isEmpty()) {
            Toast.makeText(this, "Cole os links dos grupos primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        if (msg.isEmpty()) {
            Toast.makeText(this, "Escreva a mensagem primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        // confirmação: mostra em qual WhatsApp vai sair, para quantos grupos e o começo da mensagem
        val app = if (radioMsgBusiness.isChecked) "WhatsApp Business" else "WhatsApp"
        val previa = if (msg.length > 120) msg.take(120) + "…" else msg
        AlertDialog.Builder(this)
            .setTitle("Enviar para ${links.size} grupos?")
            .setMessage("Pelo $app.\n\nMensagem:\n$previa")
            .setPositiveButton("Enviar") { _, _ ->
                selectedPackage = if (radioMsgBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"
                saveSavedSettings()
                showTab("home")
                beginMessages(links, msg)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun beginMessages(links: List<String>, message: String) {
        phase2Links = links.toMutableList()
        isPhase2 = true
        lastRunMessage = message
        saveTypingSettings()
        linkList = phase2Links
        resetListUi("💬 Enviando mensagens (${linkList.size})")
        acquireScreenLock()
        beginRun(Phase.MESSAGES, message)
        openNextLink()
    }

    // =====================================================================
    // Ajustes (aba Config)
    // =====================================================================

    // =====================================================================
    // Maneira 2: escanear a lista de conversas do WhatsApp (SÓ LEITURA)
    // =====================================================================

    private fun readMaxUnread(): Int =
        (etMaxUnread.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 999)

    /** Intent para abrir o app; se o sistema não enxergar o pacote, tenta pelo nome do pacote mesmo assim. */
    private fun launchIntentFor(pkg: String): Intent =
        packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)

    private fun appName(pkg: String): String = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"

    private fun startScan() {
        if (scanInProgress) {
            Toast.makeText(this, "O escaneamento já está em andamento. Para cancelar, use Finalizar (faixa vermelha).", Toast.LENGTH_LONG).show()
            return
        }
        if (isRunning || runActive) {
            askFinalizeThen { startScan() }
            return
        }
        if (!requireAccessibility()) return
        if (GroupJoinerService.serviceInstance == null) {
            Toast.makeText(this, "Serviço de acessibilidade desligado.", Toast.LENGTH_LONG).show()
            return
        }
        // quais WhatsApp escanear (escolha da aba Automático)
        val quais = when {
            radioAutoNormal.isChecked -> listOf("com.whatsapp")
            radioAutoBusiness.isChecked -> listOf("com.whatsapp.w4b")
            else -> listOf("com.whatsapp", "com.whatsapp.w4b")
        }
        scanQueue.clear()
        scanQueue.addAll(quais)
        scanSkipNote = ""
        scanInProgress = true
        btnScan.isEnabled = false
        acquireScreenLock()
        RunLog.add(this, "🔍 Escaneamento iniciado: " + quais.joinToString(" + ") { appName(it) })
        scanNext()
    }

    /** Escaneia o próximo WhatsApp da fila; quando a fila acaba, mostra o resultado. */
    private fun scanNext() {
        val svc = GroupJoinerService.serviceInstance
        if (svc == null || scanQueue.isEmpty()) {
            onScanFinished()
            return
        }
        val pkg = scanQueue.removeAt(0)
        selectedPackage = pkg
        updateRunBanner()
        tvScanResult.text = "🔍 Escaneando o ${appName(pkg)}... o app abre o WhatsApp, vai ao topo, rola a lista até " +
            "embaixo e volta sozinho (pode levar até 3 minutos por WhatsApp). Não mexa no celular."
        scanSafetyRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (scanInProgress) {
                scanQueue.clear()
                onScanFinished()
            }
        }
        scanSafetyRunnable = r
        handler.postDelayed(r, 200_000L)
        val abrir = launchIntentFor(pkg)
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(abrir)
        } catch (e: Exception) {
            // não abriu: esse WhatsApp não está instalado neste celular
            RunLog.add(this, "⚠ ${appName(pkg)} não encontrado neste celular (${e.message})")
            scanSkipNote += (if (scanSkipNote.isEmpty()) "" else "; ") + "${appName(pkg)} não está instalado"
            scanNext()
            return
        }
        svc.startScan(pkg)
    }

    /** O serviço terminou um WhatsApp (ou o tempo esgotou): segue para o próximo da fila ou mostra o resultado. */
    private fun onScanFinished() {
        if (scanInProgress && scanQueue.isNotEmpty() && GroupJoinerService.serviceInstance != null) {
            scanNext()
            return
        }
        scanSafetyRunnable?.let { handler.removeCallbacks(it) }
        scanSafetyRunnable = null
        val estava = scanInProgress
        scanInProgress = false
        scanQueue.clear()
        btnScan.isEnabled = true
        updateRunBanner()
        if (!runActive) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        showTab("auto")
        renderScan()
        if (estava) Toast.makeText(this, "Escaneamento concluído", Toast.LENGTH_SHORT).show()
    }

    private fun scanText(nome: String, r: ScanResult): String {
        val limite = readMaxUnread()
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.finishedAt))
        val dentro = r.rows.count { it.unread <= limite }
        val sb = StringBuilder()
        sb.append("📱 $nome — lido às $hora: ${r.rows.size} conversas\n")
        sb.append("✅ $dentro com até $limite não lidas (receberiam a mensagem)\n")
        sb.append("⏭ ${r.rows.size - dentro} com mais (seriam puladas)\n")
        if (r.note.isNotEmpty()) sb.append("ℹ ${r.note}\n")
        sb.append("\n")
        r.rows.forEach {
            sb.append(if (it.unread <= limite) "✅ " else "⏭ ")
            sb.append(it.name).append(" — ")
            sb.append(if (it.approx) "marcada como não lida" else "${it.unread} não lidas").append("\n")
        }
        return sb.toString()
    }

    /** Último escaneamento de cada WhatsApp (normal e Business) que já foi feito. */
    private fun scanResults(): List<Pair<String, ScanResult>> =
        listOf("com.whatsapp", "com.whatsapp.w4b").mapNotNull { pkg ->
            ScanStore.load(this, pkg)?.let { appName(pkg) to it }
        }

    private fun renderScan() {
        if (scanInProgress) return
        val partes = scanResults()
        val texto = if (partes.isEmpty()) "Nenhum escaneamento ainda."
            else partes.joinToString("\n\n") { (nome, r) -> scanText(nome, r) }
        tvScanResult.text = if (scanSkipNote.isNotEmpty()) "$texto\n\nℹ $scanSkipNote" else texto
    }

    /** Texto do botão "Copiar diagnóstico": só estrutura da tela e contagens, sem nomes de grupos nem mensagens. */
    private fun scanDiagnosticText(): String {
        val partes = scanResults()
        if (partes.isEmpty()) return "Nenhum escaneamento ainda."
        return partes.joinToString("\n\n") { (nome, r) ->
            "=== $nome ===\nConversas lidas: ${r.rows.size}\nObservações: ${r.note}\n\n${r.diag}"
        }
    }

    private fun readTypingWpm(): Int =
        (etTypingWpm.text.toString().trim().toIntOrNull() ?: 70).coerceIn(20, 250)

    private fun readPauseMin(): Int =
        (etPauseMin.text.toString().trim().toIntOrNull() ?: 8).coerceIn(1, 600)

    private fun readPauseMax(): Int =
        (etPauseMax.text.toString().trim().toIntOrNull() ?: 20).coerceIn(readPauseMin(), 600)

    private fun loadTypingSettings() {
        val s = StorageManager.loadTypingSettings(this)
        switchHumanTyping.isChecked = s.human
        etTypingWpm.setText(s.wpm.toString())
        etPauseMin.setText(s.pauseMin.toString())
        etPauseMax.setText(s.pauseMax.toString())
    }

    private fun saveTypingSettings() {
        StorageManager.saveTypingSettings(
            this, switchHumanTyping.isChecked, readTypingWpm(), readPauseMin(), readPauseMax()
        )
    }

    /** Mensagem da Config, prazo entre envios e a aba Mensagens: agora ficam salvos mesmo se o app reiniciar. */
    private fun loadSavedSettings() {
        etMessage.setText(StorageManager.getPref(this, "cfg_message"))
        switchSendMessage.isChecked = StorageManager.getPref(this, "cfg_send_after_join") == "1"
        etCooldownHours.setText(StorageManager.getPref(this, "cfg_cooldown", "3"))
        editMsgLinks.setText(StorageManager.getPref(this, "msg_links"))
        etMsgOnly.setText(StorageManager.getPref(this, "msg_text"))
        // qual WhatsApp estava escolhido (normal ou Business) fica lembrado
        if (StorageManager.getPref(this, "wa_app") == "business") radioBusiness.isChecked = true else radioNormal.isChecked = true
        etMaxUnread.setText(StorageManager.getPref(this, "max_unread", "0"))
        when (StorageManager.getPref(this, "auto_app", "both")) {
            "normal" -> radioAutoNormal.isChecked = true
            "business" -> radioAutoBusiness.isChecked = true
            else -> radioAutoBoth.isChecked = true
        }
        updateMsgLinkCount()
    }

    private fun saveSavedSettings() {
        StorageManager.setPref(this, "cfg_message", etMessage.text.toString())
        StorageManager.setPref(this, "cfg_send_after_join", if (switchSendMessage.isChecked) "1" else "0")
        StorageManager.setPref(this, "cfg_cooldown", readCooldownHours().toString())
        StorageManager.setPref(this, "msg_links", editMsgLinks.text.toString())
        StorageManager.setPref(this, "msg_text", etMsgOnly.text.toString())
        StorageManager.setPref(this, "wa_app", if (radioBusiness.isChecked) "business" else "normal")
        StorageManager.setPref(this, "max_unread", readMaxUnread().toString())
        StorageManager.setPref(
            this, "auto_app",
            if (radioAutoNormal.isChecked) "normal" else if (radioAutoBusiness.isChecked) "business" else "both"
        )
    }

    // =====================================================================
    // Lista, histórico e abas
    // =====================================================================

    private fun updateLinkStatus(index: Int, status: String) {
        runOnUiThread {
            if (index < linkItems.size) {
                linkItems[index].status = status
                linkItems[index].countdown = ""
                linkAdapter.notifyItemChanged(index)
            }
        }
    }

    private fun addToHistory(link: String, status: String) {
        val entry = HistoryEntry(number = currentIndex, link = link, status = status)
        HistoryManager.add(entry)
        runOnUiThread { historyAdapter.notifyItemInserted(0); updateHistoryCount() }
        // Persistir histórico e pendentes
        StorageManager.saveHistory(this)
        StorageManager.savePendingLinks(this)
    }

    private fun updateHistoryCount() {
        val total = HistoryManager.entries.size
        val joined = HistoryManager.entries.count { it.status == "joined" }
        val requested = HistoryManager.entries.count { it.status == "requested" }
        val pending = HistoryManager.entries.count { it.status == "pending" }
        val msgs = HistoryManager.entries.count { it.status == "message_sent" }
        val invalid = HistoryManager.entries.count { it.status == "invalid" || it.status == "error_env" }
        tvHistoryCount.text = "$total  ✅$joined  💬$msgs  ⏳$requested  🔒$pending  ❌$invalid"
    }

    private fun refreshLogView() {
        val lines = RunLog.lines(this, 300)
        tvLog.text = if (lines.isEmpty()) "Nada registrado ainda." else lines.joinToString("\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showTab(tab: String) {
        listOf(
            "home" to pageHome, "messages" to pageMessages, "auto" to pageAuto, "history" to pageHistory,
            "log" to pageLog, "settings" to pageSettings
        ).forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        listOf(
            "home" to tabHome, "messages" to tabMessages, "auto" to tabAuto, "history" to tabHistory,
            "log" to tabLog, "settings" to tabSettings
        ).forEach { (key, tv) ->
            if (key == tab) { tv.setTextColor(Color.parseColor("#075E54")); tv.setBackgroundColor(Color.parseColor("#E8F5E9")) }
            else { tv.setTextColor(Color.parseColor("#888888")); tv.setBackgroundColor(Color.WHITE) }
        }
        if (tab == "log") refreshLogView()
        if (tab == "messages") updateMsgLinkCount()
        if (tab == "auto") renderScan()
        if (tab == "history") updateHistoryCount()
    }

    private fun applyTheme(dark: Boolean) {
        findViewById<View>(R.id.rootLayout).setBackgroundColor(if (dark) Color.parseColor("#121212") else Color.WHITE)
    }

    // =====================================================================
    // Sistema: permissões, notificações, tela
    // =====================================================================

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

    private fun sendFinishedNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("On Groups — Concluido!")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        nm.notify(1, notif)
    }

    /** Android 13+: sem esta permissão nenhuma notificação (nem o status fixo nem os avisos) aparece. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 701) } catch (e: Exception) { }
        }
    }

    private fun maybeAskBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            if (StorageManager.getPref(this, "battery_asked") == "1") return
            StorageManager.setPref(this, "battery_asked", "1")
            AlertDialog.Builder(this)
                .setTitle("Liberar uso de bateria?")
                .setMessage(
                    "Para o On Groups continuar trabalhando com o app em segundo plano, libere o uso de bateria. " +
                        "Depois, vale também fixar o app nos recentes (cadeado) do seu celular."
                )
                .setPositiveButton("Liberar") { _, _ -> requestIgnoreBattery() }
                .setNegativeButton("Depois", null)
                .show()
        } catch (e: Exception) { }
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    /** Quando o app volta sozinho (resultado de um grupo ou recuperação), acende a tela e aparece mesmo com bloqueio de deslizar. */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked && !km.isDeviceSecure) {
                km.requestDismissKeyguard(this, null)
            }
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun clearScreenFlags() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) { }
    }
}

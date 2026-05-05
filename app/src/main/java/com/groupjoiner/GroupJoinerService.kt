package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var timeoutRunnable: Runnable? = null
    private var checkRunnable: Runnable? = null

    companion object {
        var serviceInstance: GroupJoinerService? = null
        const val NOTIF_ID = 42
        const val CHANNEL_ID = "ongroups_return"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun setWaiting(waiting: Boolean) {
        isActive = waiting
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }

        if (waiting) {
            scheduleCheck(5000L, 1)
            timeoutRunnable = Runnable {
                if (isActive) finishWithBack("invalid")
            }
            handler.postDelayed(timeoutRunnable!!, 25_000L)
        } else {
            cancelReturnNotification()
        }
    }

    private fun scheduleCheck(delayMs: Long, attempt: Int) {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = Runnable { tryActOnScreen(attempt) }
        handler.postDelayed(checkRunnable!!, delayMs)
    }

    private fun tryActOnScreen(attempt: Int) {
        if (!isActive) return

        val root = rootInActiveWindow
        if (root == null) {
            if (attempt < 5) scheduleCheck(2000L, attempt + 1) else finishWithBack("invalid")
            return
        }

        val pkg = root.packageName?.toString() ?: ""
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
            root.recycle()
            if (attempt < 5) scheduleCheck(2000L, attempt + 1) else finishWithBack("invalid")
            return
        }

        val status = detectAndAct(root)
        root.recycle()

        when (status) {
            "joined", "requested", "pending", "already_member" -> {
                isActive = false
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                checkRunnable?.let { handler.removeCallbacks(it) }
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp(status) }, 1800L)
            }
            else -> {
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else finishWithBack("invalid")
            }
        }
    }

    private fun finishWithBack(status: String) {
        isActive = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ returnToApp(status) }, 1000L)
    }

    private fun returnToApp(status: String) {
        isActive = false
        cancelReturnNotification()

        // Estratégia 1: REORDER_TO_FRONT (Motorola, maioria)
        try {
            val i = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            i?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            i?.let { startActivity(it) }
        } catch (e: Exception) { }

        // Estratégia 2: CLEAR_TOP direto (Samsung, OnePlus)
        try {
            val i = Intent(applicationContext, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } catch (e: Exception) { }

        // Estratégia 3: Notificação clicável (Xiaomi, MIUI, EMUI)
        // Mostra notificação para o usuário tocar e voltar
        showReturnNotification(status)

        // Notifica o resultado após 1s
        handler.postDelayed({
            MainActivity.instance?.onGroupProcessed(status)
        }, 1000L)

        // Tenta novamente após 2s caso ainda não tenha voltado
        handler.postDelayed({
            MainActivity.instance?.onGroupProcessed(status)
        }, 2500L)
    }

    private fun showReturnNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val returnIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, returnIntent, flags)

        val statusText = when (status) {
            "joined"         -> "✅ Entrou no grupo!"
            "requested"      -> "⏳ Pedido enviado!"
            "pending"        -> "🔒 Aguardando liberacao"
            "already_member" -> "👥 Ja e membro"
            else             -> "❌ Grupo invalido"
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("On Groups — Toque para voltar")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIF_ID, notif)

        // Remove a notificação após 8s automaticamente
        handler.postDelayed({ cancelReturnNotification() }, 8000L)
    }

    private fun cancelReturnNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIF_ID)
        } catch (e: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "On Groups Retorno",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun detectAndAct(root: AccessibilityNodeInfo): String {
        for (t in listOf("voce ja e membro", "you're already a member", "ja participa", "already a participant")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "already_member"
        }
        for (t in listOf("cancelar pedido", "cancel request", "cancelar solicitacao", "withdraw request", "pedido enviado", "request sent")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "pending"
        }
        for (t in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                if (tryClick(node)) { node.recycle(); return "joined" }
                node.recycle()
            }
        }
        for (t in listOf("enviar pedido", "send request", "solicitar", "request to join", "pedir para entrar")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                if (tryClick(node)) { node.recycle(); return "requested" }
                node.recycle()
            }
        }
        return scanAllClickable(root)
    }

    private fun scanAllClickable(root: AccessibilityNodeInfo): String {
        val joinKw = listOf("entrar", "join", "participar")
        val requestKw = listOf("pedido", "request", "solicitar", "pedir")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val combined = "$text $desc"
            if (node.isClickable && combined.isNotBlank()) {
                when {
                    joinKw.any { combined.contains(it) } -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        queue.forEach { it.recycle() }
                        return "joined"
                    }
                    requestKw.any { combined.contains(it) } -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        queue.forEach { it.recycle() }
                        return "requested"
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return "not_found"
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
        val p = node.parent
        if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); return true }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
        cancelReturnNotification()
    }
}

package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var timeoutRunnable: Runnable? = null
    private var checkRunnable: Runnable? = null

    companion object {
        var serviceInstance: GroupJoinerService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
    }

    // Não usamos eventos — usamos polling
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun setWaiting(waiting: Boolean) {
        isActive = waiting
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }

        if (waiting) {
            scheduleCheck(5000L, 1)

            // Timeout geral de 25s
            timeoutRunnable = Runnable {
                if (isActive) finishWithBack("invalid")
            }
            handler.postDelayed(timeoutRunnable!!, 25_000L)
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
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1000L)
                handler.postDelayed({ returnToApp(status) }, 2000L)
            }
            else -> {
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else finishWithBack("invalid")
            }
        }
    }

    private fun detectAndAct(root: AccessibilityNodeInfo): String {
        // 1. Já é membro
        for (t in listOf("você já é membro", "you're already a member", "já participa", "already a participant")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "already_member"
        }

        // 2. Pedido já enviado
        for (t in listOf("cancelar pedido", "cancel request", "cancelar solicitação", "withdraw request", "pedido enviado", "request sent")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "pending"
        }

        // 3. Botão Entrar — busca por texto exato
        for (t in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                if (tryClick(node)) { node.recycle(); return "joined" }
                node.recycle()
            }
        }

        // 4. Botão Enviar Pedido — busca por texto exato
        for (t in listOf("enviar pedido", "send request", "solicitar", "request to join", "pedir para entrar")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                if (tryClick(node)) { node.recycle(); return "requested" }
                node.recycle()
            }
        }

        // 5. Busca ampla — percorre TODOS os nós clicáveis da tela
        return scanAllClickable(root)
    }

    // Percorre toda a árvore procurando botões com palavras-chave
    private fun scanAllClickable(root: AccessibilityNodeInfo): String {
        val joinKeywords = listOf("entrar", "join", "participar", "enter")
        val requestKeywords = listOf("pedido", "request", "solicitar", "pedir")

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val combined = "$text $desc"

            if (node.isClickable && combined.isNotBlank()) {
                when {
                    joinKeywords.any { combined.contains(it) } -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        // reciclar filhos da fila
                        queue.forEach { it.recycle() }
                        return "joined"
                    }
                    requestKeywords.any { combined.contains(it) } -> {
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
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        val p = node.parent
        if (p?.isClickable == true) {
            p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p.recycle()
            return true
        }
        return false
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
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
        } catch (e: Exception) { }
        handler.postDelayed({ MainActivity.instance?.onGroupProcessed(status) }, 600L)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
    }
}

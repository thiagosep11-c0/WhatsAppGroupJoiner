package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedTime = 0L
    private val cooldown = 8000L
    private var waitingForGroup = false
    private var alreadyActed = false
    private var timeoutRunnable: Runnable? = null

    companion object {
        var serviceInstance: GroupJoinerService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!waitingForGroup || alreadyActed) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        timeoutRunnable?.let { handler.removeCallbacks(it) }

        handler.postDelayed({
            if (!waitingForGroup || alreadyActed) return@postDelayed
            tryActOnScreen(1)
        }, 4000L)
    }

    private fun tryActOnScreen(attempt: Int) {
        val root = rootInActiveWindow ?: run {
            doneAndBack("invalid")
            return
        }

        val status = detectScreen(root)
        root.recycle()

        when (status) {
            "joined" -> {
                alreadyActed = true
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp("joined") }, 1800L)
            }
            "requested" -> {
                alreadyActed = true
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp("requested") }, 1800L)
            }
            "pending" -> {
                // Já enviou pedido antes — "cancelar pedido" visível
                alreadyActed = true
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp("pending") }, 1800L)
            }
            "already_member" -> {
                alreadyActed = true
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp("already_member") }, 1800L)
            }
            else -> {
                // not_found — tenta mais vezes
                if (attempt < 3) {
                    handler.postDelayed({ tryActOnScreen(attempt + 1) }, 2000L)
                } else {
                    doneAndBack("invalid")
                }
            }
        }
    }

    private fun detectScreen(root: AccessibilityNodeInfo): String {
        // Já é membro
        for (t in listOf("você já é membro", "you're already a member", "já participa", "already a participant")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "already_member"
        }

        // Pedido já enviado (cancelar pedido visível)
        for (t in listOf("cancelar pedido", "cancel request", "cancelar solicitação", "withdraw request")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "pending"
        }

        // Tentar entrar
        for (t in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                val clicked = clickNode(node)
                node.recycle()
                if (clicked) return "joined"
            }
        }

        // Tentar enviar pedido
        for (t in listOf("enviar pedido", "send request", "solicitar", "request to join")) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            for (node in nodes) {
                val clicked = clickNode(node)
                node.recycle()
                if (clicked) return "requested"
            }
        }

        return "not_found"
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
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

    private fun doneAndBack(status: String) {
        alreadyActed = true
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500L)
        handler.postDelayed({ returnToApp(status) }, 1500L)
    }

    private fun returnToApp(status: String) {
        lastProcessedTime = System.currentTimeMillis()
        waitingForGroup = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
        } catch (e: Exception) { }
        handler.postDelayed({
            MainActivity.instance?.onGroupProcessed(status)
        }, 600L)
    }

    fun setWaiting(waiting: Boolean) {
        waitingForGroup = waiting
        alreadyActed = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        if (waiting) {
            timeoutRunnable = Runnable {
                if (waitingForGroup && !alreadyActed) {
                    doneAndBack("invalid")
                }
            }
            handler.postDelayed(timeoutRunnable!!, 20_000L)
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); serviceInstance = null }
}

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
        if (!waitingForGroup) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        // Cancela timeout anterior
        timeoutRunnable?.let { handler.removeCallbacks(it) }

        // Aguarda 4s para tela carregar completamente
        handler.postDelayed({
            if (!waitingForGroup) return@postDelayed
            val root = rootInActiveWindow ?: run {
                handleResult(false, "invalid")
                return@postDelayed
            }

            if (isGroupInviteScreen(root)) {
                val result = tryClickJoinButton(root)
                root.recycle()
                handleResult(result.first, result.second)
            } else {
                root.recycle()
                // Tenta mais uma vez após 2s (tela pode ainda estar carregando)
                handler.postDelayed({
                    val root2 = rootInActiveWindow ?: run { handleResult(false, "invalid"); return@postDelayed }
                    if (isGroupInviteScreen(root2)) {
                        val result = tryClickJoinButton(root2)
                        root2.recycle()
                        handleResult(result.first, result.second)
                    } else {
                        root2.recycle()
                        handleResult(false, "invalid")
                    }
                }, 2000L)
            }
        }, 4000L)
    }

    private fun handleResult(clicked: Boolean, type: String) {
        lastProcessedTime = System.currentTimeMillis()
        waitingForGroup = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }

        // Volta pro app
        handler.postDelayed({
            try {
                val appIntent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
                appIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                appIntent?.let { startActivity(it) }
            } catch (e: Exception) { }

            handler.postDelayed({
                MainActivity.instance?.onGroupProcessed(clicked, type)
            }, 500L)
        }, 1000L)
    }

    private fun isGroupInviteScreen(root: AccessibilityNodeInfo): Boolean {
        val texts = listOf(
            "entrar no grupo", "join group", "enviar pedido", "send request",
            "grupo do whatsapp", "whatsapp group", "convidado para",
            "you've been invited", "link de convite", "invite link"
        )
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) { nodes.forEach { it.recycle() }; return true }
        }
        return false
    }

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        for (text in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "joined") }
                val p = node.parent
                if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "joined") }
                node.recycle()
            }
        }
        for (text in listOf("enviar pedido", "send request", "solicitar", "request")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "requested") }
                val p = node.parent
                if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "requested") }
                node.recycle()
            }
        }
        return Pair(false, "not_found")
    }

    fun setWaiting(waiting: Boolean) {
        waitingForGroup = waiting
        if (waiting) {
            // Timeout de 15s — se não detectar nada, marca como inválido e volta
            timeoutRunnable = Runnable {
                if (waitingForGroup) {
                    handleResult(false, "invalid")
                }
            }
            handler.postDelayed(timeoutRunnable!!, 15_000L)
        } else {
            timeoutRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}

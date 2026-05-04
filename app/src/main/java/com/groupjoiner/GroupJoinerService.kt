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

        // Aguarda 4s para tela carregar
        handler.postDelayed({
            if (!waitingForGroup || alreadyActed) return@postDelayed
            tryActOnScreen(1)
        }, 4000L)
    }

    private fun tryActOnScreen(attempt: Int) {
        val root = rootInActiveWindow ?: run {
            finishGroup(false, "invalid")
            return
        }

        val result = tryClickJoinButton(root)
        root.recycle()

        when (result.second) {
            "joined", "requested" -> {
                alreadyActed = true
                // 1. Aperta back — fecha a tela de convite, volta ao WhatsApp normalmente
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 800L)
                // 2. Volta pro app On Groups
                handler.postDelayed({
                    returnToApp(true, result.second)
                }, 1800L)
            }
            "already_member" -> {
                alreadyActed = true
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ returnToApp(false, "invalid") }, 1800L)
            }
            else -> {
                if (attempt < 3) {
                    // Tenta mais uma vez após 2s
                    handler.postDelayed({ tryActOnScreen(attempt + 1) }, 2000L)
                } else {
                    // Sem botão — aperta back e marca inválido
                    alreadyActed = true
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500L)
                    handler.postDelayed({ returnToApp(false, "invalid") }, 1500L)
                }
            }
        }
    }

    private fun returnToApp(clicked: Boolean, type: String) {
        lastProcessedTime = System.currentTimeMillis()
        waitingForGroup = false
        try {
            val appIntent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            appIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            appIntent?.let { startActivity(it) }
        } catch (e: Exception) { }

        handler.postDelayed({
            MainActivity.instance?.onGroupProcessed(clicked, type)
        }, 600L)
    }

    private fun finishGroup(clicked: Boolean, type: String) {
        alreadyActed = true
        returnToApp(clicked, type)
    }

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        // Já é membro?
        for (text in listOf("você já é membro", "you're already a member", "já participa", "already a participant")) {
            if (root.findAccessibilityNodeInfosByText(text).isNotEmpty()) return Pair(false, "already_member")
        }
        // Entrar no grupo
        for (text in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "joined") }
                val p = node.parent
                if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "joined") }
                node.recycle()
            }
        }
        // Enviar pedido
        for (text in listOf("enviar pedido", "send request", "solicitar", "request to join")) {
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
        alreadyActed = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        if (waiting) {
            // Timeout de 20s — se travar, aperta back e volta
            timeoutRunnable = Runnable {
                if (waitingForGroup && !alreadyActed) {
                    alreadyActed = true
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ returnToApp(false, "invalid") }, 1000L)
                }
            }
            handler.postDelayed(timeoutRunnable!!, 20_000L)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}

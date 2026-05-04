package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedTime = 0L
    private val cooldown = 5000L
    private var waitingForGroup = false

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

        // Aguarda 3s para a tela carregar completamente
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            val isInvite = isGroupInviteScreen(root)

            if (isInvite) {
                val result = tryClickJoinButton(root)
                root.recycle()

                lastProcessedTime = System.currentTimeMillis()
                waitingForGroup = false

                // Volta pro app imediatamente depois da ação
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000L)

                handler.postDelayed({
                    MainActivity.instance?.onGroupProcessed(result.first, result.second)
                }, 1500L)
            } else {
                root.recycle()
                // Tela não era de convite — volta pro app e marca como inválido
                waitingForGroup = false
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000L)
                handler.postDelayed({
                    MainActivity.instance?.onGroupProcessed(false, "invalid")
                }, 1500L)
            }
        }, 3000L)
    }

    private fun isGroupInviteScreen(root: AccessibilityNodeInfo): Boolean {
        val inviteTexts = listOf(
            "entrar no grupo",
            "join group",
            "enviar pedido",
            "send request",
            "grupo do whatsapp",
            "whatsapp group",
            "convidado para",
            "you've been invited",
            "link de convite",
            "invite link"
        )
        for (text in inviteTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    // Retorna Pair(clicou, tipo: "joined" | "requested" | "not_found")
    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        // Botões de entrar diretamente
        val joinTexts = listOf("entrar no grupo", "join group", "entrar", "join")
        for (text in joinTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    return Pair(true, "joined")
                }
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return Pair(true, "joined")
                }
                node.recycle()
            }
        }

        // Botões de enviar pedido (grupo fechado)
        val requestTexts = listOf("enviar pedido", "send request", "solicitar", "request")
        for (text in requestTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    return Pair(true, "requested")
                }
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return Pair(true, "requested")
                }
                node.recycle()
            }
        }

        return Pair(false, "not_found")
    }

    fun setWaitingForGroup(waiting: Boolean) {
        waitingForGroup = waiting
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}

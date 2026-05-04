package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    companion object {
        var serviceInstance: GroupJoinerService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedTime = 0L
    private val cooldown = 5000L
    private var waitingForGroup = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return

        // Só age no WhatsApp normal ou Business
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        // Só processa se o app mandou abrir um link
        if (!waitingForGroup) return

        // Só age em mudança de janela (nova tela carregada)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        // Aguarda a tela carregar
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            // Verifica se é realmente a tela de convite de grupo
            val isGroupInviteScreen = isGroupInviteScreen(root)

            if (isGroupInviteScreen) {
                val joined = tryClickJoinButton(root)
                root.recycle()

                if (joined) {
                    lastProcessedTime = System.currentTimeMillis()
                    waitingForGroup = false
                    handler.postDelayed({
                        MainActivity.instance?.onGroupProcessed(true)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 2000L)
                } else {
                    waitingForGroup = false
                    handler.postDelayed({
                        MainActivity.instance?.onGroupProcessed(false)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 3000L)
                }
            } else {
                root.recycle()
            }
        }, 2500L)
    }

    private fun isGroupInviteScreen(root: AccessibilityNodeInfo): Boolean {
        // Verifica se a tela contém textos típicos da tela de convite
        val inviteTexts = listOf(
            "entrar no grupo",
            "join group",
            "grupo do whatsapp",
            "whatsapp group",
            "convidado para",
            "you've been invited"
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

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Boolean {
        val targetTexts = listOf(
            "entrar no grupo",
            "join group",
            "entrar",
            "join"
        )

        for (text in targetTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    return true
                }
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }
        return false
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

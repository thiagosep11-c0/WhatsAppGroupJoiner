package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedTime = 0L
    private val cooldown = 3000L // evita duplo clique

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return

        // Só age no WhatsApp normal ou Business
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        // Evita processar múltiplos eventos em sequência
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        // Aguarda a tela carregar completamente
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val joined = tryClickJoinButton(root)
            root.recycle()

            if (joined) {
                lastProcessedTime = System.currentTimeMillis()
                // Volta pro app após 2 segundos
                handler.postDelayed({
                    MainActivity.instance?.onGroupProcessed(true)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 2000L)
            } else {
                // Botão não encontrado = grupo inválido ou já é membro
                handler.postDelayed({
                    MainActivity.instance?.onGroupProcessed(false)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 3000L)
            }
        }, 2500L)
    }

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Boolean {
        // Textos possíveis do botão em PT e EN
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
                // Tenta o pai clicável
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

    override fun onInterrupt() {}
}

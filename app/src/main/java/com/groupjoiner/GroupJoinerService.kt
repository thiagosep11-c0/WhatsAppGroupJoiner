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
    private var alreadyActed = false

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
        if (alreadyActed) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        timeoutRunnable?.let { handler.removeCallbacks(it) }

        handler.postDelayed({
            if (!waitingForGroup || alreadyActed) return@postDelayed
            tryActOnScreen()
        }, 4000L)
    }

    private fun tryActOnScreen(attempt: Int = 1) {
        val root = rootInActiveWindow ?: run {
            closeAndReport(false, "invalid")
            return
        }

        val result = tryClickJoinButton(root)
        root.recycle()

        when (result.second) {
            "joined", "requested" -> {
                // Clicou com sucesso — fecha tela e volta pro app
                alreadyActed = true
                handler.postDelayed({
                    closeWhatsAppScreen()
                    handler.postDelayed({ returnToApp(result.first, result.second) }, 800L)
                }, 1000L)
            }
            "already_member" -> {
                // Já é membro — fecha e segue
                alreadyActed = true
                closeWhatsAppScreen()
                handler.postDelayed({ returnToApp(false, "invalid") }, 800L)
            }
            else -> {
                if (attempt < 3) {
                    // Tenta mais uma vez após 2s
                    handler.postDelayed({ tryActOnScreen(attempt + 1) }, 2000L)
                } else {
                    // Nenhum botão encontrado — fecha com X e marca como inválido
                    alreadyActed = true
                    closeWhatsAppScreen()
                    handler.postDelayed({ returnToApp(false, "invalid") }, 800L)
                }
            }
        }
    }

    private fun closeWhatsAppScreen() {
        // Tenta fechar pelo botão X primeiro
        val root = rootInActiveWindow
        if (root != null) {
            val closeTexts = listOf("fechar", "close", "cancelar", "cancel")
            for (text in closeTexts) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); root.recycle(); return }
                    node.recycle()
                }
            }
            // Procura botão de fechar percorrendo a árvore
            val closeNode = findClickableByDesc(root, listOf("fechar", "close", "back", "voltar", "x"))
            if (closeNode != null) {
                closeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                closeNode.recycle()
                root.recycle()
                return
            }
            root.recycle()
        }
        // Se não encontrou botão X, usa o back do sistema
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun returnToApp(clicked: Boolean, type: String) {
        try {
            val appIntent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            appIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            appIntent?.let { startActivity(it) }
        } catch (e: Exception) { }

        handler.postDelayed({
            MainActivity.instance?.onGroupProcessed(clicked, type)
        }, 600L)
    }

    private fun closeAndReport(clicked: Boolean, type: String) {
        alreadyActed = true
        closeWhatsAppScreen()
        handler.postDelayed({ returnToApp(clicked, type) }, 800L)
    }

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        // Verificar se já é membro
        val memberTexts = listOf("você já é membro", "you're already a member", "já participa", "already a participant")
        for (text in memberTexts) {
            if (root.findAccessibilityNodeInfosByText(text).isNotEmpty()) {
                return Pair(false, "already_member")
            }
        }

        // Botão Entrar
        for (text in listOf("entrar no grupo", "join group", "entrar", "join")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "joined") }
                val p = node.parent
                if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "joined") }
                node.recycle()
            }
        }

        // Botão Enviar Pedido
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
        if (waiting) {
            timeoutRunnable = Runnable {
                if (waitingForGroup && !alreadyActed) {
                    closeAndReport(false, "invalid")
                }
            }
            handler.postDelayed(timeoutRunnable!!, 18_000L)
        } else {
            timeoutRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    private fun findClickableByDesc(node: AccessibilityNodeInfo, descs: List<String>): AccessibilityNodeInfo? {
        val cd = node.contentDescription?.toString()?.lowercase() ?: ""
        val txt = node.text?.toString()?.lowercase() ?: ""
        if (node.isClickable && descs.any { cd.contains(it) || txt.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByDesc(child, descs)
            if (found != null) { child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}

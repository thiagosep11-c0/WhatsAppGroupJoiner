package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var isSendingMessage = false
    private var messageToSend = ""
    private var timeoutRunnable: Runnable? = null
    private var checkRunnable: Runnable? = null

    companion object {
        var serviceInstance: GroupJoinerService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun setWaiting(waiting: Boolean) {
        isActive = waiting
        hasReturned = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }

        if (waiting) {
            val delay = if (isSendingMessage) 4000L else 5000L
            scheduleCheck(delay, 1)

            // Timeout de 20s — SEMPRE volta pro app, sem exceção
            timeoutRunnable = Runnable {
                if (isActive) {
                    isActive = false
                    checkRunnable?.let { handler.removeCallbacks(it) }
                    // Força back e volta
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK) // segundo back por garantia
                        returnToApp("invalid")
                    }, 800L)
                }
            }
            handler.postDelayed(timeoutRunnable!!, 20_000L)
        }
    }

    fun setMessageMode(message: String) {
        isSendingMessage = true
        messageToSend = message
    }

    fun clearMessageMode() {
        isSendingMessage = false
        messageToSend = ""
    }

    // Modo verificação — checa se pedido foi aprovado
    private var checkingApproval = false

    fun setCheckApproval(checking: Boolean) {
        checkingApproval = checking
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

        val status = when {
            isSendingMessage -> tryTypeAndSendMessage(root)
            checkingApproval -> checkIfApproved(root)
            else -> detectAndAct(root)
        }
        root.recycle()

        when (status) {
            "joined", "requested", "pending", "already_member", "message_sent",
            "message_failed", "approved", "still_pending" -> {
                isActive = false
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                checkRunnable?.let { handler.removeCallbacks(it) }
                val finalStatus = when (status) {
                    "message_sent"   -> "message_sent"
                    "message_failed" -> "invalid"
                    "approved"       -> "approved"
                    "still_pending"  -> "still_pending"
                    else             -> status
                }
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1400L) // segundo back
                handler.postDelayed({ returnToApp(finalStatus) }, 2200L)
            }
            else -> {
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else finishWithBack("invalid")
            }
        }
    }

    // Verificação de aprovação: checa se pedido pendente foi aceito
    private fun checkIfApproved(root: AccessibilityNodeInfo): String {
        // 1. Se achar campo de texto do chat = foi aprovado
        val inputNode = findEditText(root)
        if (inputNode != null) {
            inputNode.recycle()
            return "approved"
        }

        // 2. Se mostrar cancelar pedido = ainda pendente
        for (t in listOf("cancelar pedido", "cancel request", "withdraw request", "cancelar solicitacao")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "still_pending"
        }

        // 3. Se mostrar botão entrar = aprovado mas ainda nao entrou (grupo aberto agora)
        for (t in listOf("entrar no grupo", "join group")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "still_pending"
        }

        // 4. Qualquer outro caso = trata como still_pending (nao fecha o app)
        // Só marca invalid se tiver texto de link inválido
        for (t in listOf("link invalido", "invalid link", "link expirado", "expired", "nao existe")) {
            if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "still_pending"
        }

        // Se nao identificou nada ainda, trata como still_pending para nao crashar
        return "still_pending"
    }

    // Fase 2: digitar e enviar mensagem no chat do grupo
    private fun tryTypeAndSendMessage(root: AccessibilityNodeInfo): String {
        // Procura campo de texto do chat (EditText)
        val inputNode = findEditText(root)

        if (inputNode == null) {
            // Chat ainda não carregou, tenta de novo
            return "not_found"
        }

        // Clica no campo
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Digita a mensagem
        val args = Bundle()
        args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageToSend)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        inputNode.recycle()

        // Aguarda 1.5s e pressiona enviar
        handler.postDelayed({
            val root2 = rootInActiveWindow ?: return@postDelayed
            val sent = clickSendButton(root2)
            root2.recycle()
            if (!sent) {
                // Tenta pressionar Enter como fallback
                val root3 = rootInActiveWindow ?: return@postDelayed
                val inputNode2 = findEditText(root3)
                inputNode2?.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                inputNode2?.recycle()
                root3.recycle()
            }
        }, 1500L)

        return "message_sent"
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
                queue.forEach { it.recycle() }
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        val sendKeywords = listOf("enviar", "send", "send message", "enviar mensagem")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cd = (node.contentDescription?.toString() ?: "").lowercase()
            val txt = (node.text?.toString() ?: "").lowercase()
            if (node.isClickable && sendKeywords.any { cd.contains(it) || txt.contains(it) }) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                queue.forEach { it.recycle() }
                return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    // Fase 1: detectar e clicar em Entrar/Enviar pedido
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
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return "not_found"
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
        val p = node.parent
        if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); return true }
        return false
    }

    private fun finishWithBack(status: String) {
        isActive = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK) // segundo back por garantia
            returnToApp(status)
        }, 800L)
    }

    private var hasReturned = false

    private fun returnToApp(status: String) {
        if (hasReturned) return  // evita dupla chamada
        hasReturned = true
        isActive = false

        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
        } catch (e: Exception) { }

        handler.postDelayed({
            hasReturned = false // reset para próximo grupo
            MainActivity.instance?.onGroupProcessed(status)
        }, 800L)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
    }
}

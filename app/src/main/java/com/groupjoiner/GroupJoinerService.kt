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
    private var isCheckingApproval = false
    private var messageToSend = ""
    private var hasReturned = false
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

    // --- Funções públicas chamadas pelo MainActivity ---

    fun setWaiting(waiting: Boolean) {
        isActive = waiting
        hasReturned = false
        cancelAll()
        if (waiting) {
            scheduleCheck(5000L, 1)
            timeoutRunnable = Runnable {
                if (isActive) safeFinish("invalid")
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

    fun setCheckApproval(checking: Boolean) {
        isCheckingApproval = checking
    }

    // --- Lógica interna ---

    private fun cancelAll() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        checkRunnable = null
    }

    private fun scheduleCheck(delayMs: Long, attempt: Int) {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = Runnable {
            if (isActive) tryActOnScreen(attempt)
        }
        handler.postDelayed(checkRunnable!!, delayMs)
    }

    private fun tryActOnScreen(attempt: Int) {
        if (!isActive) return

        try {
            val root = rootInActiveWindow
            if (root == null) {
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else safeFinish("invalid")
                return
            }

            val pkg = root.packageName?.toString() ?: ""
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
                safeRecycle(root)
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else safeFinish("invalid")
                return
            }

            val status = when {
                isSendingMessage  -> trySendMessage(root)
                isCheckingApproval -> tryCheckApproval(root)
                else              -> tryJoinGroup(root)
            }
            safeRecycle(root)

            when (status) {
                "not_found" -> {
                    if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                    else safeFinish("invalid")
                }
                else -> {
                    isActive = false
                    cancelAll()
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1500L)
                    handler.postDelayed({ returnToApp(status) }, 2300L)
                }
            }
        } catch (e: Exception) {
            // Qualquer crash — volta pro app com segurança
            safeFinish("invalid")
        }
    }

    private fun safeFinish(status: String) {
        isActive = false
        cancelAll()
        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) {}
        handler.postDelayed({ returnToApp(status) }, 1200L)
    }

    private fun returnToApp(status: String) {
        if (hasReturned) return
        hasReturned = true
        isActive = false
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
        } catch (e: Exception) {}
        handler.postDelayed({
            hasReturned = false
            MainActivity.instance?.onGroupProcessed(status)
        }, 600L)
    }

    // --- Fase 1: Entrar no grupo ---

    private fun tryJoinGroup(root: AccessibilityNodeInfo): String {
        return try {
            // Já é membro
            for (t in listOf("voce ja e membro", "you're already a member", "already a participant")) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "already_member"
            }
            // Pedido já enviado
            for (t in listOf("cancelar pedido", "cancel request", "withdraw request")) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "pending"
            }
            // Entrar no grupo
            for (t in listOf("entrar no grupo", "join group", "entrar", "join")) {
                val nodes = root.findAccessibilityNodeInfosByText(t)
                for (node in nodes) {
                    if (tryClickNode(node)) { safeRecycle(node); return "joined" }
                    safeRecycle(node)
                }
            }
            // Enviar pedido
            for (t in listOf("enviar pedido", "send request", "solicitar", "request to join")) {
                val nodes = root.findAccessibilityNodeInfosByText(t)
                for (node in nodes) {
                    if (tryClickNode(node)) { safeRecycle(node); return "requested" }
                    safeRecycle(node)
                }
            }
            // Varredura ampla
            scanForJoinButton(root)
        } catch (e: Exception) {
            "not_found"
        }
    }

    private fun scanForJoinButton(root: AccessibilityNodeInfo): String {
        return try {
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
                            queue.forEach { safeRecycle(it) }
                            return "joined"
                        }
                        requestKw.any { combined.contains(it) } -> {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            queue.forEach { safeRecycle(it) }
                            return "requested"
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            "not_found"
        } catch (e: Exception) {
            "not_found"
        }
    }

    // --- Fase 2: Enviar mensagem ---

    private fun trySendMessage(root: AccessibilityNodeInfo): String {
        return try {
            // Detectar se só admin pode enviar
            val adminTexts = listOf(
                "so administradores", "only admins", "apenas administradores",
                "admins can send", "somente administradores", "admin only",
                "so adm", "only administrators"
            )
            for (t in adminTexts) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "admin_only"
            }
            // Detectar campo desabilitado (campo existe mas nao é editável)
            val inputNode = findEditText(root)
            if (inputNode == null) {
                // Verifica se tem campo mas desabilitado
                val disabledInput = findDisabledInput(root)
                if (disabledInput != null) {
                    safeRecycle(disabledInput)
                    return "admin_only"
                }
                return "not_found"
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle()
            args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageToSend)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            safeRecycle(inputNode)

            handler.postDelayed({
                try {
                    val root2 = rootInActiveWindow ?: return@postDelayed
                    clickSendButton(root2)
                    safeRecycle(root2)
                } catch (e: Exception) {}
            }, 1500L)

            "message_sent"
        } catch (e: Exception) {
            "not_found"
        }
    }

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        return try {
            val sendKw = listOf("enviar", "send", "send message")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val cd = (node.contentDescription?.toString() ?: "").lowercase()
                val txt = (node.text?.toString() ?: "").lowercase()
                if (node.isClickable && sendKw.any { cd.contains(it) || txt.contains(it) }) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    queue.forEach { safeRecycle(it) }
                    return true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            false
        } catch (e: Exception) { false }
    }

    // --- Verificação de aprovação ---

    private fun tryCheckApproval(root: AccessibilityNodeInfo): String {
        return try {
            // Se achar campo de texto = foi aprovado
            val inputNode = findEditText(root)
            if (inputNode != null) {
                safeRecycle(inputNode)
                return "approved"
            }
            // Se mostrar cancelar pedido = ainda pendente
            for (t in listOf("cancelar pedido", "cancel request", "withdraw request")) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return "still_pending"
            }
            // Qualquer outro caso = still_pending (seguro)
            "still_pending"
        } catch (e: Exception) {
            "still_pending"
        }
    }

    // --- Helpers ---

    private fun findDisabledInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!node.isEnabled && node.className?.toString()?.contains("EditText") == true) {
                    queue.forEach { safeRecycle(it) }
                    return node
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            null
        } catch (e: Exception) { null }
    }

    // Sair do grupo: abre menu e clica em Sair
    var isSilentLeave = false

    fun setLeaveMode(leave: Boolean) {
        isSilentLeave = leave
    }

    private fun tryLeaveGroup(root: AccessibilityNodeInfo): String {
        return try {
            // Procura botão de menu (3 pontinhos)
            val menuKw = listOf("mais opcoes", "more options", "menu", "opcoes")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var found = false
            while (queue.isNotEmpty() && !found) {
                val node = queue.removeFirst()
                val cd = (node.contentDescription?.toString() ?: "").lowercase()
                if (node.isClickable && menuKw.any { cd.contains(it) }) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    queue.forEach { safeRecycle(it) }
                    found = true
                    break
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            if (!found) return "not_found"

            // Aguarda menu abrir e clica em Sair do grupo
            handler.postDelayed({
                try {
                    val root2 = rootInActiveWindow ?: return@postDelayed
                    for (t in listOf("sair do grupo", "exit group", "leave group")) {
                        val nodes = root2.findAccessibilityNodeInfosByText(t)
                        for (node in nodes) {
                            if (node.isClickable) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                safeRecycle(node)
                                // Confirmar saída
                                handler.postDelayed({
                                    try {
                                        val root3 = rootInActiveWindow ?: return@postDelayed
                                        for (t2 in listOf("sair", "exit", "leave", "confirmar", "ok")) {
                                            val nodes2 = root3.findAccessibilityNodeInfosByText(t2)
                                            for (n2 in nodes2) {
                                                if (n2.isClickable) {
                                                    n2.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                                    safeRecycle(n2)
                                                    break
                                                }
                                                safeRecycle(n2)
                                            }
                                        }
                                        safeRecycle(root3)
                                    } catch (e: Exception) {}
                                }, 1000L)
                                break
                            }
                            safeRecycle(node)
                        }
                    }
                    safeRecycle(root2)
                } catch (e: Exception) {}
            }, 1500L)

            "left_group"
        } catch (e: Exception) { "not_found" }
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
                    queue.forEach { safeRecycle(it) }
                    return node
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun tryClickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            val p = node.parent
            if (p?.isClickable == true) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(p)
                return true
            }
            false
        } catch (e: Exception) { false }
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        cancelAll()
    }
}

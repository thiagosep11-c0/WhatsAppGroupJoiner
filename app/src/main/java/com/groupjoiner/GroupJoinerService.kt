package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var isSendingMessage = false
    private var isCheckingApproval = false
    private var messageToSend = ""
    private var humanTyping = true
    private var typingWpm = 70
    private var hasReturned = false
    private var timeoutRunnable: Runnable? = null
    private var checkRunnable: Runnable? = null
    private var typingRunnable: Runnable? = null
    private var activeSeq = 0L

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
            activeSeq = RunRuntime.groupSeq
            RunRuntime.mentionNote = ""   // grupo novo: a marcação @todos começa do zero
            mentionTried = false
            scheduleCheck(5000L, 1)
            timeoutRunnable = Runnable {
                if (isActive) safeFinish(failureStatus())
            }
            handler.postDelayed(timeoutRunnable!!, 20_000L)
        }
    }

    // human = digitar palavra por palavra (como gente); wpm = palavras por minuto
    fun setMessageMode(message: String, human: Boolean = true, wpm: Int = 70) {
        isSendingMessage = true
        messageToSend = message
        humanTyping = human
        typingWpm = wpm
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
        typingRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        checkRunnable = null
        typingRunnable = null
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
                else safeFinish("retry_env")
                return
            }

            val pkg = root.packageName?.toString() ?: ""
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
                safeRecycle(root)
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else safeFinish("retry_env")
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
                "typing" -> {
                    // Digitação humana em andamento: os passos agendados (scheduleTyping) terminam o fluxo
                }
                else -> leaveChat(status)
            }
        } catch (e: Exception) {
            // Qualquer crash — volta pro app com segurança
            safeFinish("retry_env")
        }
    }

    private fun safeFinish(status: String) {
        isActive = false
        cancelAll()
        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) {}
        handler.postDelayed({ try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) {} }, 800L)
        handler.postDelayed({ returnToApp(status) }, 1600L)
    }

    private fun returnToApp(status: String) {
        if (hasReturned) return
        hasReturned = true
        isActive = false
        val seq = activeSeq
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            // o resultado também vai dentro do Intent: se a tela do app tiver sido fechada pelo sistema,
            // a nova tela recebe o resultado e o processo continua
            intent?.putExtra(MainActivity.EXTRA_STATUS, status)
            intent?.putExtra(MainActivity.EXTRA_SEQ, seq)
            intent?.let { startActivity(it) }
        } catch (e: Exception) {}
        handler.postDelayed({
            hasReturned = false
            MainActivity.instance?.onGroupProcessed(status, seq)
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
            if (humanTyping || hasMention(messageToSend)) {
                // Modo humano: lê o grupo, digita palavra por palavra, relê e envia (passos agendados abaixo).
                // Mensagem com @todos usa o mesmo caminho (precisa digitar aos poucos e tocar na opção "Todos"), mesmo sem digitação humana.
                safeRecycle(inputNode)
                startHumanTyping()
                return "typing"
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle()
            args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageToSend)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            safeRecycle(inputNode)

            handler.postDelayed({
                try {
                    val root2 = rootInActiveWindow ?: return@postDelayed
                    if (clickSendButton(root2)) markSentNow()
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

    // --- Fase 2 (modo humano): passos agendados ---
    // Sequência por grupo: "lê" o grupo -> toca no campo -> escreve palavra por palavra -> relê -> envia -> fica um instante -> sai.
    // Cada passo busca a tela de novo (os nós antigos ficam inválidos) e confere que ainda é o WhatsApp.

    // Tempos de cada passo: com digitação humana valem os tempos "de gente"; sem ela (só quando há @todos) ficam curtos
    private fun dRead(): Long = if (humanTyping) HumanTyping.readPauseMs() else 400L
    private fun dFocus(): Long = if (humanTyping) HumanTyping.focusPauseMs() else 250L
    private fun dWord(word: String): Long = if (humanTyping) HumanTyping.wordDelayMs(word, typingWpm) else 60L
    private fun dLetter(): Long = if (humanTyping) HumanTyping.letterDelayMs() else 40L
    private fun dBeforeSend(): Long = if (humanTyping) HumanTyping.beforeSendPauseMs() else 400L
    private fun dStay(): Long = if (humanTyping) HumanTyping.stayAfterSendMs() else 1_500L

    private fun startHumanTyping() {
        val words = HumanTyping.splitWords(messageToSend)
        mentionTried = false
        // O limite de 20 s do setWaiting() não serve para digitar: troca por um proporcional ao tamanho da mensagem
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val limit = Runnable { if (isActive) safeFinish("retry_env") }
        timeoutRunnable = limit
        handler.postDelayed(limit, 60_000L + words.size * 6_000L)
        scheduleTyping(dRead()) { typingFocus(words) }
    }

    private fun scheduleTyping(delayMs: Long, action: () -> Unit) {
        typingRunnable?.let { handler.removeCallbacks(it) }
        val step = Runnable {
            if (!isActive) return@Runnable
            try { action() } catch (e: Exception) { safeFinish("invalid") }
        }
        typingRunnable = step
        handler.postDelayed(step, delayMs)
    }

    // Acha o campo de mensagem na tela atual (só no WhatsApp) e roda [block] nele
    private fun withInputField(block: (AccessibilityNodeInfo) -> Unit): Boolean {
        val root = rootInActiveWindow
        return try {
            val pkg = root?.packageName?.toString() ?: ""
            var input = if (root != null && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) findEditText(root) else null
            // a lista de marcação (@todos) pode virar a "janela ativa": então o campo de digitar está em outra janela do WhatsApp
            if (input == null) input = findInputInOtherWindows()
            if (input == null) {
                false
            } else {
                try { block(input) } finally { safeRecycle(input) }
                true
            }
        } catch (e: Exception) {
            false
        } finally {
            safeRecycle(root)
        }
    }

    private fun findInputInOtherWindows(): AccessibilityNodeInfo? {
        return try {
            val todas = windows ?: return null
            for (w in todas) {
                val r = w.root ?: continue
                if (isWhatsAppRoot(r)) {
                    val found = findEditText(r)
                    if (found != null) return found
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun setInputText(input: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun typingFocus(words: List<String>) {
        val ok = withInputField { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        if (!ok) { safeFinish("retry_env"); return }
        scheduleTyping(dFocus() + dWord(words[0])) {
            typingWord(words, 0, "")
        }
    }

    // Mostra a palavra [index] (o tempo de "escrevê-la" já passou) e agenda a próxima
    private fun typingWord(words: List<String>, index: Int, typed: String) {
        // a primeira palavra @todos da mensagem vira marcação (as outras, se houver, ficam como texto)
        if (!mentionTried && isMentionWord(words[index])) {
            typingMention(words, index, typed)
            return
        }
        val text = typed + words[index]
        val ok = withInputField { setInputText(it, text) }
        if (!ok) { clearDraftAndFinish(); return }
        if (index + 1 < words.size) {
            scheduleTyping(dWord(words[index + 1])) { typingWord(words, index + 1, text) }
        } else {
            scheduleTyping(dBeforeSend()) { typingSend(1) }
        }
    }

    private fun typingSend(attempt: Int) {
        val root = rootInActiveWindow
        var clicked = false
        if (root != null) {
            clicked = clickSendButton(root)
            safeRecycle(root)
        }
        when {
            clicked -> {
                markSentNow() // anota na hora: se algo cair depois, o app não manda de novo nesse grupo
                scheduleTyping(dStay()) { leaveChat("message_sent") }
            }
            attempt < 3 -> scheduleTyping(900L) { typingSend(attempt + 1) }
            else -> clearDraftAndFinish()
        }
    }

    // Não conseguiu enviar (tela mudou / sem botão de enviar): apaga o que foi digitado para não deixar rascunho solto no grupo
    private fun clearDraftAndFinish() {
        try { withInputField { setInputText(it, "") } } catch (e: Exception) {}
        safeFinish("retry_env")
    }

    // ---------------- Marcação @todos (fase 2) ----------------
    // Quando a mensagem tem a palavra @todos SOZINHA (com espaço antes e depois, ou no começo/fim), o app digita "@todos" letra por
    // letra, espera o WhatsApp mostrar a opção "Todos" (só aparece em grupos pequenos, até 32 participantes) e toca nela.
    // Se a opção não aparecer, tira o "@todos" do campo e envia o resto da mensagem (o motivo vai para o registro).
    // Depois da marcação o campo guarda um pedaço "especial": reescrever o texto inteiro apagaria a marcação. Por isso as palavras
    // que vêm DEPOIS dela são coladas no fim do campo (via área de transferência) em vez de reescrever tudo.

    private val mentionLabels = setOf("todos", "@todos", "everyone", "@everyone", "all", "@all")
    private val mentionTries = 6
    private var mentionTried = false
    private var mentionTouched = false

    private fun isMentionWord(piece: String): Boolean = piece.trim().equals("@todos", ignoreCase = true)

    private fun hasMention(message: String): Boolean = HumanTyping.splitWords(message).any { isMentionWord(it) }

    private fun typingMention(words: List<String>, index: Int, typed: String) {
        mentionTried = true
        mentionTouched = false
        typingMentionLetter(words, index, typed, words[index].trim(), 1)
    }

    // Escreve "@todos" aos poucos (@, @t, @to ...): é assim que o WhatsApp abre e filtra a lista de marcação
    private fun typingMentionLetter(words: List<String>, index: Int, typed: String, token: String, k: Int) {
        val ok = withInputField { setInputText(it, typed + token.substring(0, k)) }
        if (!ok) { clearDraftAndFinish(); return }
        if (k < token.length) {
            scheduleTyping(dLetter()) { typingMentionLetter(words, index, typed, token, k + 1) }
        } else {
            scheduleTyping(1_000L) { typingMentionPick(words, index, typed, 1, false) }
        }
    }

    // Procura a opção "Todos" na lista e toca nela; na volta confere se a lista sumiu (= a marcação entrou)
    private fun typingMentionPick(words: List<String>, index: Int, typed: String, attempt: Int, clicked: Boolean) {
        val roots = mentionRoots()
        var found: AccessibilityNodeInfo? = null
        for (r in roots) {
            found = findMentionOption(r)
            if (found != null) break
        }
        val option = found
        if (option == null) {
            when {
                clicked -> typingMentionDone(words, index)
                attempt < mentionTries -> scheduleTyping(700L) { typingMentionPick(words, index, typed, attempt + 1, false) }
                else -> typingMentionGiveUp(words, index, typed, "a opção Todos não apareceu (grupo com mais de 32 participantes?)", roots)
            }
            return
        }
        if (attempt >= mentionTries) {
            val why = if (mentionTouched) "toquei na opção Todos mas a lista não fechou" else "não consegui tocar na opção Todos"
            typingMentionGiveUp(words, index, typed, why, roots)
            return
        }
        val tocou = clickWithAncestors(option)
        if (tocou) mentionTouched = true
        scheduleTyping(800L) { typingMentionPick(words, index, typed, attempt + 1, tocou) }
    }

    // A marcação entrou: segue com o resto da mensagem (colando) ou, se era a última palavra, vai para o envio
    private fun typingMentionDone(words: List<String>, index: Int) {
        RunRuntime.mentionNote = "marcou @todos"
        val next = index + 1
        if (next < words.size) {
            // a quebra de linha que vinha depois do @todos não sobrevive à marcação: devolve na primeira palavra seguinte
            val nl = words[index].substring(words[index].trimEnd().length).filter { it == '\n' }
            val w2 = if (nl.isEmpty()) words else words.toMutableList().also { it[next] = nl + it[next] }
            scheduleTyping(dWord(w2[next])) { typingPaste(w2, next) }
        } else {
            scheduleTyping(dBeforeSend()) { typingSend(1) }
        }
    }

    // Não deu para marcar: tira o "@todos" do campo e segue com o resto da mensagem (a quebra de linha que vinha depois dele fica)
    private fun typingMentionGiveUp(words: List<String>, index: Int, typed: String, why: String, roots: List<AccessibilityNodeInfo>) {
        RunRuntime.mentionNote = "sem marcar @todos ($why)"
        dumpMentionScreen(roots)
        val temQuebra = words[index].substring(words[index].trimEnd().length).contains('\n')
        val seguir = if (temQuebra) typed + "\n" else typed
        withInputField { setInputText(it, typed) }
        val next = index + 1
        when {
            next < words.size -> scheduleTyping(dWord(words[next])) { typingWord(words, next, seguir) }
            typed.isBlank() -> {
                RunLog.add(applicationContext, "⚠ a mensagem só tinha @todos e a marcação não foi possível: nada para enviar")
                safeFinish("invalid")
            }
            else -> scheduleTyping(dBeforeSend()) { typingSend(1) }
        }
    }

    // Depois da marcação: cola a próxima palavra no fim do campo (reescrever o texto inteiro apagaria a marcação)
    private fun typingPaste(words: List<String>, index: Int) {
        val piece = words[index]
        var before = ""
        var glue = ""
        var pasted = false
        val ok = withInputField { input ->
            before = input.text?.toString() ?: ""
            glue = if (before.isNotEmpty() && !before.last().isWhitespace() && !piece.first().isWhitespace()) " " else ""
            pasted = pasteAtEnd(input, glue + piece)
        }
        if (!ok) { clearDraftAndFinish(); return }
        scheduleTyping(350L) { typingPasteCheck(words, index, before, glue, pasted) }
    }

    // Põe [text] na área de transferência e manda o campo colar (o cursor fica no fim depois da marcação)
    private fun pasteAtEnd(input: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("On Groups", text))
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    // Confere se a palavra colada apareceu no fim do campo. Se não, escreve o texto todo de uma vez:
    // a marcação vira texto simples, mas a mensagem sai completa (melhor do que sair faltando pedaço).
    private fun typingPasteCheck(words: List<String>, index: Int, before: String, glue: String, pasted: Boolean) {
        val piece = words[index]
        val ok = withInputField { input ->
            val now = input.text?.toString() ?: ""
            val entrou = pasted && now.length > before.length && now.trimEnd().endsWith(piece.trimEnd())
            if (!entrou) {
                setInputText(input, before + glue + piece)
                RunRuntime.mentionNote = "⚠ @todos virou texto simples (não consegui colar o resto da mensagem)"
            }
        }
        if (!ok) { clearDraftAndFinish(); return }
        val next = index + 1
        if (next < words.size) scheduleTyping(dWord(words[next])) { typingPaste(words, next) }
        else scheduleTyping(dBeforeSend()) { typingSend(1) }
    }

    // Janelas do WhatsApp a olhar: a tela ativa e, se o Android listar, janelas extras (a lista de marcação pode ser flutuante)
    private fun mentionRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            val ativa = rootInActiveWindow
            if (ativa != null && isWhatsAppRoot(ativa)) list.add(ativa)
        } catch (e: Exception) {}
        try {
            val todas = windows
            if (todas != null) {
                for (w in todas) {
                    val r = w.root
                    if (r != null && isWhatsAppRoot(r) && list.none { it == r }) list.add(r)
                }
            }
        } catch (e: Exception) {}
        return list
    }

    // Acha, na tela, o item "Todos" da lista de marcação (nunca o campo de digitar). Havendo mais de um, o mais baixo (perto do campo).
    private fun findMentionOption(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var exact: AccessibilityNodeInfo? = null
        var exactTop = Int.MIN_VALUE
        var loose: AccessibilityNodeInfo? = null
        var looseTop = Int.MIN_VALUE
        val r = Rect()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 600) {
            val n = q.removeFirst()
            seen++
            val cls = n.className?.toString() ?: ""
            if (n.isVisibleToUser && !n.isEditable && !cls.contains("EditText")) {
                val t = norm(n.text?.toString() ?: "").trim()
                val d = norm(n.contentDescription?.toString() ?: "").trim()
                if (t in mentionLabels || d in mentionLabels) {
                    n.getBoundsInScreen(r)
                    if (r.top > exactTop) { exact = n; exactTop = r.top }
                } else if ((t.startsWith("todos") || t.startsWith("@todos")) && t.length <= 60) {
                    n.getBoundsInScreen(r)
                    if (r.top > looseTop) { loose = n; looseTop = r.top }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return exact ?: loose
    }

    // Toca no nó ou, se ele não for clicável, no primeiro "pai" clicável (a linha inteira da lista)
    private fun clickWithAncestors(node: AccessibilityNodeInfo): Boolean {
        return try {
            var cur: AccessibilityNodeInfo? = node
            var level = 0
            while (cur != null && level < 6) {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                cur = cur.parent
                level++
            }
            false
        } catch (e: Exception) { false }
    }

    private fun maskKeep(s: String): String = if (norm(s).trim() in mentionLabels) s else mask(s.take(30))

    // Quando a marcação falha, grava no registro a estrutura da tela (sem nomes: letras viram x) para eu calibrar a busca. Máx. 3 por sessão.
    private fun dumpMentionScreen(roots: List<AccessibilityNodeInfo>) {
        if (RunRuntime.mentionDumps >= 3) return
        RunRuntime.mentionDumps++
        try {
            val sb = StringBuilder("🔍 Diagnóstico da marcação @todos (letras trocadas por x; só \"Todos\" fica legível)")
            var n = 0
            val b = Rect()
            for ((wi, root) in roots.withIndex()) {
                sb.append("\n   [janela ${wi + 1}] classe | id | texto | descrição | toque | topo-fundo")
                val q = ArrayDeque<AccessibilityNodeInfo>()
                q.add(root)
                while (q.isNotEmpty() && n < 70) {
                    val node = q.removeFirst()
                    val txt = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    if (txt.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
                        node.getBoundsInScreen(b)
                        val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
                        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
                        sb.append("\n   $cls | $id | ${maskKeep(txt)} | ${maskKeep(desc)} | ${if (node.isClickable) "toque" else "-"} | ${b.top}-${b.bottom}")
                        n++
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
                }
            }
            RunLog.add(applicationContext, sb.toString())
        } catch (e: Exception) {}
    }

    // Falha sem causa clara: se o WhatsApp não está na frente, o problema é do ambiente (tela/WhatsApp), não do link
    private fun failureStatus(): String {
        return try {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""
            safeRecycle(root)
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") "invalid" else "retry_env"
        } catch (e: Exception) { "retry_env" }
    }

    // Chamado pelo vigia: traz o app para a frente e manda ele retomar o processo
    fun relaunchApp(recover: Boolean) {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (recover) intent?.putExtra(MainActivity.EXTRA_RECOVER, true)
            intent?.let { startActivity(it) }
        } catch (e: Exception) {}
    }

    // Cancela o que estiver em andamento (grupo atual: timers e digitação; escaneamento) e apaga rascunho, se houver
    fun abortCurrent() {
        isActive = false
        cancelAll()
        // derruba também tudo o que já estava agendado (voltar, devolver o resultado, passos do escaneamento):
        // nada do processo finalizado pode disparar depois e atrapalhar o próximo
        handler.removeCallbacksAndMessages(null)
        scanning = false
        scanRunnable = null
        scanTimeout = null
        scanPkg = ""
        hasReturned = false
        try { withInputField { setInputText(it, "") } } catch (e: Exception) {}
    }

    // ---------------- Escanear: lê a lista de conversas do WhatsApp (SÓ LEITURA: não envia nada) ----------------
    // Passos: acha a lista de conversas -> liga o filtro "Grupos" -> lê as linhas rolando a lista -> volta o filtro
    // para "Todas" -> salva o resultado e traz o app para a frente.

    private var scanning = false
    private var scanRunnable: Runnable? = null
    private var scanTimeout: Runnable? = null
    private val scanRows = LinkedHashMap<String, ScanRow>()
    private val scanNotes = mutableListOf<String>()
    private var scanDiag = ""
    private var scanPkg = ""
    private var scanPass = 0
    private var scanNoNew = 0
    private var scanChipClicked = false

    private class NodeInfo(val cls: String, val id: String, val text: String, val desc: String, val clickable: Boolean)

    private val timeLike = Regex("""^(\d{1,2}[:h]\d{2}|\d{1,2}/\d{1,2}(/\d{2,4})?|ontem|hoje|yesterday|today|seg|ter|qua|qui|sex|sab|dom|mon|tue|wed|thu|fri|sat|sun)\.?$""")
    private val onlyDigits = Regex("""\d{1,4}\+?""")
    private val unreadRe1 = Regex("""(\d+)\s+(?:mensagens?|messages?)\s+(?:nao\s+lidas?|unread)""")
    private val unreadRe2 = Regex("""(\d+)\s+unread""")

    fun startScan(pkg: String) {
        if (scanning) return
        scanPkg = pkg
        isActive = false
        cancelAll()
        scanning = true
        scanRows.clear()
        scanNotes.clear()
        scanDiag = ""
        scanPass = 0
        scanNoNew = 0
        scanChipClicked = false
        scanTimeout?.let { handler.removeCallbacks(it) }
        val t = Runnable {
            if (scanning) {
                scanNotes.add("tempo esgotado (3 min)")
                finishScan()
            }
        }
        scanTimeout = t
        handler.postDelayed(t, 180_000L)
        scheduleScan(3_000L) { scanPrepare(1) }
    }

    private fun scheduleScan(delayMs: Long, action: () -> Unit) {
        scanRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!scanning) return@Runnable
            try {
                action()
            } catch (e: Exception) {
                scanNotes.add("erro: ${e.message}")
                finishScan()
            }
        }
        scanRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun isWhatsAppRoot(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        if (scanPkg.isNotEmpty()) return pkg == scanPkg // durante o escaneamento só vale o WhatsApp escolhido
        return pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun mask(s: String): String =
        s.map { if (it.isLetter()) 'x' else if (it.isDigit()) '9' else it }.joinToString("")

    private fun maskDesc(s: String): String {
        val t = norm(s)
        return if (unreadRe1.containsMatchIn(t) || unreadRe2.containsMatchIn(t) || t.contains("nao lida")) s else mask(s)
    }

    /** Garante que a lista de conversas está na tela: sai de um chat com BACK (no máximo 2 vezes) e reabre o WhatsApp se ele saiu. */
    private fun scanPrepare(attempt: Int) {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppRoot(root)) {
            safeRecycle(root)
            if (attempt >= 8) {
                scanNotes.add("o WhatsApp não abriu na frente")
                finishScan()
                return
            }
            if (attempt == 3 || attempt == 6) {
                try {
                    val i = packageManager.getLaunchIntentForPackage(scanPkg)
                        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(scanPkg)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {}
            }
            scheduleScan(1_500L) { scanPrepare(attempt + 1) }
            return
        }
        val composer = findComposer(root)
        safeRecycle(composer)
        safeRecycle(root)
        if (composer != null) {
            if (attempt <= 2) {
                scanNotes.add("estava dentro de uma conversa: voltei para a lista")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleScan(1_400L) { scanPrepare(attempt + 1) }
            } else {
                scanNotes.add("não consegui sair da conversa: abra a lista de Conversas no WhatsApp e tente de novo")
                finishScan()
            }
            return
        }
        scanChip()
    }

    /** Campo de digitar mensagem de um chat (a lista de conversas não tem). */
    private fun findComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isEditable && (n.className?.toString() ?: "").contains("EditText")) {
                val dica = if (Build.VERSION.SDK_INT >= 26) (n.hintText?.toString() ?: "") else ""
                val t = norm((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "") + " " + dica)
                if (t.contains("mensagem") || t.contains("message")) return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    /** A lista principal da tela: o RecyclerView/ListView com mais itens. */
    private fun findListNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val cls = n.className?.toString() ?: ""
            if ((cls.contains("RecyclerView") || cls.contains("ListView")) && n.childCount > (best?.childCount ?: -1)) best = n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return best
    }

    /** Botão de filtro (chip) pelo texto; não procura dentro da lista de conversas. */
    private fun findChip(root: AccessibilityNodeInfo, nomes: List<String>): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val cls = n.className?.toString() ?: ""
            if (cls.contains("RecyclerView") || cls.contains("ListView")) continue
            val t = norm((n.text?.toString() ?: "").trim())
            val d = norm((n.contentDescription?.toString() ?: "").trim())
            if ((t in nomes || d in nomes) && (n.isClickable || n.parent?.isClickable == true)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun scanChip() {
        val root = rootInActiveWindow
        if (root == null) {
            scanNotes.add("sem janela do WhatsApp")
            finishScan()
            return
        }
        val chip = findChip(root, listOf("grupos", "groups"))
        if (chip != null) {
            val ok = tryClickNode(chip)
            scanChipClicked = ok
            scanNotes.add(if (ok) "filtro Grupos ligado" else "achei o filtro Grupos mas não consegui tocar nele")
        } else {
            scanNotes.add("filtro Grupos NÃO encontrado: li todas as conversas (podem entrar contatos pessoais)")
        }
        safeRecycle(root)
        scheduleScan(1_600L) { scanToTop(0) }
    }

    /** Antes de ler, rola a lista até o TOPO (ela pode estar no meio): assim o escaneamento cobre todos os grupos, de cima até embaixo. */
    private fun scanToTop(n: Int) {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppRoot(root)) {
            safeRecycle(root)
            scanNotes.add("saí do WhatsApp ao subir a lista")
            finishScan()
            return
        }
        val list = findListNode(root)
        val moveu = list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
        safeRecycle(list)
        safeRecycle(root)
        if (moveu && n < 60) {
            scheduleScan(350L) { scanToTop(n + 1) }
        } else {
            if (n > 0) scanNotes.add("subi a lista até o topo ($n rolagens)")
            scheduleScan(700L) { scanRead() }
        }
    }

    private fun flatten(node: AccessibilityNodeInfo, depth: Int, out: MutableList<NodeInfo>) {
        if (depth > 7 || out.size >= 60) return
        out.add(
            NodeInfo(
                node.className?.toString() ?: "",
                node.viewIdResourceName ?: "",
                node.text?.toString() ?: "",
                node.contentDescription?.toString() ?: "",
                node.isClickable
            )
        )
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            flatten(c, depth + 1, out)
            safeRecycle(c)
        }
    }

    /** Transforma uma linha da lista em (nome, não lidas). Devolve null se não parece uma conversa. */
    private fun parseRow(infos: List<NodeInfo>): ScanRow? {
        val textos = infos.filter { it.text.isNotBlank() }
        if (textos.size < 2) return null
        val porId = infos.firstOrNull { it.text.isNotBlank() && (it.id.contains("contact_name") || it.id.endsWith("/name")) }
        val candidato = porId ?: textos.firstOrNull {
            val t = norm(it.text.trim())
            !timeLike.matches(t) && !t.matches(onlyDigits)
        }
        val nome = candidato?.text?.trim() ?: return null
        if (norm(nome) == "arquivadas" || norm(nome) == "archived") return null

        var unread = 0
        var approx = false
        var achou = false
        for (n in infos) {
            for (s in listOf(n.desc, n.text)) {
                if (s.isBlank()) continue
                val t = norm(s)
                val m = unreadRe1.find(t) ?: unreadRe2.find(t)
                if (m != null) {
                    unread = m.groupValues[1].toIntOrNull() ?: 0
                    achou = true
                    break
                }
            }
            if (achou) break
        }
        if (!achou) {
            val c = infos.firstOrNull { it.text.matches(onlyDigits) && (it.id.contains("count") || it.id.contains("unread")) }
            if (c != null) {
                unread = c.text.filter { it.isDigit() }.toIntOrNull() ?: 0
                achou = true
            }
        }
        if (!achou && infos.any { norm(it.desc).contains("nao lida") }) {
            unread = 1
            approx = true
        }
        return ScanRow(nome, unread, approx)
    }

    private fun scanRead() {
        scanPass++
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppRoot(root)) {
            safeRecycle(root)
            scanNotes.add("saí do WhatsApp durante a leitura")
            finishScan()
            return
        }
        val list = findListNode(root)
        if (list == null) {
            scanNotes.add("lista de conversas não reconhecida na tela")
            scanDiag = buildDiag(root, null)
            safeRecycle(root)
            finishScan()
            return
        }
        var added = 0
        val diagRows = mutableListOf<List<NodeInfo>>()
        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue
            val infos = mutableListOf<NodeInfo>()
            flatten(row, 0, infos)
            safeRecycle(row)
            val parsed = parseRow(infos) ?: continue
            if (diagRows.size < 2) diagRows.add(infos)
            val old = scanRows[parsed.name]
            if (old == null) {
                scanRows[parsed.name] = parsed
                added++
            } else if (parsed.unread > old.unread) {
                scanRows[parsed.name] = parsed
            }
        }
        if (scanPass == 1) scanDiag = buildDiag(root, diagRows)
        if (added == 0) scanNoNew++ else scanNoNew = 0
        val podeRolar = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        safeRecycle(list)
        safeRecycle(root)
        if (scanNoNew >= 2 || !podeRolar || scanPass >= 80) {
            scheduleScan(300L) { scanRestoreChip() }
        } else {
            scheduleScan(900L) { scanRead() }
        }
    }

    /** Devolve o filtro do WhatsApp para "Todas" (se o app tinha ligado "Grupos"). */
    private fun scanRestoreChip() {
        if (scanChipClicked) {
            val root = rootInActiveWindow
            if (root != null && isWhatsAppRoot(root)) {
                val chip = findChip(root, listOf("todas", "all"))
                if (chip != null) tryClickNode(chip)
            }
            safeRecycle(root)
        }
        scheduleScan(800L) { finishScan() }
    }

    /** Diagnóstico para calibrar a leitura: estrutura da tela e de 2 linhas, com letras trocadas por x e números por 9. */
    private fun buildDiag(root: AccessibilityNodeInfo, rows: List<List<NodeInfo>>?): String {
        val sb = StringBuilder()
        sb.append("Diagnóstico (letras trocadas por x e números por 9)\n")
        sb.append("--- tela (classe | id | filhos | rolável)\n")
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var cont = 0
        while (q.isNotEmpty() && cont < 40) {
            val n = q.removeFirst()
            cont++
            sb.append("${n.className} | ${n.viewIdResourceName ?: "-"} | ${n.childCount} | ${n.isScrollable}\n")
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        rows?.forEachIndexed { idx, infos ->
            sb.append("--- linha ${idx + 1} (classe | id | texto | descrição | toque)\n")
            infos.forEach {
                sb.append("${it.cls} | ${it.id.ifEmpty { "-" }} | ${mask(it.text)} | ${maskDesc(it.desc)} | ${it.clickable}\n")
            }
        }
        return sb.toString()
    }

    private fun finishScan() {
        if (!scanning) return
        scanning = false
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
        scanTimeout?.let { handler.removeCallbacks(it) }
        scanTimeout = null
        val rows = scanRows.values.toList()
        val nota = scanNotes.joinToString("; ")
        try { ScanStore.save(applicationContext, scanPkg, ScanResult(rows, nota, scanDiag, System.currentTimeMillis())) } catch (e: Exception) {}
        scanPkg = ""
        RunLog.add(applicationContext, "🔍 Escaneamento concluído: ${rows.size} conversas lidas" + (if (nota.isNotEmpty()) " ($nota)" else ""))
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.putExtra(MainActivity.EXTRA_SCAN_DONE, true)
            intent?.let { startActivity(it) }
        } catch (e: Exception) {}
    }

    private fun markSentNow() {
        val link = RunRuntime.currentLink ?: return
        val pkg = RunRuntime.currentPkg.ifEmpty { "com.whatsapp" }
        try { StorageManager.markSent(applicationContext, link, pkg) } catch (e: Exception) {}
    }

    // Sai do chat (BACK x2), volta ao app e informa o resultado
    private fun leaveChat(status: String) {
        isActive = false
        cancelAll()
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800L)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1500L)
        handler.postDelayed({ returnToApp(status) }, 2300L)
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

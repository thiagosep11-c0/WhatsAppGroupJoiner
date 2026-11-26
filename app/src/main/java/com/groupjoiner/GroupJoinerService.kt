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
import android.view.accessibility.AccessibilityWindowInfo
import java.text.Normalizer
import kotlin.random.Random

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var isSendingMessage = false
    private var isCheckingApproval = false
    private var messageToSend = ""
    private var photoPlanned = false   // esta fase manda uma foto antes do texto em cada grupo
    private var captionMode = false    // o texto vai como legenda da foto (escrito na prévia, antes de tocar em Enviar)
    private var captionPending = false // a legenda está sendo escrita: quando tocar em Enviar, a foto também conta como enviada
    private var captionTyping = false  // escrevendo na caixa de LEGENDA da prévia: os passos do texto usam essa caixa, não a do chat
    private var captionSkip = false    // neste grupo a legenda não deu: a foto vai sem legenda e o texto logo depois, no chat
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
            captionPending = false        // grupo novo: nenhuma legenda em andamento
            captionTyping = false
            captionSkip = false
            RunRuntime.mentionNote = ""   // grupo novo: a marcação @todos começa do zero
            RunRuntime.step = "esperando o WhatsApp abrir o grupo"
            mentionTried = false
            scheduleCheck(5000L, 1)
            timeoutRunnable = Runnable {
                if (isActive) safeFinish(failureStatus())
            }
            handler.postDelayed(timeoutRunnable!!, 20_000L)
        }
    }

    // human = digitar palavra por palavra (como gente); wpm = palavras por minuto; photo = mandar uma foto em cada grupo;
    // caption = o texto vai como LEGENDA da foto (uma mensagem só); sem caption o texto vai logo depois da foto
    fun setMessageMode(message: String, human: Boolean = true, wpm: Int = 70, photo: Boolean = false, caption: Boolean = false) {
        isSendingMessage = true
        messageToSend = message
        humanTyping = human
        typingWpm = wpm
        photoPlanned = photo
        captionMode = caption
    }

    fun clearMessageMode() {
        isSendingMessage = false
        messageToSend = ""
        photoPlanned = false
        captionMode = false
        captionPending = false
        captionTyping = false
        captionSkip = false
        KeyboardBridge.pending = null
        imeRestore()   // se a rodada acabou no meio da foto, o teclado do usuário volta
        PhotoStore.cleanupLater(applicationContext, 20_000L)   // a última foto sai da galeria depois que o WhatsApp terminar com ela
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
            // só vale o WhatsApp ESCOLHIDO (normal ou Business): nunca age no outro
            if (!isTargetWhatsApp(pkg)) {
                safeRecycle(root)
                if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                else safeFinish("retry_env")
                return
            }

            // WhatsApp com a conta banida ou sem cadastro: não adianta seguir errando grupo por grupo; pausa com o motivo
            val saude = WaHealth.classify(root)
            if (saude == WaHealth.BANNED || saude == WaHealth.NO_ACCOUNT) {
                safeRecycle(root)
                WaHealth.set(applicationContext, pkg, saude)
                RunRuntime.stopNote = WaHealth.pauseText(pkg, saude)
                RunLog.add(applicationContext, "⛔ " + RunRuntime.stopNote)
                safeFinish(if (saude == WaHealth.BANNED) "wa_banned" else "wa_noaccount")
                return
            }

            // mensagens: se o chat anterior pode ter ficado aberto, não escreve aqui (a mensagem podia ir para o grupo errado):
            // sai do WhatsApp e refaz este grupo do zero
            if (isSendingMessage && !RunRuntime.leftChatOk) {
                safeRecycle(root)
                RunLog.add(applicationContext, "⚠ O chat anterior pode ter ficado aberto: saio do WhatsApp e refaço este grupo")
                safeFinish("retry_env")
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
                    // a tela não mostrou nada conhecido: é FALHA (vai para o Retentar falhas do painel), não link inválido.
                    // Link inválido de verdade é reconhecido pelo texto do WhatsApp (tryJoinGroup).
                    if (attempt < 5) scheduleCheck(2000L, attempt + 1)
                    else safeFinish("error")
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
        exitAndReturn(status)
    }

    // ---------------- Saída segura do WhatsApp e volta ao app ----------------
    // Antes: dois toques em Voltar "às cegas". Se o WhatsApp já tinha saído (ou nunca abriu), o Voltar caía no PRÓPRIO app e o fechava.
    // Agora: só aperta Voltar enquanto o WhatsApp escolhido estiver na frente, confere a cada passo e para assim que sair do chat.

    private class Front(val known: Boolean, val whatsapp: Boolean, val composer: Boolean)

    private fun readFront(): Front {
        val root: AccessibilityNodeInfo? = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root == null) return Front(false, false, false)
        return try {
            val wa = isTargetRoot(root)
            val input = if (wa) findChatInput(root) else null
            val composer = input != null
            safeRecycle(input)
            Front(true, wa, composer)
        } catch (e: Exception) {
            Front(false, false, false)
        } finally {
            safeRecycle(root)
        }
    }

    private fun exitAndReturn(status: String) {
        setStep("saindo do WhatsApp")
        isActive = false
        cancelAll()
        // um instante para o que acabou de ser tocado (Enviar, Entrar...) terminar antes de sair
        handler.postDelayed({ exitStep(status, 0, 0) }, 800L)
    }

    private fun exitStep(status: String, backs: Int, unknown: Int) {
        val f = readFront()
        when {
            !f.known -> {
                // janela em transição: espera um pouco; se continuar sem ler, assume que o WhatsApp não está na frente
                if (unknown < 3) handler.postDelayed({ exitStep(status, backs, unknown + 1) }, 500L)
                else finishExit(status, true)
            }
            !f.whatsapp -> finishExit(status, true)                 // outro app na frente (o nosso ou a tela inicial): NÃO aperta Voltar
            !f.composer && backs >= 2 -> finishExit(status, true)   // já está fora do chat (2 toques: o 1º pode só ter fechado o teclado)
            backs >= 5 -> finishExit(status, false)                 // 5 toques e ainda está no chat
            else -> {
                try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) {}
                handler.postDelayed({ exitStep(status, backs + 1, 0) }, 900L)
            }
        }
    }

    private fun finishExit(status: String, left: Boolean) {
        RunRuntime.leftChatOk = left
        if (!left) RunLog.add(applicationContext, "⚠ Não consegui sair da conversa do WhatsApp (5 toques em Voltar). Volto ao app mesmo assim")
        returnToApp(status)
    }

    private fun returnToApp(status: String) {
        if (hasReturned) return
        hasReturned = true
        isActive = false
        setStep("voltando ao app")
        val seq = activeSeq
        // o resultado também vai dentro do Intent: se a tela do app tiver sido fechada pelo sistema,
        // a nova tela recebe o resultado e o processo continua
        bringAppToFront(1) { intent ->
            intent.putExtra(MainActivity.EXTRA_STATUS, status)
            intent.putExtra(MainActivity.EXTRA_SEQ, seq)
        }
        handler.postDelayed({
            hasReturned = false
            MainActivity.instance?.onGroupProcessed(status, seq)
        }, 600L)
    }

    private var lastFrontAlertAt = 0L

    /** Traz o app para a frente e CONFERE: se não apareceu, tenta de novo (até 3 vezes); no fim avisa com uma notificação para tocar. */
    private fun bringAppToFront(attempt: Int, extras: ((Intent) -> Unit)?) {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null && attempt == 1 && extras != null) extras(intent)
            intent?.let { startActivity(it) }
        } catch (e: Exception) {}
        handler.postDelayed({
            if (MainActivity.isForeground) return@postDelayed
            if (attempt < 3) {
                bringAppToFront(attempt + 1, null)
            } else {
                RunLog.add(
                    applicationContext,
                    "⚠ O app não voltou para a frente sozinho (o celular pode estar bloqueando janelas em segundo plano: veja a aba Config)"
                )
                val agora = System.currentTimeMillis()
                if (agora - lastFrontAlertAt > 5 * 60_000L) {
                    lastFrontAlertAt = agora
                    ForegroundService.alert(
                        applicationContext, "On Groups ficou em segundo plano",
                        "O WhatsApp ficou na frente. Toque aqui para voltar ao app."
                    )
                }
            }
        }, 2000L)
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
            // Link que não vale mais (redefinido, revogado, grupo apagado): INVÁLIDO de verdade (sai das listas do painel)
            if (telaDiz(root, frasesLinkInvalido)) {
                RunLog.add(applicationContext, "❌ O WhatsApp diz que o link de convite não vale mais")
                return "invalid"
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

    // O que o WhatsApp escreve quando o link de convite não vale mais (sem acento e em minúsculas, como o norm() deixa)
    private val frasesLinkInvalido = listOf(
        "link de convite invalido", "link de convite nao e valido", "link de convite foi redefinido", "link de convite foi revogado",
        "link de convite expirou", "link foi redefinido", "link foi revogado", "convite foi redefinido", "convite expirou",
        "nao foi possivel entrar no grupo", "nao e possivel entrar no grupo", "nao foi possivel participar do grupo",
        "este grupo nao existe", "grupo nao existe mais",
        "invite link is invalid", "invite link was reset", "invite link was revoked", "invite link has expired",
        "couldn't join this group", "couldnt join this group", "can't join this group", "cant join this group",
        "this group no longer exists"
    )

    /** A tela atual tem algum destes textos? (compara sem acento e sem diferença de maiúsculas) */
    private fun telaDiz(root: AccessibilityNodeInfo, frases: List<String>): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        try {
            while (q.isNotEmpty() && n < 300) {
                val node = q.removeFirst()
                n++
                val t = norm((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).replace('’', '\'')
                if (t.isNotBlank() && frases.any { t.contains(it) }) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
        } catch (e: Exception) { }
        return false
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
                            RunLog.add(applicationContext, "👆 Toque (varredura de entrada): ${describeNode(node)}")
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            queue.forEach { safeRecycle(it) }
                            return "joined"
                        }
                        requestKw.any { combined.contains(it) } -> {
                            RunLog.add(applicationContext, "👆 Toque (varredura de pedido): ${describeNode(node)}")
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
            // o campo de digitar de um CHAT (na parte de baixo da tela): nunca a barra de pesquisa do topo
            val inputNode = findChatInput(root)
            if (inputNode == null) {
                // Verifica se tem campo mas desabilitado
                val disabledInput = findDisabledInput(root)
                if (disabledInput != null) {
                    safeRecycle(disabledInput)
                    return "admin_only"
                }
                return "not_found"
            }
            if (messageToSend.isBlank()) {
                safeRecycle(inputNode)
                RunLog.add(applicationContext, "⚠ A mensagem programada está vazia: não enviei nada")
                return "error"
            }
            // Todo envio passa pelos mesmos passos conferidos (digita, confere o texto, toca em Enviar, confere que saiu).
            // Com a digitação humana desligada os passos são curtos e o texto vai de uma vez; antes esse caminho mandava sair do chat
            // enquanto ainda tocava em Enviar, e a mensagem podia ficar só como rascunho.
            safeRecycle(inputNode)
            // com foto: primeiro a foto (passos conferidos abaixo) e só depois o texto; sem foto, direto para o texto
            if (photoPlanned && PhotoStore.supported() && !PhotoStore.alreadySent(photoKey())) startImageStep() else startHumanTyping()
            return "typing"
        } catch (e: Exception) {
            "not_found"
        }
    }

    private val sendLabels = setOf("enviar", "send", "enviar mensagem", "send message")

    // O botão Enviar do chat: prefere o id "send"; senão o rótulo exato (Enviar/Send); só em último caso uma palavra parecida,
    // e só na metade de baixo da tela. Só vale botão visível, ligado e tocável.
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var byId: AccessibilityNodeInfo? = null
        var byLabel: AccessibilityNodeInfo? = null
        var byWord: AccessibilityNodeInfo? = null
        val altura = resources.displayMetrics.heightPixels
        val b = Rect()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst()
            seen++
            if (n.isClickable && n.isEnabled && n.isVisibleToUser) {
                val id = n.viewIdResourceName ?: ""
                val cd = norm(n.contentDescription?.toString() ?: "").trim()
                val tx = norm(n.text?.toString() ?: "").trim()
                if (id.endsWith(":id/send")) {
                    if (byId == null) byId = n
                } else if (cd in sendLabels || tx in sendLabels) {
                    if (byLabel == null) byLabel = n
                } else if (cd.contains("enviar") || cd.contains("send") || tx.contains("enviar") || tx.contains("send")) {
                    n.getBoundsInScreen(b)
                    if (b.top > altura / 2 && byWord == null) byWord = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return byId ?: byLabel ?: byWord
    }

    private val knownLabels = setOf(
        "enviar", "send", "enviar mensagem", "send message", "entrar no grupo", "join group", "entrar", "join", "participar",
        "enviar pedido", "send request", "solicitar", "request to join", "cancelar pedido", "cancel request",
        "todos", "@todos", "mais opcoes", "more options"
    )

    private fun showLabel(s: String): String =
        if (s.isEmpty()) "" else if (norm(s).trim() in knownLabels) s else mask(s.take(20))

    // Descrição curta de um botão para o registro: rótulos conhecidos de botão aparecem; qualquer outro texto sai com letras trocadas por x
    private fun describeNode(n: AccessibilityNodeInfo?): String {
        if (n == null) return "-"
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: "-"
        return "id=$id texto=${showLabel(n.text?.toString() ?: "")} desc=${showLabel(n.contentDescription?.toString() ?: "")}"
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

    // Diz ao painel online o que o app está fazendo agora (o sinal sai logo, respeitando o intervalo mínimo)
    private fun setStep(text: String) {
        if (RunRuntime.step == text) return
        RunRuntime.step = text
        PanelClient.beat(applicationContext)
    }

    private fun startHumanTyping() {
        setStep("lendo o grupo")
        val words = HumanTyping.splitWords(messageToSend)
        mentionTried = false
        mentionInvolved = false
        expectedPlain = messageToSend.trim()   // o que o campo precisa ter na hora de enviar
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
            try { action() } catch (e: Exception) { safeFinish("error") }
        }
        typingRunnable = step
        handler.postDelayed(step, delayMs)
    }

    // Acha o campo de mensagem na tela atual (só no WhatsApp) e roda [block] nele.
    // Escrevendo a legenda: o campo é a caixa de legenda da prévia da foto (nunca a caixa do chat que fica por trás).
    private fun withInputField(block: (AccessibilityNodeInfo) -> Unit): Boolean {
        if (captionTyping) {
            val legenda = findCaptionInput() ?: return false
            return try {
                block(legenda)
                true
            } catch (e: Exception) {
                false
            } finally {
                safeRecycle(legenda)
            }
        }
        val root = rootInActiveWindow
        return try {
            val pkg = root?.packageName?.toString() ?: ""
            var input = if (root != null && isTargetWhatsApp(pkg)) findChatInput(root) else null
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
                if (isTargetRoot(r)) {
                    val found = findChatInput(r)
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
        setStep(if (captionTyping) "tocando na caixa de legenda" else "tocando no campo de mensagem")
        val ok = withInputField { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        if (!ok) {
            if (captionTyping) captionTypingFailed("a caixa de legenda sumiu antes de eu começar a escrever") else safeFinish("retry_env")
            return
        }
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
        if (!humanTyping) {
            // sem digitação humana: escreve de uma vez tudo o que vem até a marcação @todos (ou até o fim)
            setStep("escrevendo a mensagem")
            var end = index
            val sb = StringBuilder(typed)
            while (end < words.size && !(!mentionTried && isMentionWord(words[end]))) {
                sb.append(words[end])
                end++
            }
            val all = sb.toString()
            val okAll = withInputField { setInputText(it, all) }
            if (!okAll) { clearDraftAndFinish(); return }
            if (end < words.size) scheduleTyping(dWord(words[end])) { typingMention(words, end, all) }
            else scheduleTyping(dBeforeSend()) { typingSend(1) }
            return
        }
        setStep("digitando a palavra ${index + 1} de ${words.size}")
        val text = typed + words[index]
        val ok = withInputField { setInputText(it, text) }
        if (!ok) { clearDraftAndFinish(); return }
        if (index + 1 < words.size) {
            scheduleTyping(dWord(words[index + 1])) { typingWord(words, index + 1, text) }
        } else {
            scheduleTyping(dBeforeSend()) { typingSend(1) }
        }
    }

    // ---------------- Envio conferido ----------------
    // 1) Antes de enviar: o campo tem EXATAMENTE a mensagem programada? Se não, reescreve (texto simples) e confere de novo.
    //    Nunca envia um texto diferente do programado: se não conseguir acertar, limpa o campo e não envia.
    // 2) Toca no botão Enviar certo. 3) Depois do toque: o campo esvaziou? Se a mensagem continua lá, toca de novo (até 3 vezes).

    private var expectedPlain = ""
    private var mentionInvolved = false

    private fun stripAll(s: String): String =
        s.filter { !it.isWhitespace() && it.code != 0x200B && it.code != 0x200E && it.code != 0x200F && it.code != 0xFEFF }

    // Espaços e quebras de linha não contam (o WhatsApp pode mudar); letras, números, emojis e a ordem contam.
    // Com marcação @todos o WhatsApp mostra a marcação do jeito dele: só confere o que vem antes e o que vem depois dela.
    private fun matchesExpected(curRaw: String): Boolean {
        val cur = stripAll(curRaw)
        if (cur.isEmpty()) return false
        val exp = stripAll(expectedPlain)
        if (!mentionInvolved) return cur == exp
        val m = Regex("@todos", RegexOption.IGNORE_CASE).find(exp) ?: return cur == exp
        val before = exp.substring(0, m.range.first)
        val after = exp.substring(m.range.last + 1)
        return cur.length >= before.length + after.length &&
            cur.startsWith(before, ignoreCase = true) && cur.endsWith(after, ignoreCase = true)
    }

    // Campo vazio mostra o texto de dica ("Mensagem"): isso não é mensagem digitada
    private fun showingHint(input: AccessibilityNodeInfo): Boolean = Build.VERSION.SDK_INT >= 26 && input.isShowingHintText

    private fun typingSend(attempt: Int) {
        setStep("conferindo o texto antes de enviar")
        var cur = ""
        var hint = false
        val found = withInputField { input ->
            cur = input.text?.toString() ?: ""
            hint = showingHint(input)
        }
        if (!found) { clearDraftAndFinish(); return }
        if (hint || !matchesExpected(cur)) {
            if (attempt >= 3) {
                RunLog.add(
                    applicationContext,
                    "⛔ O texto no campo não confere com a mensagem programada: NÃO enviei (campo limpo). " +
                        "Esperado ${stripAll(expectedPlain).length} caracteres, campo ${stripAll(cur).length}" +
                        (if (hint) " (campo mostrando a dica)" else "") +
                        ". Campo (letras trocadas por x): ${mask(cur.take(60))}"
                )
                clearDraftAndFinish()
                return
            }
            RunLog.add(applicationContext, "⚠ O texto no campo estava diferente do programado: reescrevi tudo (tentativa $attempt/3)")
            mentionInvolved = false
            val plain = expectedPlain
            withInputField { setInputText(it, plain) }
            scheduleTyping(600L) { typingSend(attempt + 1) }
            return
        }
        typingSendClick(1)
    }

    private fun typingSendClick(n: Int) {
        setStep(if (captionTyping) "tocando em Enviar (foto com legenda)" else "tocando em Enviar")
        var botao: AccessibilityNodeInfo? = null
        var tocou = false
        var descricao = "-"
        if (captionTyping) {
            // legenda: o Enviar da PRÉVIA da foto (nunca o do chat que fica por trás)
            val roots = mentionRoots()
            try {
                botao = scanImageScreen(roots).send
                if (botao != null) tocou = clickWithAncestors(botao)
                descricao = describeNode(botao)
            } catch (e: Exception) {
                tocou = false
            } finally {
                roots.forEach { safeRecycle(it) }
            }
        } else {
            val root = rootInActiveWindow
            if (root != null && isTargetRoot(root)) {
                botao = findSendButton(root)
                if (botao != null) tocou = botao.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            descricao = describeNode(botao)
            safeRecycle(root)
        }
        when {
            tocou -> {
                if (n == 1) {
                    markSentNow() // anota na hora: se algo cair depois, o app não manda de novo nesse grupo
                    RunLog.add(applicationContext, "👆 Toquei em Enviar ($descricao)")
                }
                if (captionTyping) scheduleTyping(1_300L) { captionSendCheck(n, 1) } else scheduleTyping(1_100L) { typingSendCheck(n, 1) }
            }
            n < 3 -> scheduleTyping(900L) { typingSendClick(n + 1) }
            else -> {
                RunLog.add(applicationContext, "⛔ Não achei o botão Enviar (3 tentativas): NÃO enviei (campo limpo)")
                clearDraftAndFinish()
            }
        }
    }

    // Depois do toque em Enviar: o campo esvaziou? (esvaziou = a mensagem saiu)
    private fun typingSendCheck(n: Int, checks: Int) {
        setStep("conferindo que a mensagem saiu")
        var ainda = false
        val found = withInputField { input ->
            ainda = !showingHint(input) && matchesExpected(input.text?.toString() ?: "")
        }
        when {
            !found && checks < 2 -> scheduleTyping(700L) { typingSendCheck(n, checks + 1) }
            !found || !ainda -> scheduleTyping(dStay()) { leaveChat("message_sent") }
            n < 3 -> scheduleTyping(300L) { typingSendClick(n + 1) }   // a mensagem continua no campo: o toque não enviou
            else -> {
                try {
                    StorageManager.unmarkSent(
                        applicationContext, RunRuntime.currentLink ?: "", RunRuntime.currentPkg.ifEmpty { "com.whatsapp" }
                    )
                } catch (e: Exception) {}
                RunLog.add(applicationContext, "⛔ A mensagem continuou no campo depois de 3 toques em Enviar: NÃO saiu (campo limpo)")
                clearDraftAndFinish()
            }
        }
    }

    // Não conseguiu enviar (tela mudou / sem botão de enviar): apaga o que foi digitado para não deixar rascunho solto no grupo
    private fun clearDraftAndFinish() {
        if (captionTyping) {
            captionTypingFailed("não consegui escrever a legenda")
            return
        }
        try { withInputField { setInputText(it, "") } } catch (e: Exception) {}
        safeFinish("retry_env")
    }

    // ---------------- Texto como LEGENDA da foto ----------------
    // Na prévia da foto: acha a caixa de legenda (se ela só vira campo depois de um toque, toca nela), escreve o texto com os
    // mesmos passos conferidos do chat (inclusive @todos), toca no Enviar da prévia e confere que a prévia fechou.
    // Se a legenda não der (caixa não encontrada, texto não confere, Enviar sem efeito): apaga o que foi escrito, grava a tela no
    // Registro e manda a foto SEM legenda e o texto logo depois, no chat (o jeito que já funcionava). Nunca trava o grupo por isso.

    // A caixa de legenda: campo editável numa janela do WhatsApp SEM a caixa do chat (a prévia). Prefere o de id "caption" ou com
    // a dica "legenda/caption"; senão o primeiro campo editável da prévia.
    private fun findCaptionInput(): AccessibilityNodeInfo? {
        val roots = mentionRoots()
        try {
            for (root in roots) {
                val achado = captionFieldIn(root, editable = true)
                if (achado != null) return achado
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    private fun captionLabel(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName ?: ""
        val dica = if (Build.VERSION.SDK_INT >= 26) norm(n.hintText?.toString() ?: "") else ""
        val tx = norm(n.text?.toString() ?: "")
        val cd = norm(n.contentDescription?.toString() ?: "")
        return id.endsWith("/caption") || id.contains("caption") ||
            dica.contains("legenda") || dica.contains("caption") || tx.contains("legenda") || tx.contains("caption") ||
            cd.contains("legenda") || cd.contains("caption")
    }

    // editable = true: só campo de digitar de verdade (para escrever). editable = false: também o "Adicione uma legenda" que
    // ainda não é campo (para tocar e abrir). Janela com a caixa do chat (id "entry") nunca serve.
    private fun captionFieldIn(root: AccessibilityNodeInfo, editable: Boolean): AccessibilityNodeInfo? {
        var forte: AccessibilityNodeInfo? = null
        var fraco: AccessibilityNodeInfo? = null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 900) {
            val n = q.removeFirst()
            seen++
            val id = n.viewIdResourceName ?: ""
            if (id.endsWith("/entry")) return null   // janela do chat: a legenda nunca é escrita aí
            val cls = n.className?.toString() ?: ""
            val campo = n.isEditable && cls.contains("EditText")
            if (editable) {
                if (campo && n.isVisibleToUser) {
                    if (captionLabel(n)) { if (forte == null) forte = n } else if (fraco == null) fraco = n
                }
            } else if (n.isVisibleToUser) {
                val tx = norm(n.text?.toString() ?: "") + " " + norm(n.contentDescription?.toString() ?: "")
                val botaoEnviar = tx.contains("enviar") || tx.contains("send")
                if (!botaoEnviar && captionLabel(n)) { if (forte == null) forte = n } else if (!botaoEnviar && cls.contains("EditText") && fraco == null) fraco = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return forte ?: fraco
    }

    // Toca na caixa de legenda que ainda não é campo de digitar (o WhatsApp pode só abrir o campo depois do toque)
    private fun tapCaptionPlaceholder(): Boolean {
        val roots = mentionRoots()
        try {
            for (root in roots) {
                val alvo = captionFieldIn(root, editable = false) ?: continue
                val ok = tapSmall(alvo) || alvo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                RunLog.add(applicationContext, "👆 Foto: toque na caixa de legenda (${describeNode(alvo)})" + (if (ok) "" else " — sem resposta"))
                return ok
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    // Toca no nó ou num "pai" pequeno (a barra da legenda); nunca num pai grande: tocar na foto esconde os botões da prévia
    private fun tapSmall(node: AccessibilityNodeInfo): Boolean {
        return try {
            val altura = resources.displayMetrics.heightPixels
            val b = Rect()
            var cur: AccessibilityNodeInfo? = node
            var level = 0
            while (cur != null && level < 4) {
                cur.getBoundsInScreen(b)
                if (b.height() > altura / 4) return false
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                cur = cur.parent
                level++
            }
            false
        } catch (e: Exception) { false }
    }

    // A prévia da foto continua aberta? (janela do WhatsApp sem a caixa do chat, com a caixa de legenda ou o Enviar)
    private fun previewStillOpen(): Boolean {
        val roots = mentionRoots()
        return try {
            val s = scanImageScreen(roots)
            s.caption || s.send != null
        } catch (e: Exception) {
            false
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    // Etapa 3 com legenda: acha a caixa (até 3 olhadas, tocando nela se ainda não for campo) e começa a escrever
    private fun captionOpen(attempt: Int) {
        setStep("procurando a caixa de legenda")
        val campo = findCaptionInput()
        if (campo != null) {
            safeRecycle(campo)
            captionTyping = true
            captionPending = true
            RunLog.add(applicationContext, "📝 Foto: escrevendo o texto como legenda")
            startHumanTyping()
            return
        }
        if (attempt < 3) {
            val tocou = tapCaptionPlaceholder()
            scheduleTyping(jit(if (tocou) 1_100L else 800L)) { captionOpen(attempt + 1) }
            return
        }
        captionGiveUp("não achei a caixa de legenda na prévia da foto")
    }

    // Algo falhou enquanto escrevia a legenda: apaga o que foi escrito (nunca manda a foto com meia legenda) e decide o que fazer
    private fun captionTypingFailed(reason: String) {
        captionPending = false
        try { withInputField { setInputText(it, "") } } catch (e: Exception) { }
        scheduleTyping(600L) { captionClearCheck(reason, 1) }
    }

    private fun captionClearCheck(reason: String, n: Int) {
        var resto = ""
        var dica = false
        val achou = withInputField { c ->
            resto = c.text?.toString() ?: ""
            dica = showingHint(c)
        }
        if (achou && !dica && stripAll(resto).isNotEmpty()) {
            if (n < 3) {
                withInputField { setInputText(it, "") }
                scheduleTyping(600L) { captionClearCheck(reason, n + 1) }
            } else {
                captionTyping = false
                imgFail("$reason e não consegui apagar a legenda")
            }
            return
        }
        captionTyping = false
        // legenda vazia: com a prévia aberta, a foto vai sem legenda e o texto depois; sem prévia, o grupo é refeito
        if (previewStillOpen()) captionGiveUp(reason) else imgFail("$reason (a prévia da foto fechou)")
    }

    // A legenda não deu neste grupo: a foto vai SEM legenda (Enviar da prévia) e o texto vai logo depois, no chat.
    // Depois de 2 grupos assim na mesma rodada, os próximos já vão desse jeito (sem perder tempo tentando a legenda).
    private fun captionGiveUp(reason: String) {
        captionTyping = false
        captionPending = false
        captionSkip = true
        PhotoStore.captionFails++
        // a marcação @todos tentada na legenda não conta: ela vai ser tentada de novo no texto, no chat
        if (mentionTried) {
            RunRuntime.mentionTotal = (RunRuntime.mentionTotal - 1).coerceAtLeast(0)
            if (RunRuntime.mentionNote == "marcou @todos") RunRuntime.mentionOk = (RunRuntime.mentionOk - 1).coerceAtLeast(0)
            RunRuntime.mentionNote = ""
            mentionTried = false
        }
        RunLog.add(applicationContext, "⚠ Foto: $reason. Mando a foto sem legenda e o texto logo depois")
        if (PhotoStore.captionFails == 2) {
            RunLog.add(applicationContext, "⚠ A legenda falhou em 2 grupos: até o fim desta rodada a foto vai sem legenda e o texto logo depois")
        }
        dumpImageScreen("legenda: $reason")
        armImgTimeout()
        imgStage = 3
        imgTries = 0
        scheduleTyping(jit(700L)) { imgTick() }
    }

    // Depois do toque em Enviar da prévia: a prévia tem que fechar (a foto saiu com a legenda)
    private fun captionSendCheck(n: Int, checks: Int) {
        setStep("conferindo que a foto saiu")
        if (!previewStillOpen()) {
            captionTyping = false
            scheduleTyping(dStay()) { leaveChat("message_sent") }
            return
        }
        when {
            checks < 3 -> scheduleTyping(900L) { captionSendCheck(n, checks + 1) }   // o envio pode estar começando
            n < 3 -> typingSendClick(n + 1)                                         // a prévia continua: o toque não pegou
            else -> {
                // 3 toques e a prévia continua aberta: nada saiu (desfaz a anotação de enviado e tenta sem legenda)
                try {
                    StorageManager.unmarkSent(
                        applicationContext, RunRuntime.currentLink ?: "", RunRuntime.currentPkg.ifEmpty { "com.whatsapp" }
                    )
                } catch (e: Exception) {}
                PhotoStore.uncommit(photoKey())
                RunLog.add(applicationContext, "⛔ A prévia da foto continuou aberta depois de 3 toques em Enviar")
                captionTypingFailed("o Enviar da prévia não respondeu")
            }
        }
    }

    // ---------------- Foto antes do texto (pelo teclado do On Groups) ----------------
    // Com foto ligada, cada grupo recebe primeiro a foto e só depois o texto, no MESMO grupo que o app acabou de abrir:
    // toca na caixa de mensagem -> troca para o teclado do On Groups -> o teclado coloca a foto na caixa (como um GIF) ->
    // volta para o teclado do usuário -> o WhatsApp mostra a prévia -> toca em Enviar -> confere que voltou ao chat -> texto.
    // Nunca usa a galeria (não tem como pegar outra foto). Se algo não der certo, grava a tela no Registro (letras trocadas
    // por x) e: falha passageira = o grupo é refeito; falha que não muda sozinha (teclado desativado, WhatsApp que não aceita
    // foto pelo teclado) = pausa a rodada com o motivo; prévia que não apareceu = pausa para você conferir no grupo.
    // A foto só conta como enviada depois de conferida (assim um grupo refeito depois do texto não manda a foto de novo).

    private fun photoKey(): String = (RunRuntime.currentLink ?: "") + "|" + RunRuntime.currentPkg

    private val imgKnown = sendLabels + setOf(
        "legenda", "caption", "adicione uma legenda", "add a caption", "adicionar legenda", "adicionar uma legenda",
        "mensagem", "message", "emoji", "visualizacao unica", "view once", "voltar", "back", "navegar para cima", "navigate up",
        "cortar", "crop", "texto", "text", "desenhar", "draw", "figurinhas", "stickers", "hd", "qualidade", "quality",
        "girar", "rotate", "filtros", "filters", "descartar", "discard", "cancelar", "cancel", "ok"
    )

    private var imgStage = 2       // 2 = foto entregue (esperando a prévia); 3 = prévia (falta Enviar); 4 = enviada (conferindo)
    private var imgTicks = 0       // quantas olhadas já deu (limite geral)
    private var imgTries = 0       // tentativas sem avançar dentro da etapa atual
    private var imgSendClicks = 0  // toques no Enviar da prévia
    private var imgBackSeen = 0    // olhadas seguidas já de volta ao chat
    private var imgFile: java.io.File? = null
    private var imePrev = ""       // teclado do usuário antes da troca ("" = não trocamos)

    private fun jit(base: Long): Long = base + Random.nextLong(0L, 350L)

    private class ImgScan {
        var chatEntry = false                       // campo de mensagem do chat à vista
        var caption = false                         // campo de legenda da prévia da foto
        var send: AccessibilityNodeInfo? = null     // botão Enviar da prévia (nunca o do chat)
    }

    // Lê as janelas do WhatsApp: a prévia é uma tela (ou janela) SEM o campo do chat, com legenda e/ou botão Enviar
    private fun scanImageScreen(roots: List<AccessibilityNodeInfo>): ImgScan {
        val s = ImgScan()
        for (root in roots) {
            var rootEntry = false
            var rootCaption = false
            var rootSend: AccessibilityNodeInfo? = null
            var rootSendById = false
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 900) {
                val n = q.removeFirst()
                seen++
                val id = n.viewIdResourceName ?: ""
                val cls = n.className?.toString() ?: ""
                val tx = norm(n.text?.toString() ?: "").trim()
                val cd = norm(n.contentDescription?.toString() ?: "").trim()
                val dica = if (Build.VERSION.SDK_INT >= 26) norm(n.hintText?.toString() ?: "").trim() else ""
                if (id.endsWith("/entry")) rootEntry = true
                // campo de legenda da prévia: um campo de digitar (nunca o texto da legenda de uma foto no chat)
                if (cls.contains("EditText") &&
                    (id.endsWith("/caption") || dica.contains("legenda") || dica.contains("caption") || cd.contains("legenda") || cd.contains("caption"))
                ) rootCaption = true
                if (n.isVisibleToUser && n.isEnabled && (id.endsWith(":id/send") || tx in sendLabels || cd in sendLabels)) {
                    val porId = id.endsWith(":id/send")
                    if (rootSend == null || (porId && !rootSendById)) {
                        rootSend = n
                        rootSendById = porId
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            if (rootEntry) {
                s.chatEntry = true
            } else {
                if (rootCaption) s.caption = true
                if (rootSend != null && s.send == null) s.send = rootSend
            }
        }
        return s
    }

    private fun imgTap(n: AccessibilityNodeInfo, what: String): Boolean {
        val ok = clickWithAncestors(n)
        RunLog.add(applicationContext, "👆 Foto: toque em $what (${describeNode(n)})" + (if (ok) "" else " — sem resposta"))
        return ok
    }

    private fun startImageStep() {
        setStep("preparando a foto")
        imgStage = 2
        imgTicks = 0
        imgTries = 0
        imgSendClicks = 0
        imgBackSeen = 0
        armImgTimeout()
        scheduleTyping(400L) { imgPrepare() }
    }

    // O limite de 20 s do setWaiting() não serve: troca por um para a foto (o texto põe o dele depois)
    private fun armImgTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val limit = Runnable { if (isActive) imgFail("tempo esgotado (100 s)") }
        timeoutRunnable = limit
        handler.postDelayed(limit, 100_000L)
    }

    private fun imgPrepare() {
        try {
            val ctx = applicationContext
            if (Build.VERSION.SDK_INT < 30) {
                imgStop("a foto pelo teclado precisa de Android 11 ou mais novo")
                return
            }
            if (!KeyboardBridge.isEnabled(ctx)) {
                imgStop("o teclado do On Groups não está ativado (no app: aba Por lista > Ativar teclado)")
                return
            }
            val file = PhotoStore.pickFor(ctx, photoKey())
            if (file == null) {
                imgStop("não há foto guardada no app")
                return
            }
            imgFile = file
            imgKbdFocus(1)
        } catch (e: Exception) {
            imgFail("erro ao preparar a foto: " + (e.message ?: "?"))
        }
    }

    // Toca na caixa de mensagem do grupo e troca para o teclado do On Groups: ele entrega a foto assim que chega no campo
    private fun imgKbdFocus(attempt: Int) {
        setStep("abrindo o teclado do On Groups")
        val file = imgFile
        if (file == null) {
            imgFail("a foto da vez sumiu")
            return
        }
        val tocou = withInputField {
            it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!tocou) {
            if (attempt < 3) scheduleTyping(900L) { imgKbdFocus(attempt + 1) } else imgFail("não achei a caixa de mensagem do grupo")
            return
        }
        val ctx = applicationContext
        val nosso = KeyboardBridge.imeId(ctx)
        val atual = KeyboardBridge.currentIme(ctx)
        if (imePrev.isEmpty() && atual.isNotEmpty() && atual != nosso) imePrev = atual
        KeyboardBridge.result = null
        KeyboardBridge.pending = KeyboardBridge.Request(RunRuntime.currentPkg.ifEmpty { "com.whatsapp" }, file, System.currentTimeMillis())
        var trocou = false
        if (Build.VERSION.SDK_INT >= 30) {
            trocou = try { softKeyboardController.switchToInputMethod(nosso) } catch (e: Exception) { false }
        }
        if (!trocou) {
            KeyboardBridge.pending = null
            imgStop("o Android não deixou trocar para o teclado do On Groups")
            return
        }
        RunLog.add(ctx, "⌨ Foto: troquei para o teclado do On Groups")
        OnGroupsKeyboard.instance?.tryCommitPending()   // se o teclado já estava ligado no campo
        scheduleTyping(jit(900L)) { imgKbdWait(1) }
    }

    // Espera o teclado contar se o WhatsApp aceitou a foto
    private fun imgKbdWait(n: Int) {
        setStep("esperando o teclado entregar a foto")
        val r = KeyboardBridge.result
        if (r == null) {
            if (n == 3 || n == 6) OnGroupsKeyboard.instance?.tryCommitPending()
            if (n < 9) {
                scheduleTyping(700L) { imgKbdWait(n + 1) }
                return
            }
            KeyboardBridge.pending = null
            imgFail("o teclado do On Groups não chegou na caixa de mensagem")
            return
        }
        KeyboardBridge.result = null
        RunLog.add(
            applicationContext,
            "⌨ Foto: o campo de mensagem aceita: ${r.accepted} · ${r.note}" + (if (r.mime.isNotEmpty()) " (${r.mime})" else "")
        )
        imeRestore()
        if (!r.ok) {
            imgStop("o WhatsApp não aceitou a foto pelo teclado (${r.note}; aceita: ${r.accepted})")
            return
        }
        imgStage = 2
        imgTries = 0
        scheduleTyping(jit(1_600L)) { imgTick() }
    }

    // Volta para o teclado que o usuário usava antes da foto
    private fun imeRestore() {
        val prev = imePrev
        imePrev = ""
        if (prev.isEmpty() || Build.VERSION.SDK_INT < 30) return
        try { softKeyboardController.switchToInputMethod(prev) } catch (e: Exception) { }
    }

    private fun imgTick() {
        if (!isActive) return
        val roots = mentionRoots()
        try {
            imgTicks++
            if (imgTicks > 30) {
                imgFail("passos demais sem chegar ao fim")
                return
            }
            if (roots.isEmpty()) {
                imgTries++
                if (imgTries >= 5) imgFail("o WhatsApp saiu da frente") else scheduleTyping(1_200L) { imgTick() }
                return
            }
            val s = scanImageScreen(roots)
            val preview = s.caption || s.send != null
            when (imgStage) {
                2 -> {
                    setStep("esperando a prévia da foto")
                    if (preview) {
                        imgStage = 3
                        imgTries = 0
                        scheduleTyping(200L) { imgTick() }
                    } else {
                        imgTries++
                        if (imgTries >= 6) {
                            imgCheck("a prévia não apareceu depois de o teclado entregar a foto (o WhatsApp pode ter mandado direto ou ignorado)")
                        } else {
                            scheduleTyping(jit(1_000L)) { imgTick() }
                        }
                    }
                }
                3 -> {
                    if (captionMode && !captionSkip && PhotoStore.captionFails < 2 && messageToSend.isNotBlank()) {
                        // legenda: acha a caixa de legenda da prévia e escreve o texto nela (os mesmos passos conferidos do
                        // texto: digita, confere que está igual, toca no Enviar da prévia e confere que a prévia fechou).
                        // Se não der, a foto vai sem legenda e o texto logo depois, no chat (captionGiveUp)
                        setStep("procurando a caixa de legenda")
                        scheduleTyping(jit(700L)) { captionOpen(1) }
                        return
                    }
                    setStep("tocando em Enviar (foto)")
                    val enviar = s.send
                    if (enviar != null && imgTap(enviar, "Enviar a foto")) {
                        imgSendClicks++
                        imgStage = 4
                        imgTries = 0
                        imgBackSeen = 0
                        scheduleTyping(jit(1_800L)) { imgTick() }
                    } else {
                        imgTries++
                        if (imgTries >= 5) imgFail("não achei o botão Enviar da prévia da foto") else scheduleTyping(jit(900L)) { imgTick() }
                    }
                }
                else -> {
                    setStep("conferindo que a foto saiu")
                    val enviar = s.send
                    when {
                        preview -> {
                            // ainda na prévia: o toque pode não ter pegado
                            if (enviar != null && imgSendClicks < 3 && imgTap(enviar, "Enviar a foto (de novo)")) {
                                imgSendClicks++
                                scheduleTyping(jit(1_500L)) { imgTick() }
                            } else {
                                imgTries++
                                if (imgTries >= 4) imgFail("a foto não saiu (a prévia continua aberta)") else scheduleTyping(jit(1_000L)) { imgTick() }
                            }
                        }
                        s.chatEntry -> {
                            imgBackSeen++
                            if (imgBackSeen >= 2) imgDone() else scheduleTyping(jit(900L)) { imgTick() }
                        }
                        else -> {
                            imgTries++
                            if (imgTries >= 8) imgFail("não voltei ao chat depois de enviar a foto") else scheduleTyping(jit(1_000L)) { imgTick() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            imgFail("erro na foto: " + (e.message ?: "?"))
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    private fun imgDone() {
        val ctx = applicationContext
        PhotoStore.commitSent(ctx, photoKey())
        RunLog.add(ctx, "📷 Foto enviada neste grupo (rodízio ${PhotoStore.modeText(ctx)}). Agora o texto")
        setStep("foto enviada: indo para o texto")
        scheduleTyping(jit(1_500L)) { startHumanTyping() }
    }

    private fun imgCleanup() {
        KeyboardBridge.pending = null
        imeRestore()
    }

    // Falha passageira: nada de texto sem foto. Grava a tela no Registro (para calibrar), sai do WhatsApp e o grupo é refeito.
    private fun imgFail(reason: String) {
        imgCleanup()
        RunLog.add(applicationContext, "⛔ Foto: $reason. O texto NÃO foi enviado; o grupo será refeito")
        dumpImageScreen(reason)
        safeFinish("retry_env")
    }

    // Falha que não muda sozinha (teclado desativado, WhatsApp que não aceita foto pelo teclado): pausa a rodada com o motivo
    private fun imgStop(reason: String) {
        imgCleanup()
        RunRuntime.stopNote = "📷 Foto: $reason. Corrija e toque em Continuar (ou desligue a foto)"
        RunLog.add(applicationContext, "⛔ Foto: $reason. Nada foi enviado neste grupo; pausei a rodada")
        dumpImageScreen(reason)
        safeFinish("photo_stop")
    }

    // Não dá para saber se a foto saiu: pausa para o usuário conferir no grupo (nunca manda a foto duas vezes)
    private fun imgCheck(reason: String) {
        imgCleanup()
        RunRuntime.stopNote = "📷 Foto: $reason. Confira no grupo e toque em Continuar"
        RunLog.add(applicationContext, "⚠ Foto: $reason. Pausei para você conferir no grupo")
        dumpImageScreen(reason)
        safeFinish("photo_check")
    }

    // Nomes de botões e dicas do próprio WhatsApp ficam legíveis no diagnóstico (sem os "..." do fim); o resto vira x
    private fun imgKeep(s: String): String = if (norm(s).trim().trimEnd('.', '…', ' ') in imgKnown) s else mask(s.take(30))

    private fun winType(t: Int): String = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "teclado"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "sistema"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "acessibilidade"
        else -> "tipo $t"
    }

    // Estrutura da tela (sem nomes: letras viram x, só os nomes dos botões ficam legíveis). No máximo 6 por sessão.
    // Mostra também as janelas na tela (app, teclado, sistema) e, em cada campo, se é editável e a dica ("Adicione uma legenda").
    private fun dumpImageScreen(motivo: String) {
        if (PhotoStore.dumps >= 6) return
        PhotoStore.dumps++
        try {
            val sb = StringBuilder("🔍 Diagnóstico da foto ($motivo). Letras trocadas por x; só os nomes dos botões ficam legíveis")
            try {
                val todas = windows
                if (todas != null && todas.isNotEmpty()) {
                    val partes = todas.mapIndexed { i, w ->
                        val p = try { w.root?.packageName?.toString() ?: "?" } catch (e: Exception) { "?" }
                        "${i + 1}) ${winType(w.type)} $p" + (if (w.isActive) " (ativa)" else "") + (if (w.isFocused) " (foco)" else "")
                    }
                    sb.append("\n   janelas: ").append(partes.joinToString(" · "))
                }
            } catch (e: Exception) { }
            val janelas = mutableListOf<AccessibilityNodeInfo>()
            try { rootInActiveWindow?.let { janelas.add(it) } } catch (e: Exception) { }
            for (r in mentionRoots()) if (janelas.none { it == r }) janelas.add(r)
            val b = Rect()
            for ((wi, root) in janelas.withIndex()) {
                sb.append("\n   [janela ${wi + 1}] pacote ${root.packageName ?: "?"} | classe | id | texto | descrição | dica | estado | topo-fundo")
                var n = 0
                val q = ArrayDeque<AccessibilityNodeInfo>()
                q.add(root)
                while (q.isNotEmpty() && n < 70) {
                    val node = q.removeFirst()
                    val txt = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val dica = if (Build.VERSION.SDK_INT >= 26) (node.hintText?.toString() ?: "") else ""
                    val clsFull = node.className?.toString() ?: ""
                    val campo = node.isEditable || clsFull.contains("EditText")
                    if (txt.isNotEmpty() || desc.isNotEmpty() || dica.isNotEmpty() || node.isClickable || campo) {
                        node.getBoundsInScreen(b)
                        val cls = clsFull.substringAfterLast('.').ifEmpty { "-" }
                        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
                        val estado = listOfNotNull(
                            if (node.isClickable) "toque" else null,
                            if (node.isEditable) "editável" else null,
                            if (node.isFocused) "foco" else null,
                            if (!node.isVisibleToUser) "oculto" else null,
                            if (!node.isEnabled) "desligado" else null
                        ).joinToString("+").ifEmpty { "-" }
                        sb.append("\n   $cls | $id | ${imgKeep(txt)} | ${imgKeep(desc)} | ${imgKeep(dica)} | $estado | ${b.top}-${b.bottom}")
                        n++
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
                }
            }
            RunLog.add(applicationContext, sb.toString())
        } catch (e: Exception) { }
    }

    // ---------------- Verificar WhatsApp (comando do painel) ----------------
    // Abre cada WhatsApp instalado, olha a tela (conta banida? sem cadastro? funcionando?), vai para a tela inicial e, no fim,
    // traz o app de volta. Só leitura: não toca em nada dentro do WhatsApp.

    private var waCheckQueue = mutableListOf<String>()
    private val waCheckResult = LinkedHashMap<String, String>()
    private var waCheckDone: ((Map<String, String>) -> Unit)? = null

    fun checkWhatsApps(pkgs: List<String>, done: (Map<String, String>) -> Unit) {
        waCheckQueue = pkgs.toMutableList()
        waCheckResult.clear()
        waCheckDone = done
        RunRuntime.step = "verificando os WhatsApp"
        waCheckNext()
    }

    private fun waCheckNext() {
        if (waCheckQueue.isEmpty()) {
            relaunchApp(false)
            val cb = waCheckDone
            waCheckDone = null
            val res = LinkedHashMap(waCheckResult)
            handler.postDelayed({ cb?.invoke(res) }, 1_500L)
            return
        }
        val pkg = waCheckQueue.removeAt(0)
        if (!WaHealth.installed(applicationContext, pkg)) {
            waCheckResult[pkg] = WaHealth.NOT_INSTALLED
            WaHealth.set(applicationContext, pkg, WaHealth.NOT_INSTALLED)
            waCheckNext()
            return
        }
        val abriu = try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) {
                false
            } else {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!abriu) {
            waCheckResult[pkg] = WaHealth.UNKNOWN
            waCheckNext()
            return
        }
        handler.postDelayed({ waCheckRead(pkg, 1) }, 3_500L)
    }

    private fun waCheckRead(pkg: String, attempt: Int) {
        val root: AccessibilityNodeInfo? = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root == null || root.packageName?.toString() != pkg) {
            safeRecycle(root)
            if (attempt < 3) {
                handler.postDelayed({ waCheckRead(pkg, attempt + 1) }, 1_500L)
                return
            }
            waCheckResult[pkg] = WaHealth.UNKNOWN
            WaHealth.set(applicationContext, pkg, WaHealth.UNKNOWN)
            waCheckNext()
            return
        }
        val st = WaHealth.classify(root) ?: WaHealth.OK
        safeRecycle(root)
        waCheckResult[pkg] = st
        WaHealth.set(applicationContext, pkg, st)
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (e: Exception) { }
        handler.postDelayed({ waCheckNext() }, 1_200L)
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

    private fun typingMention(words: List<String>, index: Int, typed: String) {
        mentionTried = true
        mentionTouched = false
        RunRuntime.mentionTotal++
        setStep("digitando @todos")
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
        if (attempt == 1 && !clicked) setStep("esperando a opção Todos aparecer")
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
        setStep("tocando na opção Todos")
        val tocou = clickWithAncestors(option)
        if (tocou) mentionTouched = true
        RunLog.add(applicationContext, "👆 Toquei na opção Todos (${describeNode(option)}), tentativa $attempt: ${if (tocou) "tocou" else "não deu para tocar"}")
        scheduleTyping(800L) { typingMentionPick(words, index, typed, attempt + 1, tocou) }
    }

    // A marcação entrou: segue com o resto da mensagem (colando) ou, se era a última palavra, vai para o envio
    private fun typingMentionDone(words: List<String>, index: Int) {
        RunRuntime.mentionNote = "marcou @todos"
        mentionInvolved = true   // o WhatsApp mostra a marcação do jeito dele: a conferência olha só o que vem antes e depois
        RunRuntime.mentionOk++
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
        // a mensagem esperada passa a ser a original SEM o @todos
        expectedPlain = words.filterIndexed { i, _ -> i != index }.joinToString("").trim()
        mentionInvolved = false
        val temQuebra = words[index].substring(words[index].trimEnd().length).contains('\n')
        val seguir = if (temQuebra) typed + "\n" else typed
        withInputField { setInputText(it, typed) }
        val next = index + 1
        when {
            next < words.size -> scheduleTyping(dWord(words[next])) { typingWord(words, next, seguir) }
            typed.isBlank() -> {
                RunLog.add(applicationContext, "⚠ a mensagem só tinha @todos e a marcação não foi possível: nada para enviar")
                safeFinish("error")
            }
            else -> scheduleTyping(dBeforeSend()) { typingSend(1) }
        }
    }

    // Depois da marcação: cola a próxima palavra no fim do campo (reescrever o texto inteiro apagaria a marcação)
    private fun typingPaste(words: List<String>, index: Int) {
        setStep("escrevendo o resto da mensagem")
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
                mentionInvolved = true
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
            if (ativa != null && isTargetRoot(ativa)) list.add(ativa)
        } catch (e: Exception) {}
        try {
            val todas = windows
            if (todas != null) {
                for (w in todas) {
                    val r = w.root
                    if (r != null && isTargetRoot(r) && list.none { it == r }) list.add(r)
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
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") "error" else "retry_env"
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

    // Painel online: traz o app para a frente para cumprir um comando (a tela do app é quem conduz o processo)
    fun relaunchForRemote() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
            intent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.putExtra(MainActivity.EXTRA_REMOTE, true)
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
        waCheckQueue.clear()
        waCheckDone = null
        KeyboardBridge.pending = null
        imeRestore()   // se parou no meio da foto, o teclado do usuário volta
        try { withInputField { setInputText(it, "") } } catch (e: Exception) {}   // escrevendo a legenda: apaga a legenda
        captionTyping = false
        captionPending = false
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
        // traz o app para a frente e confere que ele apareceu (senão tenta de novo e avisa)
        bringAppToFront(1) { intent -> intent.putExtra(MainActivity.EXTRA_SCAN_DONE, true) }
    }

    private fun markSentNow() {
        val link = RunRuntime.currentLink ?: return
        val pkg = RunRuntime.currentPkg.ifEmpty { "com.whatsapp" }
        try { StorageManager.markSent(applicationContext, link, pkg) } catch (e: Exception) {}
        // legenda: o toque em Enviar da prévia leva a foto junto (o rodízio anda e o grupo não recebe a foto de novo)
        if (captionPending) {
            captionPending = false
            try { PhotoStore.commitSent(applicationContext, photoKey()) } catch (e: Exception) {}
            RunLog.add(applicationContext, "📷 Foto com o texto na legenda: toquei em Enviar")
        }
    }

    // Sai do chat com segurança (só aperta Voltar com o WhatsApp na frente, conferindo), volta ao app e informa o resultado
    private fun leaveChat(status: String) {
        exitAndReturn(status)
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

    // Só vale o WhatsApp ESCOLHIDO para este processo (normal ou Business); sem escolha registrada, qualquer um dos dois
    private fun isTargetWhatsApp(pkg: String): Boolean {
        val want = RunRuntime.currentPkg
        return if (want.isNotEmpty()) pkg == want else (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")
    }

    private fun isTargetRoot(root: AccessibilityNodeInfo): Boolean = isTargetWhatsApp(root.packageName?.toString() ?: "")

    // O campo de digitar de um CHAT: EditText editável na parte de baixo da tela (ou com id "entry"); ignora a barra de pesquisa do topo
    // (o Escanear tem o seu próprio findComposer, que reconhece o campo pela dica "Mensagem")
    private fun findChatInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            val altura = resources.displayMetrics.heightPixels
            val b = Rect()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
                    node.getBoundsInScreen(b)
                    val id = node.viewIdResourceName ?: ""
                    // dica do campo de chat ("Mensagem"/"Message"): outro jeito de reconhecer quando o campo cresce (mensagem longa)
                    val dica = if (Build.VERSION.SDK_INT >= 26) (node.hintText?.toString() ?: "") else ""
                    val rotulo = norm((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "") + " " + dica)
                    if (id.endsWith("/entry") || b.top > altura * 0.35 || rotulo.contains("mensagem") || rotulo.contains("message")) {
                        queue.forEach { safeRecycle(it) }
                        return node
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            null
        } catch (e: Exception) { null }
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
                RunLog.add(applicationContext, "👆 Toque (entrada): ${describeNode(node)}")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            val p = node.parent
            if (p?.isClickable == true) {
                RunLog.add(applicationContext, "👆 Toque (entrada): ${describeNode(p)}")
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

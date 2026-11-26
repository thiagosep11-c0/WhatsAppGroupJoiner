package com.groupjoiner

/** Fases do processo. */
object Phase {
    const val JOIN = "JOIN"           // entrada nos grupos
    const val MESSAGES = "MESSAGES"   // envio de mensagens
    const val APPROVAL = "APPROVAL"   // verificação de pedidos aprovados

    fun label(p: String): String = when (p) {
        JOIN -> "ENTRADA"
        MESSAGES -> "MENSAGENS"
        APPROVAL -> "VERIFICAÇÃO"
        else -> p
    }
}

/**
 * Retrato do processo em andamento, salvo a cada passo.
 * Com ele o app (ou o vigia) sabe em que FASE e em que grupo estava, mesmo depois de qualquer parada.
 */
data class RunSnapshot(
    val active: Boolean,
    val phase: String,
    val links: List<String>,
    val statuses: List<String>,   // mesmo tamanho de links ("" = ainda não processado)
    val index: Int,               // grupo em andamento (etapa GROUP) ou o próximo (etapa WAIT)
    val stage: String,            // "GROUP" = grupo em andamento; "WAIT" = esperando o próximo
    val groupSeq: Long,           // identifica o grupo em andamento (descarta resultados velhos)
    val attempt: Int,             // tentativa atual do grupo
    val message: String,
    val pkg: String,
    val progressAt: Long,         // último sinal de vida
    val expectedBy: Long,         // até quando o próximo sinal deve chegar (o vigia cobra depois disso)
    val pauseReason: String,      // "" = rodando
    val startedAt: Long
)

/** Linha curta para a notificação e a tela: fase, posição, contagens e (se houver) o motivo da pausa. */
fun RunSnapshot.summaryLine(): String {
    val total = links.size
    val pos = minOf(index + 1, total)
    val ok = statuses.count { it == "joined" || it == "message_sent" || it == "already_member" }
    val wait = statuses.count { it == "requested" || it == "pending" }
    val bad = statuses.count { it == "invalid" || it == "error" || it == "error_env" || it == "admin_only" }
    val base = "${Phase.label(phase)} · $pos/$total · ✅$ok ⏳$wait ❌$bad"
    return if (pauseReason.isNotEmpty()) "⚠ $pauseReason — $base" else base
}

/** Dados que valem enquanto o processo do app estiver vivo (a tela do app pode morrer, isto não). */
object RunRuntime {
    @Volatile var currentLink: String? = null
    @Volatile var currentPkg: String = ""      // WhatsApp (normal ou Business) que está tratando o grupo atual
    @Volatile var leftChatOk: Boolean = true   // false = não deu para confirmar que saímos do chat anterior (o próximo grupo não escreve às cegas)
    @Volatile var mentionNote: String = ""     // o que aconteceu com a marcação @todos no grupo atual (vai para o registro)
    @Volatile var mentionDumps: Int = 0        // quantos diagnósticos da marcação já foram gravados nesta sessão (máx. 3)
    @Volatile var mentionOk: Int = 0           // marcações @todos que deram certo nesta rodada (vai para o painel online)
    @Volatile var mentionTotal: Int = 0        // grupos em que a marcação @todos foi tentada nesta rodada
    @Volatile var step: String = ""            // o que o app está fazendo agora (aparece no painel online)
    @Volatile var stopNote: String = ""        // motivo de uma pausa pedida pelo serviço (foto, WhatsApp banido...)
    @Volatile var scanning: Boolean = false    // escaneamento das conversas em andamento
    @Volatile var groupSeq: Long = 0L
    @Volatile var groupDone: Boolean = true
}

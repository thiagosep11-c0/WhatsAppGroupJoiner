package com.groupjoiner

import kotlin.random.Random

/**
 * Tempos "de gente" para a fase 2 (mensagens): digitação palavra por palavra e pausas.
 * Só faz contas — não usa nada do Android.
 */
object HumanTyping {

    /** Quebra a mensagem em palavras; cada pedaço leva junto os espaços/quebras de linha que vêm depois dele. */
    fun splitWords(message: String): List<String> {
        val text = message.trim()
        val parts = Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
        return if (parts.isEmpty()) listOf(text) else parts
    }

    /** Quanto "demora" para escrever esta palavra, na velocidade [wpm] (palavras por minuto), com variação natural. */
    fun wordDelayMs(word: String, wpm: Int): Long {
        val perChar = 60_000.0 / (wpm.coerceIn(20, 250) * 5.0)
        var ms = word.length * perChar * Random.nextDouble(0.6, 1.4)
        val end = word.trimEnd()
        if (end.endsWith(".") || end.endsWith("!") || end.endsWith("?") ||
            end.endsWith(",") || end.endsWith(":") || end.endsWith(";")
        ) {
            ms += Random.nextLong(250L, 700L)                         // pausa curta na pontuação
        }
        if (word.contains('\n')) ms += Random.nextLong(400L, 900L)    // nova linha: pensa um pouco
        if (Random.nextInt(100) < 6) ms += Random.nextLong(600L, 1_500L) // de vez em quando hesita
        return ms.toLong().coerceIn(150L, 4_500L)
    }

    fun letterDelayMs(): Long = Random.nextLong(120L, 300L)         // uma letra de cada vez (usado ao digitar @todos)
    fun readPauseMs(): Long = Random.nextLong(1_500L, 3_500L)       // "lê" o grupo antes de escrever
    fun focusPauseMs(): Long = Random.nextLong(400L, 900L)          // toca no campo e começa
    fun beforeSendPauseMs(): Long = Random.nextLong(700L, 1_800L)   // relê antes de enviar
    fun stayAfterSendMs(): Long = Random.nextLong(1_800L, 3_500L)   // fica um instante no grupo depois de enviar

    /** Pausa aleatória entre um grupo e o próximo (em segundos, de [minSec] até [maxSec]). */
    fun pauseBetweenGroupsMs(minSec: Int, maxSec: Int): Long {
        val lo = minSec.coerceAtLeast(1)
        val hi = maxSec.coerceAtLeast(lo)
        return Random.nextLong(lo * 1_000L, hi * 1_000L + 1L)
    }
}

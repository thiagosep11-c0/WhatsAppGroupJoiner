package com.groupjoiner

import android.content.Context
import kotlin.random.Random

/**
 * Mensagens intercaladas: na caixa de mensagem, cada mensagem fica separada por uma linha só com --- .
 * Cada grupo recebe uma delas, no mesmo jeito de alternar das fotos (em ordem ou ao acaso, nunca a mesma duas vezes
 * seguidas). Em ordem, foto 1 vai com a mensagem 1, foto 2 com a mensagem 2 e assim por diante. O rodízio só anda quando
 * a mensagem SAIU num grupo; um grupo refeito usa a mesma mensagem.
 */
object MessageVariants {
    const val SEP = "---"
    private const val K_NEXT = "msgvar_next"
    private const val K_LAST = "msgvar_last"

    @Volatile private var pickKey = ""
    @Volatile private var pickIdx = -1

    /** Separa a caixa de mensagem nas mensagens intercaladas (sem nenhum separador = uma mensagem só). */
    fun split(text: String): List<String> {
        val partes = mutableListOf<String>()
        val atual = StringBuilder()
        for (linha in text.replace("\r\n", "\n").split("\n")) {
            if (linha.trim() == SEP) {
                partes.add(atual.toString().trim())
                atual.setLength(0)
            } else {
                if (atual.isNotEmpty()) atual.append("\n")
                atual.append(linha)
            }
        }
        partes.add(atual.toString().trim())
        return partes.filter { it.isNotEmpty() }
    }

    /** Junta várias mensagens numa caixa só (o painel manda a lista; o app mostra na caixa com os separadores). */
    fun join(list: List<String>): String = list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n$SEP\n")

    /** Nova rodada. */
    fun resetRun() {
        pickKey = ""
        pickIdx = -1
    }

    /** A mensagem deste grupo. Chamando de novo para o MESMO grupo (grupo refeito) devolve a mesma. */
    fun pickFor(ctx: Context, key: String, text: String): String {
        val lista = split(text)
        if (lista.isEmpty()) return text.trim()
        if (lista.size == 1) return lista[0]
        val n = lista.size
        if (key == pickKey && pickIdx in 0 until n) return lista[pickIdx]
        val idx = if (PhotoStore.mode(ctx) == "rnd") {
            val ultima = StorageManager.getPref(ctx, K_LAST, "-1").toIntOrNull() ?: -1
            var i = Random.nextInt(n)
            while (i == ultima) i = Random.nextInt(n)
            i
        } else {
            val proxima = StorageManager.getPref(ctx, K_NEXT, "0").toIntOrNull() ?: 0
            ((proxima % n) + n) % n
        }
        pickKey = key
        pickIdx = idx
        return lista[idx]
    }

    /** A mensagem saiu neste grupo: o rodízio anda. */
    fun commitSent(ctx: Context, text: String) {
        val n = split(text).size
        if (n <= 1 || pickIdx < 0) return
        StorageManager.setPref(ctx, K_LAST, pickIdx.toString())
        StorageManager.setPref(ctx, K_NEXT, ((pickIdx + 1) % n).toString())
    }

    /** Começa o rodízio do zero (lista nova). */
    fun resetRotation(ctx: Context) {
        StorageManager.setPref(ctx, K_NEXT, "0")
        StorageManager.setPref(ctx, K_LAST, "-1")
        resetRun()
    }
}

package com.groupjoiner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro em arquivo do que o app fez: fase, grupo, resultado, erros e recuperações.
 * Sobrevive a reinícios do app — serve para saber em que ponto parou e por quê.
 */
object RunLog {
    private const val FILE_NAME = "ongroups_registro.txt"
    private const val MAX_LINES = 800

    @Synchronized
    fun add(context: Context, text: String) {
        try {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
            f.appendText("$stamp  $text\n")
            if (f.length() > 200_000L) {
                val keep = f.readLines().takeLast(MAX_LINES)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun lines(context: Context, max: Int = 300): List<String> {
        return try {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            if (f.exists()) f.readLines().takeLast(max) else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun last(context: Context): String = lines(context, 1).firstOrNull() ?: "nenhuma ainda"

    @Synchronized
    fun clear(context: Context) {
        try { File(context.applicationContext.filesDir, FILE_NAME).delete() } catch (e: Exception) { }
    }
}

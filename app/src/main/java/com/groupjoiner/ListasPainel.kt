package com.groupjoiner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listas salvas no painel online (app 7): no fim de cada fase (entrada nos grupos ou mensagens) o app manda ao painel o
 * resultado de CADA grupo (entrou, aguardando, link inválido, falha...). O painel guarda a lista (cifrada) e usa para o
 * "Reenviar mensagem" (só os grupos em que entrou ou que estão aguardando) e para o "Retentar falhas".
 * Sem conexão na hora, o resultado fica numa fila aqui e vai depois (junto com o próximo sinal de vida).
 */
object ListasPainel {
    private const val PREFS = "listas_painel"
    private const val K_ID = "lista_id"
    private const val K_CMD = "lista_cmd"
    private const val K_FILA = "fila"
    private const val MAX_FILA = 6
    @Volatile private var enviando = false
    private val trava = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Rodada nova (a fase de mensagens que vem depois de entrar continua a MESMA lista): um número novo para a lista.
     * [cmdId] = o pedido do painel (Novo envio) que começou a rodada, ou "" se começou no próprio celular.
     */
    fun nova(ctx: Context, cmdId: String) {
        val id = java.lang.Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((Math.random() * 0xFFFF).toInt())
        prefs(ctx).edit().putString(K_ID, id.take(20)).putString(K_CMD, cmdId.filter { it.isLetterOrDigit() }.take(24)).apply()
    }

    /**
     * Resultado de uma fase. [statuses] na mesma ordem de [links]; "not_done" = o celular não chegou nesse grupo
     * (rodada finalizada antes do fim). Só entrada (JOIN) e mensagens (MESSAGES) vão para o painel.
     */
    fun enviar(ctx: Context, fase: String, pkg: String, links: List<String>, statuses: List<String>) {
        try {
            val app = ctx.applicationContext
            if (!PanelClient.isPaired(app) || links.isEmpty()) return
            if (fase != Phase.JOIN && fase != Phase.MESSAGES) return
            val p = prefs(app)
            if (p.getString(K_ID, "").orEmpty().isEmpty()) nova(app, "")
            val itens = JSONArray()
            for (i in links.indices) itens.put(JSONArray().put(links[i]).put(statuses.getOrElse(i) { "not_done" }.ifEmpty { "not_done" }))
            val o = JSONObject()
                .put("runId", p.getString(K_ID, "").orEmpty())
                .put("cmdId", p.getString(K_CMD, "").orEmpty())
                .put("phase", fase)
                .put("pkg", pkg)
                .put("at", System.currentTimeMillis())
                .put("items", itens)
            synchronized(trava) {
                val fila = try { JSONArray(p.getString(K_FILA, "[]")) } catch (e: Exception) { JSONArray() }
                fila.put(o)
                val fica = JSONArray()
                val ini = if (fila.length() > MAX_FILA) fila.length() - MAX_FILA else 0
                for (i in ini until fila.length()) fica.put(fila.get(i))
                p.edit().putString(K_FILA, fica.toString()).apply()
            }
            RunLog.add(app, "☁ Lista: resultado de ${links.size} grupos (${Phase.label(fase)}) vai para o painel")
            descarregar(app)
        } catch (e: Exception) { }
    }

    /** Manda o que estiver esperando na fila (fora da tela principal). Chamado também depois de cada sinal que deu certo. */
    fun descarregar(ctx: Context) {
        val app = ctx.applicationContext
        if (enviando || !PanelClient.isPaired(app)) return
        val p = prefs(app)
        val texto = p.getString(K_FILA, "[]").orEmpty()
        if (texto.isEmpty() || texto == "[]") return
        enviando = true
        Thread {
            try {
                val fila = try { JSONArray(texto) } catch (e: Exception) { JSONArray() }
                var feitos = 0
                for (i in 0 until fila.length()) {
                    val r = PanelClient.postLista(app, fila.getJSONObject(i).toString())
                    // 2xx: o painel guardou. 400/413: o painel recusou (mandar de novo não adianta). Outro erro: tenta depois.
                    if (r in 200..299 || r == 400 || r == 413) feitos++ else break
                }
                if (feitos > 0) {
                    synchronized(trava) {
                        val atual = try { JSONArray(p.getString(K_FILA, "[]")) } catch (e: Exception) { JSONArray() }
                        val resto = JSONArray()
                        for (i in feitos until atual.length()) resto.put(atual.get(i))
                        p.edit().putString(K_FILA, resto.toString()).apply()
                    }
                }
            } catch (e: Exception) {
            } finally {
                enviando = false
            }
        }.start()
    }
}

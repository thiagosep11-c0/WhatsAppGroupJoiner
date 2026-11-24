package com.groupjoiner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Fotos que saem ANTES do texto em cada grupo (aba Por lista e envios do painel online).
 *
 * - As fotos ficam numa pasta só do app (filesDir/fotos), no máximo [MAX], e a lista/ajustes no arquivo de ajustes
 *   (não são apagados por "Limpar histórico").
 * - Rodízio: "seq" (em ordem: 1, 2, 3, 1, 2, 3...) ou "rnd" (ao acaso, nunca a mesma duas vezes seguidas). O rodízio só anda
 *   quando a foto SAIU de verdade num grupo; se o grupo for refeito, ele usa a mesma foto.
 * - A foto da vez entra no grupo pelo "teclado" do On Groups (OnGroupsKeyboard): ele coloca o arquivo direto na caixa de
 *   mensagem do grupo aberto, sem passar pela galeria (Android 11 ou mais novo, com o teclado ativado uma vez pelo usuário).
 * - A versão 1.0.68 colocava a foto numa pasta "On Groups" da galeria; [cleanup] ainda tira de lá o que tiver sobrado.
 */
object PhotoStore {
    const val MAX = 10
    const val ALBUM = "On Groups"
    private const val DIR = "fotos"
    private const val K_LIST = "photos_list"          // nomes dos arquivos, em ordem (JSON)
    private const val K_ON = "photos_on"              // "1" = mandar foto antes do texto
    private const val K_ROT = "photos_rot"            // "seq" ou "rnd"
    private const val K_NEXT = "photos_next"          // próxima foto do rodízio em ordem
    private const val K_LAST = "photos_last"          // última foto usada (para o rodízio ao acaso não repetir)
    private const val K_INSERTED = "photos_inserted"  // endereços das fotos colocadas na galeria (para tirar de lá)
    private const val MAX_SIDE = 1600
    private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    @Volatile var sentFor = ""         // grupo (link|pacote) que já recebeu a foto nesta rodada
    @Volatile private var pickKey = ""  // grupo para o qual a foto atual foi escolhida
    @Volatile private var pickIdx = -1  // posição da foto escolhida para esse grupo
    @Volatile var dumps = 0            // diagnósticos de tela gravados nesta sessão (limite no serviço)
    @Volatile var captionFails = 0     // grupos desta rodada em que a legenda não deu (com 2, o resto da rodada vai sem legenda)

    /** Android 11 ou mais novo: só assim o serviço de acessibilidade consegue trocar para o teclado do On Groups e voltar. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun names(ctx: Context): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(StorageManager.getPref(ctx, K_LIST, "[]"))
            for (i in 0 until a.length()) {
                val n = a.getString(i)
                if (n.isNotEmpty() && !n.contains('/') && !n.contains('\\')) out.add(n)
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveNames(ctx: Context, l: List<String>) {
        StorageManager.setPref(ctx, K_LIST, JSONArray(l).toString())
    }

    /** As fotos guardadas que existem de verdade, na ordem do rodízio. */
    fun list(ctx: Context): List<File> {
        val d = dir(ctx)
        return names(ctx).map { File(d, it) }.filter { it.isFile && it.length() > 0L }
    }

    fun count(ctx: Context): Int = list(ctx).size

    fun isOn(ctx: Context): Boolean = StorageManager.getPref(ctx, K_ON) == "1"

    fun setOn(ctx: Context, on: Boolean) {
        StorageManager.setPref(ctx, K_ON, if (on) "1" else "0")
    }

    /** "seq" (em ordem) ou "rnd" (ao acaso). */
    fun mode(ctx: Context): String = if (StorageManager.getPref(ctx, K_ROT, "seq") == "rnd") "rnd" else "seq"

    fun setMode(ctx: Context, m: String) {
        StorageManager.setPref(ctx, K_ROT, if (m == "rnd") "rnd" else "seq")
    }

    /** O texto vai como LEGENDA da foto (uma mensagem só). Vem ligado: foi o jeito que o usuário pediu. */
    fun captionOn(ctx: Context): Boolean = StorageManager.getPref(ctx, "photos_caption", "1") == "1"

    fun setCaption(ctx: Context, on: Boolean) {
        StorageManager.setPref(ctx, "photos_caption", if (on) "1" else "0")
    }

    /** Vai mandar foto nesta rodada? (ligado, com ao menos uma foto e num Android que deixa). */
    fun isActive(ctx: Context): Boolean = supported() && isOn(ctx) && count(ctx) > 0

    fun modeText(ctx: Context): String = if (mode(ctx) == "rnd") "ao acaso" else "em ordem"

    /** Nova rodada: nenhum grupo recebeu foto ainda. */
    fun resetRun() {
        sentFor = ""
        pickKey = ""
        pickIdx = -1
        captionFails = 0
    }

    private fun resetRotation(ctx: Context) {
        StorageManager.setPref(ctx, K_NEXT, "0")
        StorageManager.setPref(ctx, K_LAST, "-1")
        pickKey = ""
        pickIdx = -1
    }

    // ---------- guardar e tirar fotos ----------

    /** Guarda os bytes de uma foto já pronta (vem do painel). Devolve null se deu certo ou o motivo. */
    fun saveBytes(ctx: Context, bytes: ByteArray): String? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return "o arquivo não é uma imagem"
            val l = names(ctx)
            if (l.size >= MAX) return "no máximo $MAX fotos"
            val name = "foto_" + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999) + ".jpg"
            File(dir(ctx), name).writeBytes(bytes)
            l.add(name)
            saveNames(ctx, l)
            resetRotation(ctx)
            return null
        } catch (e: Exception) {
            return "erro ao guardar a foto: " + (e.message ?: "?")
        }
    }

    /** Troca todas as fotos guardadas pelas novas (as que vêm do painel). Devolve null se deu certo ou o motivo. */
    fun replaceAll(ctx: Context, all: List<ByteArray>): String? {
        for (b in all) {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return "uma das fotos veio estragada"
        }
        if (all.size > MAX) return "no máximo $MAX fotos"
        clear(ctx)
        for (b in all) {
            val erro = saveBytes(ctx, b)
            if (erro != null) return erro
        }
        return null
    }

    /**
     * Guarda uma foto escolhida na galeria do celular: confere a orientação, reduz (no máximo 1600 pontos no lado maior) e
     * salva em JPEG com fundo branco. Pode demorar um pouco: chame fora da tela principal. Devolve null ou o motivo.
     */
    fun addFromUri(ctx: Context, uri: Uri): String? {
        val app = ctx.applicationContext
        if (names(app).size >= MAX) return "no máximo $MAX fotos"
        val tmp = File(app.cacheDir, "foto_origem_" + System.nanoTime() + ".bin")
        try {
            val input = app.contentResolver.openInputStream(uri) ?: return "não consegui abrir a foto escolhida"
            var total = 0L
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SOURCE_BYTES) return "a foto é grande demais"
                        out.write(buf, 0, n)
                    }
                }
            }
            val path = tmp.absolutePath
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "esse arquivo não é uma imagem que o celular consiga abrir"
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return "não consegui abrir a foto"

            // a câmera guarda a foto "deitada" e anota a rotação: aplica a rotação e já reduz de uma vez
            val orient = try {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val m = Matrix()
            when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> { }
            }
            val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height).toFloat())
            if (k < 1f) m.postScale(k, k)
            val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            // fundo branco: PNG com partes transparentes não pode virar preto no JPEG
            val flat = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(turned, 0f, 0f, null)
            val baos = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 88, baos)
            if (turned !== decoded) decoded.recycle()
            turned.recycle()
            flat.recycle()
            return saveBytes(app, baos.toByteArray())
        } catch (e: OutOfMemoryError) {
            return "a foto é grande demais para o celular"
        } catch (e: Exception) {
            return "não consegui usar a foto: " + (e.message ?: "erro")
        } finally {
            try { tmp.delete() } catch (e: Exception) { }
        }
    }

    fun remove(ctx: Context, file: File) {
        val l = names(ctx)
        l.remove(file.name)
        saveNames(ctx, l)
        try { file.delete() } catch (e: Exception) { }
        resetRotation(ctx)
    }

    fun clear(ctx: Context) {
        for (f in list(ctx)) {
            try { f.delete() } catch (e: Exception) { }
        }
        saveNames(ctx, emptyList())
        resetRotation(ctx)
    }

    /** Miniatura para a tela (lado maior perto de [px]). Pode demorar: chame fora da tela principal. */
    fun thumbnail(file: File, px: Int): Bitmap? {
        return try {
            val b = BitmapFactory.Options()
            b.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= px) s *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = s
            BitmapFactory.decodeFile(file.absolutePath, o)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- rodízio ----------

    /** A foto deste grupo. Chamando de novo para o MESMO grupo (grupo refeito) devolve a mesma foto. */
    fun pickFor(ctx: Context, key: String): File? {
        val files = list(ctx)
        if (files.isEmpty()) return null
        if (key == pickKey && pickIdx in files.indices) return files[pickIdx]
        val n = files.size
        val last = (StorageManager.getPref(ctx, K_LAST, "-1").toIntOrNull() ?: -1)
        val idx = if (mode(ctx) == "rnd") {
            if (n == 1) {
                0
            } else {
                var i = Random.nextInt(n)
                while (i == last) i = Random.nextInt(n)
                i
            }
        } else {
            val proxima = StorageManager.getPref(ctx, K_NEXT, "0").toIntOrNull() ?: 0
            ((proxima % n) + n) % n
        }
        pickKey = key
        pickIdx = idx
        return files[idx]
    }

    /** A foto saiu neste grupo: o rodízio anda e o grupo não recebe a foto de novo nesta rodada. */
    fun commitSent(ctx: Context, key: String) {
        sentFor = key
        val n = count(ctx)
        if (pickIdx >= 0 && n > 0) {
            StorageManager.setPref(ctx, K_LAST, pickIdx.toString())
            StorageManager.setPref(ctx, K_NEXT, ((pickIdx + 1) % n).toString())
        }
    }

    fun alreadySent(key: String): Boolean = sentFor.isNotEmpty() && sentFor == key

    /** A foto anotada como enviada para este grupo NÃO saiu (ex.: Enviar da prévia sem efeito): o grupo volta a precisar da foto. */
    fun uncommit(key: String) {
        if (sentFor == key) sentFor = ""
    }

    /**
     * Cópia em PNG da foto (para um campo de mensagem que só aceita PNG pelo teclado). Fica numa pasta temporária que o
     * FileProvider entrega (res/xml/file_paths.xml); a cópia anterior é trocada. Devolve null se não deu.
     */
    fun pngCopy(ctx: Context, file: File): File? {
        return try {
            val dir = File(ctx.applicationContext.cacheDir, "fotos_envio").apply { mkdirs() }
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val out = File(dir, "foto_envio.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            out
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- sobras da versão 1.0.68 (que colocava a foto numa pasta da galeria) ----------

    private fun insertedList(ctx: Context): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(StorageManager.getPref(ctx, K_INSERTED, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        return out
    }

    /** Tira da galeria as fotos que o app colocou lá (só [only], se vier uma lista). */
    fun cleanup(ctx: Context, only: List<String>? = null) {
        try {
            val app = ctx.applicationContext
            val todas = insertedList(app)
            if (todas.isEmpty()) return
            val alvo = if (only == null) todas.toList() else todas.filter { it in only }
            for (u in alvo) {
                try { app.contentResolver.delete(Uri.parse(u), null, null) } catch (e: Exception) { }
            }
            todas.removeAll(alvo)
            StorageManager.setPref(app, K_INSERTED, JSONArray(todas).toString())
        } catch (e: Exception) { }
    }

    /**
     * Tira da galeria daqui a pouco (o WhatsApp ainda pode estar lendo a foto que acabou de sair). Só as fotos que já estavam
     * lá agora: uma foto colocada depois (grupo refeito) não é apagada no meio do envio.
     */
    fun cleanupLater(ctx: Context, delayMs: Long) {
        val app = ctx.applicationContext
        val agora = insertedList(app)
        if (agora.isEmpty()) return
        Handler(Looper.getMainLooper()).postDelayed({ cleanup(app, agora) }, delayMs)
    }
}

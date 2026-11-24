package com.groupjoiner

import android.content.ClipDescription
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * "Teclado" do On Groups. Não tem teclas e não lê nem guarda nada do que é digitado.
 * Serve só para uma coisa: quando o serviço de acessibilidade pede (KeyboardBridge.pending), coloca a FOTO da vez na caixa
 * de mensagem do grupo aberto no WhatsApp, do mesmo jeito que um teclado coloca um GIF ou uma figurinha. Logo depois o
 * serviço volta para o teclado normal do usuário. Se alguém escolher este teclado sem pedido nenhum, ele volta sozinho.
 */
class OnGroupsKeyboard : InputMethodService() {

    companion object {
        const val AUTHORITY = "com.groupjoiner.fotos"   // FileProvider do app (pastas em res/xml/file_paths.xml)
        @Volatile var instance: OnGroupsKeyboard? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(32, 20, 32, 20)
        box.setBackgroundColor(Color.parseColor("#ECEFF1"))
        val t = TextView(this)
        t.text = "On Groups: colocando a foto no grupo…"
        t.setTextColor(Color.parseColor("#37474F"))
        t.textSize = 14f
        box.addView(t)
        val b = Button(this)
        b.text = "Voltar ao meu teclado"
        b.setOnClickListener { backToUserKeyboard() }
        box.addView(b)
        return box
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        tryCommitPending()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        tryCommitPending()
        // escolhido sem pedido do app (ou o pedido já foi entregue): volta para o teclado normal daqui a pouco,
        // só se este ainda for o teclado em uso (o serviço normalmente já voltou antes)
        if (KeyboardBridge.pending == null) {
            handler.postDelayed({
                if (KeyboardBridge.pending == null && KeyboardBridge.currentIme(this) == KeyboardBridge.imeId(this)) {
                    try { if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() } catch (e: Exception) { }
                }
            }, 2_500L)
        }
    }

    /** Se o serviço pediu uma foto para o campo em que este teclado está agora, entrega (uma vez só). */
    fun tryCommitPending() {
        val req = KeyboardBridge.pending ?: return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        if (info.packageName != req.pkg) return   // só no WhatsApp escolhido para a rodada
        KeyboardBridge.pending = null
        val aceitos = try { EditorInfoCompat.getContentMimeTypes(info) } catch (e: Exception) { emptyArray<String>() }
        val lista = aceitos.joinToString(", ").ifEmpty { "nenhum" }
        val mime = chooseMime(aceitos)
        if (mime == null) {
            KeyboardBridge.result = KeyboardBridge.Result(false, "", lista, "o campo de mensagem não aceita foto pelo teclado", System.currentTimeMillis())
            return
        }
        try {
            val arquivo: File? = if (mime == "image/png") PhotoStore.pngCopy(this, req.file) else req.file
            if (arquivo == null) {
                KeyboardBridge.result = KeyboardBridge.Result(false, mime, lista, "não consegui preparar a foto em PNG", System.currentTimeMillis())
                return
            }
            val uri: Uri = FileProvider.getUriForFile(this, AUTHORITY, arquivo)
            val conteudo = InputContentInfoCompat(uri, ClipDescription("foto", arrayOf(mime)), null)
            val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
            if (Build.VERSION.SDK_INT < 25) {
                try { grantUriPermission(req.pkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            }
            val ok = InputConnectionCompat.commitContent(ic, info, conteudo, flags, null)
            KeyboardBridge.result = KeyboardBridge.Result(
                ok, mime, lista, if (ok) "foto entregue ao WhatsApp" else "o WhatsApp recusou a foto", System.currentTimeMillis()
            )
        } catch (e: Exception) {
            KeyboardBridge.result = KeyboardBridge.Result(false, mime, lista, "erro: " + (e.message ?: "?"), System.currentTimeMillis())
        }
    }

    // Formato que o campo aceita: JPEG (o da foto) se der; senão PNG (convertido na hora). GIF/figurinha não servem.
    private fun chooseMime(aceitos: Array<String>): String? {
        if (aceitos.any { ClipDescription.compareMimeTypes("image/jpeg", it) }) return "image/jpeg"
        if (aceitos.any { ClipDescription.compareMimeTypes("image/png", it) }) return "image/png"
        return null
    }

    /** Volta para o teclado que o usuário usava antes. */
    fun backToUserKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return
        } catch (e: Exception) { }
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } catch (e: Exception) { }
    }
}

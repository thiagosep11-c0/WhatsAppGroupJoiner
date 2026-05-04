package com.groupjoiner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editTextLinks: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPermission: Button
    private lateinit var tvStatus: TextView
    private lateinit var radioNormal: RadioButton
    private lateinit var radioBusiness: RadioButton

    private val handler = Handler(Looper.getMainLooper())
    private var linkList = mutableListOf<String>()
    private var currentIndex = 0

    // Intervalos: 30s -> 2s -> 60s, repetindo
    private val intervals = listOf(30_000L, 2_000L, 60_000L)

    companion object {
        var instance: MainActivity? = null
        var selectedPackage = "com.whatsapp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)

        editTextLinks   = findViewById(R.id.editTextLinks)
        btnStart        = findViewById(R.id.btnStart)
        btnPermission   = findViewById(R.id.btnPermission)
        tvStatus        = findViewById(R.id.tvStatus)
        radioNormal     = findViewById(R.id.radioNormal)
        radioBusiness   = findViewById(R.id.radioBusiness)

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão necessária")
                    .setMessage("Ative o 'Group Joiner Service' em Configurações > Acessibilidade.")
                    .setPositiveButton("Ir para Configurações") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@setOnClickListener
            }

            selectedPackage = if (radioBusiness.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

            val raw = editTextLinks.text.toString().trim()
            linkList = raw.split("\n")
                .map { it.trim() }
                .filter { it.startsWith("http") }
                .toMutableList()

            if (linkList.isEmpty()) {
                Toast.makeText(this, "Cole pelo menos um link válido!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentIndex = 0
            btnStart.isEnabled = false
            tvStatus.text = "Iniciando..."
            openNextLink()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any { it.id.contains(packageName) }
    }

    fun openNextLink() {
        if (currentIndex >= linkList.size) {
            runOnUiThread {
                tvStatus.text = "✅ Concluído! ${linkList.size} grupos processados."
                btnStart.isEnabled = true
            }
            return
        }

        val link = linkList[currentIndex]
        runOnUiThread {
            tvStatus.text = "🔗 Abrindo ${currentIndex + 1}/${linkList.size}..."
        }

        // Avisa o serviço que estamos esperando a tela de grupo
        GroupJoinerService.serviceInstance?.setWaitingForGroup(true)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setPackage(selectedPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "❌ Erro no link ${currentIndex + 1}. Pulando..." }
            GroupJoinerService.serviceInstance?.setWaitingForGroup(false)
            scheduleNext()
        }
    }

    fun onGroupProcessed(success: Boolean) {
        val msg = if (success)
            "✅ Entrou no grupo ${currentIndex + 1}/${linkList.size}"
        else
            "⚠️ Grupo ${currentIndex + 1} inválido ou já é membro"

        runOnUiThread { tvStatus.text = msg }
        scheduleNext()
    }

    private fun scheduleNext() {
        val delay = intervals[currentIndex % intervals.size]
        val delaySec = delay / 1000
        runOnUiThread {
            tvStatus.append("\n⏳ Próximo em ${delaySec}s...")
        }
        currentIndex++
        handler.postDelayed({ openNextLink() }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
    }
}

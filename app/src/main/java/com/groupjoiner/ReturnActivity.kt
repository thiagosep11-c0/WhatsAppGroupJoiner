package com.groupjoiner

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val status = intent.getStringExtra("status") ?: "invalid"
        val token = intent.getLongExtra("token", -1L)
        
        // Abre MainActivity por cima
        val mainIntent = Intent(this, MainActivity::class.java)
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(mainIntent)
        
        // Notifica o resultado com token para evitar duplicatas
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MainActivity.instance?.onGroupProcessed(status, token)
        }, 400L)
        
        finish()
    }
}

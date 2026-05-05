package com.groupjoiner

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val status = intent.getStringExtra("status") ?: "invalid"
        
        // Abre MainActivity por cima
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        
        // Notifica o resultado
        MainActivity.instance?.onGroupProcessed(status)
        
        finish()
    }
}

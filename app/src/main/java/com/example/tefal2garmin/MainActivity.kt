package com.example.tefal2garmin

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

class MainActivity : AppCompatActivity() {
    
    private lateinit var textView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        textView = findViewById(R.id.textView)
        
        // Handle share intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
                
                val message = if (sharedUri != null) {
                    "CSV file shared via URI: $sharedUri"
                } else if (sharedText != null) {
                    "CSV file shared via TEXT: ${sharedText.take(100)}${if (sharedText.length > 100) "..." else ""}"
                } else {
                    "Welcome to Tefal2Garmin Sync"
                }
                
                textView.text = message
            }
            else -> {
                textView.text = "Welcome to Tefal2Garmin Sync"
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getParcelableExtra(name) as? T
        }
    }
}
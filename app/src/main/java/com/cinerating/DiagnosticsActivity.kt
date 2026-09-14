package com.cinerating

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cinerating.util.DiagLog

/**
 * No-ADB debugger: shows what the accessibility service sees
 * (packages, titles found, rating results, overlay errors).
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        diagText = findViewById(R.id.diagText)

        findViewById<Button>(R.id.diagRefreshButton).setOnClickListener {
            refresh()
        }
        findViewById<Button>(R.id.diagClearButton).setOnClickListener {
            DiagLog.clear(this)
            refresh()
        }
        findViewById<Button>(R.id.diagCloseButton).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        diagText.text = DiagLog.snapshot(this)
    }
}

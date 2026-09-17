package com.cinerating

import android.app.ActivityManager
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
        diagText.text = memHeader() + "\n\n" + DiagLog.snapshot(this)
    }

    /** Same-process PSS + Java heap: the number that matters on low-RAM TVs. */
    private fun memHeader(): String {
        val pssMb = runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            if (info.isNotEmpty()) info[0].totalPss / 1024 else -1
        }.getOrDefault(-1)
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val pssTxt = if (pssMb >= 0) "${pssMb}MB" else "?"
        return "mem: pss=$pssTxt javaHeap=${heapMb}MB (same process as service)"
    }
}

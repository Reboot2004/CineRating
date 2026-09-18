package com.cinerating

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cinerating.service.CineRatingForegroundService
import com.cinerating.ocr.OcrCaptureService
import com.cinerating.ocr.ScreenCaptureConsentActivity
import com.cinerating.update.UpdateChecker
import com.cinerating.update.UpdateDownloader
import com.cinerating.update.UpdateInfo
import com.cinerating.update.UpdateInstaller
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var startServiceButton: Button
    private lateinit var checkUpdatesButton: Button
    private lateinit var versionText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var a11yStatusText: TextView
    private lateinit var captureStatusText: TextView

    private lateinit var updateChecker: UpdateChecker
    private var pendingUpdate: UpdateInfo? = null
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateChecker = UpdateChecker(this)

        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        startServiceButton = findViewById(R.id.startServiceButton)
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton)
        versionText = findViewById(R.id.versionText)
        updateStatusText = findViewById(R.id.updateStatusText)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        a11yStatusText = findViewById(R.id.a11yStatusText)
        captureStatusText = findViewById(R.id.captureStatusText)

        versionText.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            openOverlayPermissionScreen()
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startServiceButton.setOnClickListener {
            if (CineRatingForegroundService.isRunning) {
                stopCineRatingService()
            } else if (isOverlayPermissionGranted() && isAccessibilityEnabled()) {
                startCineRatingService()
            }
            updateStatus()
        }

        checkUpdatesButton.setOnClickListener {
            runUpdateCheck(force = true)
        }

        // Fallback for TVs where the overlay settings screen is missing:
        // App info → Special app access path, same place the user found manually.
        findViewById<Button>(R.id.appInfoButton).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        // OCR screen reading: one system consent, re-allowed after each reboot.
        // ML Kit text v2 needs API 23+ — older TVs keep the tree-only path.
        findViewById<Button>(R.id.captureButton).setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                updateStatusText.text = "Screen reading needs Android 6+ for on-device OCR"
            } else if (!OcrCaptureService.hasPlayServices(this)) {
                updateStatusText.text = "Google Play Services missing — OCR unavailable"
            } else {
                // The system prompt ("see everything on screen") scares off
                // remote users into BACK/Deny — explain first, then ask.
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.capture_rationale_title))
                    .setMessage(getString(R.string.capture_rationale_text))
                    .setPositiveButton(getString(R.string.capture_continue)) { _, _ ->
                        ScreenCaptureConsentActivity.start(this)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // Prompted auto-check: launch + max once per 24h (UpdateChecker gates).
        runUpdateCheck(force = false)
    }

    private fun runUpdateCheck(force: Boolean) {
        checkUpdatesButton.isEnabled = false
        checkUpdatesButton.text = getString(R.string.checking_updates)
        lifecycleScope.launch {
            try {
                val info = updateChecker.check(force)
                if (info != null) {
                    pendingUpdate = info
                    updateStatusText.text =
                        "Update ${info.versionName} available · last check ${updateChecker.lastCheckText()}"
                    showUpdateDialog(info)
                } else if (force) {
                    updateStatusText.text =
                        "${getString(R.string.up_to_date)} · last check ${updateChecker.lastCheckText()}"
                }
            } catch (e: Exception) {
                if (force) {
                    updateStatusText.text = "${getString(R.string.update_error)}: ${e.message}"
                }
            } finally {
                checkUpdatesButton.isEnabled = true
                checkUpdatesButton.text = getString(R.string.check_updates)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val message = buildString {
            append("v${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) append(info.changelog.trim())
            else append("Bug fixes and improvements.")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                startUpdateDownload(info)
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .setCancelable(!info.mandatory)
            .show()
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (!UpdateInstaller.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_available_title))
                .setMessage(getString(R.string.update_install_prompt))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runCatching {
                        startActivity(UpdateInstaller.unknownSourcesIntent(this))
                    }
                }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
            return
        }

        progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading))
            .setMessage("0%")
            .setCancelable(false)
            .create()
            .also { it.show() }

        lifecycleScope.launch {
            try {
                val apk = UpdateDownloader(this@MainActivity).download(info) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    progressDialog?.setMessage("$pct%")
                }
                updateStatusText.text = "Download complete — opening installer…"
                runCatching {
                    startActivity(UpdateInstaller.installIntent(this@MainActivity, apk))
                }
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                updateStatusText.text = "${getString(R.string.update_error)}: ${e.message}"
            } finally {
                // Always release the window, even if BACK was pressed mid-download.
                progressDialog?.dismiss()
                progressDialog = null
            }
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private fun openOverlayPermissionScreen() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startCineRatingService() {
        val intent = Intent(this, CineRatingForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCineRatingService() {
        val intent = Intent(this, CineRatingForegroundService::class.java)
        stopService(intent)
    }

    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponent = ComponentName(this, com.cinerating.service.CineRatingAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == expectedComponent }
    }

    private fun updateStatus() {
        val overlayGranted = isOverlayPermissionGranted()
        val a11yOn = isAccessibilityEnabled()
        val permissionsGranted = a11yOn && overlayGranted
        val active = CineRatingForegroundService.isRunning

        // SYSTEM_ALERT_WINDOW never appears in normal permission lists — spell it out.
        overlayStatusText.text =
            if (overlayGranted) "Overlay: GRANTED (can draw badges)"
            else "Overlay: NOT GRANTED → use Enable Overlay, or App info → Special app access"
        overlayStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted) R.color.detected_green else R.color.text_muted
            )
        )
        a11yStatusText.text =
            if (a11yOn) "Accessibility: ON" else "Accessibility: OFF"
        a11yStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11yOn) R.color.detected_green else R.color.text_muted
            )
        )

        val capOn = OcrCaptureService.isActive
        val capEnabled = OcrCaptureService.isEnabled(this)
        captureStatusText.text = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "Screen reading: unavailable (needs Android 6+)"
            capOn -> "Screen reading: ON (OCR for poster grids)"
            capEnabled -> "Screen reading: OFF — re-allow after reboot (tap Enable)"
            else -> "Screen reading: OFF (optional, reads poster grids)"
        }
        captureStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (capOn) R.color.detected_green else R.color.text_muted
            )
        )

        if (active) {
            statusIndicator.alpha = 1f
            statusIndicator.setBackgroundResource(R.drawable.circle_green)
            statusText.text = "CineRating is Active"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.detected_green))
            startServiceButton.text = "Stop CineRating"
        } else {
            statusIndicator.alpha = 0.35f
            statusIndicator.setBackgroundResource(R.drawable.detected_dot)
            statusText.text = if (permissionsGranted) "Service is Paused" else "Grant permissions to start"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            startServiceButton.text = "Start CineRating"
        }
    }
}

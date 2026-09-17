package com.cinerating.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.cinerating.util.DiagLog

/**
 * Transparent trampoline: shows the system screen-capture consent once,
 * hands the token to OcrCaptureService. Re-consent is required after
 * every reboot (Android limitation) — MainActivity prompts for that.
 */
class ScreenCaptureConsentActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 3703

        fun start(context: Context) {
            context.startActivity(
                Intent(context, ScreenCaptureConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Legacy onActivityResult for single-shot consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                OcrCaptureService.setEnabled(this, true)
                val svc = Intent(this, OcrCaptureService::class.java).apply {
                    putExtra(OcrCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OcrCaptureService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, svc)
                DiagLog.log(this, "ocr: consent granted")
            } else {
                OcrCaptureService.setEnabled(this, false)
                DiagLog.log(this, "ocr: consent denied")
            }
        }
        finish()
    }
}

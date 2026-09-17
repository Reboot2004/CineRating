package com.cinerating.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cinerating.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the MediaProjection token and takes SINGLE snapshots on demand.
 * No continuous recording: a virtual display is created per capture and
 * released immediately, bounding CPU/battery cost on old TVs.
 *
 * Runs in the app process, so the bound Binder passes Bitmaps by reference
 * (no 1MB transaction limit issues).
 */
class OcrCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): OcrCaptureService = this@OcrCaptureService
    }

    private val binder = LocalBinder()
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    companion object {
        private const val TAG = "OcrCapture"
        private const val NOTIF_ID = 3702
        private const val CHANNEL_ID = "cinerating_capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        const val PREFS = "cinerating_capture"
        const val KEY_ENABLED = "enabled"

        @Volatile
        var isActive = false
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun hasPlayServices(context: Context): Boolean = runCatching {
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    "com.google.android.gms",
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.google.android.gms", 0)
            }
            pi.applicationInfo?.enabled == true
        }.getOrDefault(false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        runCatching {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.stop()
            projection = mgr.getMediaProjection(resultCode, data).also { mp ->
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        isActive = false
                        DiagLog.log(this@OcrCaptureService, "ocr: projection stopped")
                        stopSelf()
                    }
                }
                projectionCallback = cb
                mp.registerCallback(cb, null)
            }
            isActive = true
            DiagLog.log(this, "ocr: screen capture active")
        }.onFailure {
            Log.e(TAG, "projection: ${it.message}")
            DiagLog.log(this, "ocr: projection failed: ${it.message}")
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * One snapshot at half resolution (bounds the OCR cost on old hardware).
     * Returns null when projection is down or no frame arrives in time.
     */
    suspend fun captureOnce(): Bitmap? {
        val mp = projection ?: return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                singleShot(mp)
            }
        }
    }

    private fun singleShot(mp: MediaProjection): Bitmap? {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val w = (metrics.widthPixels / 2).coerceAtLeast(480)
        val h = (metrics.heightPixels / 2).coerceAtLeast(270)

        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = mp.createVirtualDisplay(
                "cine-snap", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            var image: android.media.Image? = null
            // Drain stale frames, settle on a fresh one (~0.5s in).
            for (i in 0 until 20) {
                Thread.sleep(150)
                val latest = reader.acquireLatestImage()
                if (latest != null) {
                    runCatching { image?.close() }
                    image = latest
                    if (i >= 2) break
                }
            }
            val img = image ?: return null
            try {
                imageToBitmap(img, w, h)
            } finally {
                img.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture: ${e.message}")
            null
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
        }
    }

    private fun imageToBitmap(img: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        if (padded.width == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CineRating screen reading", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CineRating screen reading")
            .setContentText("Single snapshots for ratings. Stop via Reboot or force-stop.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isActive = false
        runCatching {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }
}

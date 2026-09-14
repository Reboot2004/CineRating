package com.cinerating.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.cinerating.R
import com.cinerating.model.RatingResult

class OverlayManager(
    private val context: Context,
    private val onScanRequested: () -> Unit,
    private val onError: (String) -> Unit = {}
) {

    companion object {
        const val MAX_BADGES = 12
        private const val BADGE_TTL_MS = 15000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CineRating)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val activeOverlays = mutableListOf<View>()
    private var scanButton: View? = null

    fun showScanButton() {
        if (scanButton != null) return

        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_scan_trigger, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 50
            y = 50
        }

        view.findViewById<View>(R.id.btn_scan).setOnClickListener {
            onScanRequested()
        }

        try {
            windowManager.addView(view, params)
            scanButton = view
        } catch (e: Exception) {
            onError("scan-button addView failed (overlay permission?): ${e.message}")
        }
    }

    fun showMinimal(result: RatingResult, anchorRect: Rect) {
        // Cap concurrent badges — home screens can expose 20+ titles, we show the first 12.
        if (activeOverlays.size >= MAX_BADGES) return
        mainHandler.post {
            if (activeOverlays.size >= MAX_BADGES) return@post
            val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_rating_minimal, null)

            val imdbView = view.findViewById<TextView>(R.id.imdbScore)
            val rtView = view.findViewById<TextView>(R.id.rtScore)
            val divider = view.findViewById<View>(R.id.scoreDivider)

            // ★ prefix keeps IMDb-only badges scannable on TV at 10ft.
            imdbView.text = "★ ${result.imdbScore.replace("/10", "").trim()}"
            if (result.rtScore.isBlank() || result.rtScore.equals("N/A", ignoreCase = true)) {
                // RT optional — hide divider + RT score, IMDb-only badge.
                rtView.visibility = View.GONE
                divider.visibility = View.GONE
            } else {
                rtView.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                rtView.text = result.rtScore
            }

            val metrics = context.resources.displayMetrics
            val density = metrics.density
            // Estimate badge size before layout; clamp so badges never go off-screen.
            val estWidthPx = (170 * density).toInt()
            val estHeightPx = (36 * density).toInt()
            var x = anchorRect.left
            var y = anchorRect.top - (70 * density).toInt()
            x = x.coerceIn(8, (metrics.widthPixels - estWidthPx).coerceAtLeast(8))
            y = y.coerceIn(8, (metrics.heightPixels - estHeightPx).coerceAtLeast(8))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }

            try {
                view.alpha = 0f
                windowManager.addView(view, params)
                activeOverlays.add(view)
                
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).setDuration(200).start()
                
                mainHandler.postDelayed({ hide(view) }, BADGE_TTL_MS)
            } catch (e: Exception) {
                onError("badge addView failed (overlay permission?): ${e.message}")
            }
        }
    }

    fun clearAll() {
        mainHandler.post {
            val overlays = activeOverlays.toList()
            overlays.forEach { hide(it) }
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            scanButton?.let { if (it.parent != null) windowManager.removeView(it) }
        } catch (e: Exception) { }
        scanButton = null
        activeOverlays.toList().forEach {
            try {
                if (it.parent != null) windowManager.removeViewImmediate(it)
            } catch (e: Exception) { }
        }
        activeOverlays.clear()
    }

    private fun hide(view: View) {
        if (view.parent != null) {
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
                duration = 200
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (view.parent != null) {
                            try {
                                windowManager.removeView(view)
                            } catch (e: Exception) {}
                            activeOverlays.remove(view)
                        }
                    }
                })
                start()
            }
        }
    }
}

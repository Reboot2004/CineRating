package com.cinerating.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cinerating.R

/**
 * Prime-X-Ray-style side panel: ONE fixed overlay listing IMDb ratings for
 * whatever is on screen. Replaces per-poster badges — no anchoring math,
 * no overlap, no 12-window churn. Rows update in place as lookups land.
 *
 * Anchored RIGHT with a 24dp margin (overscan-safe), vertically centered.
 * Passive: never focusable, TV remotes can't click it and never need to.
 */
class XrayPanelManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CineRating)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val state = XrayState()
    private var panel: View? = null
    private var rowsBox: LinearLayout? = null
    private var footer: TextView? = null
    /** title -> score view, for in-place updates */
    private val rowScores = HashMap<String, TextView>()

    fun show(titles: List<String>) {
        mainHandler.post {
            ensurePanel()
            state.setTitles(titles)
            rebuildRows()
            panel?.let {
                if (it.alpha < 1f) {
                    ObjectAnimator.ofFloat(it, View.ALPHA, it.alpha, 1f)
                        .setDuration(200).start()
                }
            }
        }
    }

    fun setScore(title: String, score: String) {
        mainHandler.post {
            if (state.setScore(title, score)) {
                rowScores[title]?.text = score
            }
        }
    }

    fun removeRow(title: String) {
        mainHandler.post {
            if (state.remove(title)) rebuildRows()
        }
    }

    fun ensureRow(title: String) {
        mainHandler.post {
            ensurePanel()
            val before = state.snapshot().size
            state.ensureRow(title)
            if (state.snapshot().size != before) rebuildRows()
            panel?.let {
                if (it.alpha < 1f) {
                    ObjectAnimator.ofFloat(it, View.ALPHA, it.alpha, 1f)
                        .setDuration(200).start()
                }
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val view = panel ?: return@post
            if (view.parent != null && view.alpha > 0f) {
                ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f).apply {
                    duration = 200
                    start()
                }
            }
        }
    }

    fun setFooter(text: String) {
        mainHandler.post { footer?.text = text }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            panel?.let { if (it.parent != null) windowManager.removeViewImmediate(it) }
        } catch (e: Exception) {
        }
        panel = null
        rowsBox = null
        footer = null
        rowScores.clear()
    }

    private fun ensurePanel() {
        if (panel != null) return
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_xray_panel, null)
        rowsBox = view.findViewById(R.id.xrayRows)
        footer = view.findViewById(R.id.xrayFooter)
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (24 * density).toInt()
            y = 0
        }
        try {
            view.alpha = 0f
            windowManager.addView(view, params)
            panel = view
        } catch (e: Exception) {
            android.util.Log.e("XrayPanel", "addView failed: ${e.message}")
        }
    }

    private fun rebuildRows() {
        val box = rowsBox ?: return
        box.removeAllViews()
        rowScores.clear()
        val inflater = LayoutInflater.from(themedContext)
        for (row in state.snapshot()) {
            val v = inflater.inflate(R.layout.overlay_xray_row, box, false)
            v.findViewById<TextView>(R.id.rowTitle).text = row.title
            val scoreView = v.findViewById<TextView>(R.id.rowScore)
            scoreView.text = row.score ?: "…"
            box.addView(v)
            rowScores[row.title] = scoreView
        }
    }
}

package com.cinerating.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cinerating.api.RatingRepository
import com.cinerating.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CineRatingAccessibilityService : AccessibilityService() {
    private lateinit var overlayManager: OverlayManager
    private lateinit var ratingRepository: RatingRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pendingScan: Job? = null
    private var lastScanAt = 0L
    private var lastScreenKey = ""

    companion object {
        private const val TAG = "CineRatingService"
        private const val DEBOUNCE_MS = 600L
        private const val MIN_SCAN_GAP_MS = 1200L
        private const val MAX_TITLES_PER_SCAN = OverlayManager.MAX_BADGES
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        overlayManager = OverlayManager(this) { performFullScan("manual") }
        ratingRepository = RatingRepository()

        runCatching {
            startService(Intent(this, CineRatingForegroundService::class.java))
        }
        overlayManager.showScanButton()
    }

    // ---- Auto full-screen scan: home grid -> badges for all visible titles,
    // refresh after scroll / focus / window change (debounced for old TVs). ----

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Never scan ourselves or system UI — avoids loops and junk badges.
        if (pkg == packageName || pkg == "com.android.systemui") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scheduleAutoScan(pkg)
            else -> Unit
        }
    }

    private fun scheduleAutoScan(pkg: String) {
        pendingScan?.cancel()
        pendingScan = serviceScope.launch {
            delay(DEBOUNCE_MS)
            val now = SystemClock.uptimeMillis()
            if (now - lastScanAt < MIN_SCAN_GAP_MS) {
                delay(MIN_SCAN_GAP_MS - (now - lastScanAt))
            }
            performFullScan(pkg)
        }
    }

    private fun performFullScan(sourceApp: String) {
        val root = rootInActiveWindow ?: return
        lastScanAt = SystemClock.uptimeMillis()
        val candidates = ArrayList<Candidate>(24)
        val seen = HashSet<String>(24)
        collectCandidates(root, seen, candidates, isRoot = true)
        if (candidates.isEmpty()) return

        // Screen key = sorted titles; if unchanged since last scan, skip clearing
        // to avoid flicker when same home screen re-emits events.
        val screenKey = candidates.map { it.title }.sorted().joinToString("|")
        val screenChanged = screenKey != lastScreenKey
        lastScreenKey = screenKey
        if (screenChanged) {
            overlayManager.clearAll()
        } else {
            // Same titles visible — badges already up, nothing to do.
            return
        }

        val toFetch = candidates.take(MAX_TITLES_PER_SCAN)
        for (c in toFetch) {
            fetchAndShowOverlay(c.title, c.bounds, sourceApp)
        }
        Log.i(TAG, "Scan [$sourceApp]: ${candidates.size} titles, fetching ${toFetch.size}")
    }

    private data class Candidate(val title: String, val bounds: Rect)

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        seen: MutableSet<String>,
        out: MutableList<Candidate>,
        isRoot: Boolean
    ) {
        if (out.size >= MAX_TITLES_PER_SCAN * 2) return
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.density

        val inVerticalBand = rect.top >= screenH * 0.10 && rect.bottom <= screenH * 0.92
        if (inVerticalBand) {
            val raw = node.text?.toString() ?: node.contentDescription?.toString()
            val cleaned = cleanTitle(raw)
            if (!cleaned.isNullOrBlank() && isLikelyMovieTitle(cleaned) && seen.add(cleaned)) {
                val wDp = rect.width() / density
                val hDp = rect.height() / density
                val isMinSize = wDp > 70 && hDp > 40
                val isPosterId = node.viewIdResourceName?.contains("poster", ignoreCase = true) == true ||
                        node.viewIdResourceName?.contains("backdrop", ignoreCase = true) == true ||
                        node.viewIdResourceName?.contains("thumbnail", ignoreCase = true) == true ||
                        node.viewIdResourceName?.contains("card", ignoreCase = true) == true
                // Full-width rows are section headers ("Trending Now"), not titles.
                val isFullWidthHeader = rect.width() > screenW * 0.85 && !isPosterId
                if ((isMinSize || isPosterId || node.isClickable) && !isFullWidthHeader &&
                    rect.width() > 10 && rect.height() > 10
                ) {
                    out.add(Candidate(cleaned, Rect(rect)))
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCandidates(child, seen, out, isRoot = false)
            child.recycle()
        }
    }

    private fun fetchAndShowOverlay(title: String, rect: Rect, sourceApp: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = ratingRepository.getRatings(title, sourceApp)
                // IMDb required — skip junk text with no rating (prevents ghosting).
                if (result != null && result.imdbScore != "N/A" && result.imdbScore.isNotBlank()) {
                    overlayManager.showMinimal(result, rect)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error for $title: ${e.message}")
            }
        }
    }

    private fun isLikelyMovieTitle(text: String): Boolean {
        val blacklist = listOf(
            "Home", "Search", "Settings", "Movies", "TV", "Sports", "Watchlist", "FREE",
            "New Episode", "Premium", "Hotstar", "Netflix", "Disney+", "Watch", "Play", "Menu",
            "Episodes", "More", "Info", "Language", "Subtitles", "Next", "Back", "Skip", "Intro",
            "Details", "Resume", "Download", "Share", "Rate", "More Like This", "Trailers",
            "My Space", "Browse", "Categories", "Coming Soon", "Hubs", "Continue Watching",
            "My Downloads", "watchlist", "login", "kids", "profile", "settings",
            "Trending Now", "Top 10", "Top10", "Popular", "Recommended", "Continue watching",
            "New Releases", "Originals", "My List", "TV Shows", "Shows", "Series"
        )

        val isBlacklisted = blacklist.any { it.equals(text, ignoreCase = true) }
        val isJustNumbersOrSymbols = text.matches(Regex("^[0-9\\s·:!\\-_\\|]+$"))
        val isTooLong = text.length > 50

        return !isBlacklisted && !isJustNumbersOrSymbols && !isTooLong && text.length > 2
    }

    private fun cleanTitle(text: String?): String? {
        if (text == null) return null
        return text.replace(",Movie", "", ignoreCase = true)
            .replace(",Show", "", ignoreCase = true)
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("Season \\d+"), "")
            .trim()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pendingScan?.cancel()
        runCatching { overlayManager.destroy() }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    lastScreenKey = ""
                    overlayManager.clearAll()
                    return false
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_INFO -> {
                    pendingScan?.cancel()
                    performFullScan("manual")
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }
}

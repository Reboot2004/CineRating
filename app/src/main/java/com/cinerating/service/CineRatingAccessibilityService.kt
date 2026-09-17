package com.cinerating.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.cinerating.api.RatingRepository
import com.cinerating.model.MatchQuality
import com.cinerating.ocr.OcrCaptureService
import com.cinerating.ocr.OcrReader
import com.cinerating.overlay.OverlayManager
import com.cinerating.util.DiagLog
import com.cinerating.util.TitleFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rethink: scan ONLY known streaming apps (never launcher/settings),
 * prefer the focused item's title (walk up ancestors), full-grid scan as fallback.
 * Everything observable is mirrored to DiagLog so the TV itself is the debugger.
 */
class CineRatingAccessibilityService : AccessibilityService() {
    private lateinit var overlayManager: OverlayManager
    private lateinit var ratingRepository: RatingRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pendingScan: Job? = null
    private var lastScanAt = 0L
    private var lastScreenKey = ""
    private var lastBadgeShownAt = 0L
    private var lastSupportedPkg: String? = null
    private var lastSupportedEventAt = 0L
    private var ocrService: OcrCaptureService? = null
    private var ocrBound = false
    private val ocrConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            ocrService = (binder as? OcrCaptureService.LocalBinder)?.service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ocrService = null
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    // Safety net: TV remotes can't click overlay buttons, so badges must appear
    // with zero presses. Re-scan periodically while a streaming app is foreground;
    // the screen-key dedup makes idle screens network-free (repository cache).
    private val rescanRunnable = object : Runnable {
        override fun run() {
            val pkg = lastSupportedPkg
            val now = SystemClock.uptimeMillis()
            if (pkg != null && now - lastSupportedEventAt < 45_000 && now - lastScanAt > 10_000) {
                performFullScan(pkg, quiet = true)
            }
            mainHandler.postDelayed(this, 12_000)
        }
    }
    // After a detail page is detected, grid scans are suppressed briefly so they
    // can't replace the good badge with junk (detail pages have no poster grid).
    private var suppressGridUntil = 0L
    private val ignoredPkgsLogged = mutableSetOf<String>()

    companion object {
        private const val TAG = "CineRatingService"
        private const val DEBOUNCE_MS = 600L
        private const val MIN_SCAN_GAP_MS = 1200L
        private const val MAX_TITLES_PER_SCAN = OverlayManager.MAX_BADGES

        // Streaming apps only — matched case-insensitively against package name.
        private val APP_KEYWORDS = listOf(
            "netflix", "hotstar", "amazonvideo", "primevideo", "avod", "disney",
            "jiocinema", "media.ondemand", "sonyliv", "zee5", "appletv",
            "sunnxt", "aha", "voot"
        )
        // Exact packages (phone + TV variants), cf. Flutter's Constants.PACKAGE_*.
        private val EXACT_PACKAGES = setOf(
            "com.netflix.mediaclient", "com.netflix.ninja",
            "in.startv.hotstar", "in.startv.hotstar.dplus",
            "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus", "com.jio.media.ondemand",
            "com.google.android.videos", "com.apple.atve.androidtv.appletv"
        )
        // Never scan these even if a keyword matches.
        private val DENY_SUBSTRINGS = listOf(
            "leanbacklauncher", "tvlauncher", "systemui", "tv.settings",
            "packageinstaller", "permissioncontroller", "setupwizard",
            "launcher", "keyboard", "ime", "dream", "screensaver"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        overlayManager = OverlayManager(
            this,
            { suppressGridUntil = 0L; performFullScan("manual", force = true) },
            { msg -> DiagLog.log(this, "overlay: $msg") }
        )
        ratingRepository = RatingRepository()
        DiagLog.log(this, "service connected")

        runCatching {
            val intent = Intent(this, CineRatingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        }.onFailure { DiagLog.log(this, "fg-service: ${it.message}") }
        // Overlay buttons can't take D-pad focus — only show one where touch exists.
        // Remote-only TVs rely on auto-scan + RED/MENU/INFO keys instead.
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            overlayManager.showScanButton()
        } else {
            DiagLog.log(this, "no touchscreen — scan button hidden, auto-scan active")
        }
        mainHandler.postDelayed(rescanRunnable, 12_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!isSupportedApp(pkg)) {
            synchronized(ignoredPkgsLogged) {
                if (ignoredPkgsLogged.add(pkg)) DiagLog.log(this, "ignored pkg=$pkg")
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Left the streaming app (or never in one): drop stale badges so
                // they can't linger over the launcher, settings or our own UI.
                lastScreenKey = ""
                suppressGridUntil = 0L
                lastSupportedPkg = null
                overlayManager.clearAll()
            }
            return
        }
        lastSupportedPkg = pkg
        lastSupportedEventAt = SystemClock.uptimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Detail-page fast path (borrowed from Flutter's HotstarReader idea:
                // e.g. HSDetailPageActivity carries the title in event.text).
                // Falls through to grid scan when it's not a detail page.
                if (!handleDetailPage(event, pkg)) {
                    scheduleAutoScan(pkg)
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                handleFocus(event, pkg)
                scheduleAutoScan(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scheduleAutoScan(pkg)
            else -> Unit
        }
    }

    internal fun isSupportedApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p == packageName || p == "android") return false
        if (DENY_SUBSTRINGS.any { p.contains(it) }) return false
        if (EXACT_PACKAGES.contains(p)) return true
        return APP_KEYWORDS.any { p.contains(it) }
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

    /**
     * Detail-page fast path: activities with "detail" in the class name
     * (e.g. Hotstar's HSDetailPageActivity) carry the title in event.text.
     * Shows ONE badge and suppresses grid scans briefly. Returns true if handled.
     */
    private fun handleDetailPage(event: AccessibilityEvent, pkg: String): Boolean {
        val cls = event.className?.toString() ?: return false
        if (!cls.contains("detail", ignoreCase = true)) return false
        val raw = event.text?.joinToString(" ").orEmpty()
        val cleaned = cleanTitle(raw)
        if (cleaned.isNullOrBlank() || !isLikelyMovieTitle(cleaned)) return false

        val src = event.source
        val bounds = Rect()
        if (src != null) {
            src.getBoundsInScreen(bounds)
            src.recycle()
        }
        if (bounds.width() <= 10 || bounds.height() <= 10) {
            val m = resources.displayMetrics
            bounds.set(m.widthPixels / 2 - 200, 120, m.widthPixels / 2 + 200, 220)
        }

        lastScreenKey = "detail:$cleaned"
        suppressGridUntil = SystemClock.uptimeMillis() + 20_000L
        overlayManager.clearAll()
        DiagLog.log(this, "detail pkg=$pkg activity=$cls title=$cleaned")
        fetchAndShowOverlay(cleaned, bounds, pkg, tag = "detail")
        return true
    }

    /**
     * Focus-first: the D-pad-focused card is the most reliable title source on TV.
     * Walk self + ancestors for the first usable title; show ONE badge, no clearing.
     */
    private fun handleFocus(event: AccessibilityEvent, pkg: String) {
        val src = event.source ?: return
        try {
            val screenW = resources.displayMetrics.widthPixels
            var cur: AccessibilityNodeInfo? = src
            var depth = 0
            while (cur != null && depth < 6) {
                val raw = cur.text?.toString() ?: cur.contentDescription?.toString()
                val cleaned = cleanTitle(raw)
                if (!cleaned.isNullOrBlank() && isLikelyMovieTitle(cleaned)) {
                    val bounds = Rect()
                    // Anchor on the focused view itself (usually the poster card).
                    src.getBoundsInScreen(bounds)
                    if (bounds.width() > 10 && bounds.height() > 10 &&
                        !isFullWidthHeader(cur, bounds, screenW)
                    ) {
                        fetchAndShowOverlay(cleaned, bounds, pkg, tag = "focus", minQuality = MatchQuality.PREFIX)
                    }
                    break
                }
                val parent = cur.parent
                if (cur !== src) cur.recycle()
                cur = parent
                depth++
            }
            if (cur != null && cur !== src) cur.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "focus walk: ${e.message}")
        } finally {
            src.recycle()
        }
    }

    /** Full-width rows are section headers, not titles — unless poster-like. */
    private fun isFullWidthHeader(
        node: AccessibilityNodeInfo,
        rect: Rect,
        screenW: Int
    ): Boolean {
        if (rect.width() <= screenW * 0.85) return false
        val viewId = node.viewIdResourceName.orEmpty()
        return !(viewId.contains("poster", ignoreCase = true) ||
                viewId.contains("backdrop", ignoreCase = true) ||
                viewId.contains("thumbnail", ignoreCase = true) ||
                viewId.contains("card", ignoreCase = true) ||
                viewId.contains("image", ignoreCase = true))
    }

    private fun performFullScan(sourceApp: String, force: Boolean = false, quiet: Boolean = false) {
        if (!force && SystemClock.uptimeMillis() < suppressGridUntil) return // detail badge up
        val root = rootInActiveWindow ?: return
        try {
            lastScanAt = SystemClock.uptimeMillis()
            val candidates = ArrayList<Candidate>(24)
            val seen = HashSet<String>(24)
            val nodeCount = intArrayOf(0)
            val stats = ScanStats()
            collectCandidates(root, seen, candidates, nodeCount, stats)

            if (candidates.isEmpty()) {
                if (!quiet) DiagLog.log(
                    this,
                    "scan pkg=$sourceApp nodes=${nodeCount[0]} " +
                            "texts=${stats.texts} skipped=${stats.skipped} " +
                            "headers=${stats.headers} small=${stats.small} titles=0"
                )
                // Tree is blind here (Netflix: 7 nodes) — fall back to one OCR
                // snapshot instead of giving up.
                runOcrFallback(sourceApp, quiet)
                return
            }

            val screenKey = candidates.map { it.title }.sorted().joinToString("|")
            val now = SystemClock.uptimeMillis()
            // Same grid + badges still alive -> nothing to do (no network, no flicker).
            // Otherwise re-show (repository cache makes refresh network-free).
            if (!force && screenKey == lastScreenKey && now - lastBadgeShownAt < 15_000) return
            lastScreenKey = screenKey
            lastBadgeShownAt = now
            overlayManager.clearAll()

            val toFetch = candidates.take(MAX_TITLES_PER_SCAN)
            DiagLog.log(this, "scan pkg=$sourceApp nodes=${nodeCount[0]} titles=${candidates.size} fetch=${toFetch.size}")
            for (c in toFetch) {
                fetchAndShowOverlay(c.title, c.bounds, sourceApp, tag = "grid")
            }
        } finally {
            root.recycle()
        }
    }

    private data class Candidate(val title: String, val bounds: Rect)

    private fun ensureOcrBound(): Boolean {
        if (!OcrCaptureService.isEnabled(this)) return false
        if (!ocrBound) {
            ocrBound = runCatching {
                bindService(
                    Intent(this, OcrCaptureService::class.java),
                    ocrConn,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
        }
        return ocrBound
    }

    /**
     * OCR fallback: one snapshot + on-device text recognition when the tree
     * yields nothing. Same dedup discipline as grid scans; repository cache
     * keeps repeat screens network-free.
     */
    private fun runOcrFallback(sourceApp: String, quiet: Boolean) {
        if (!ensureOcrBound()) return
        val svc = ocrService
        if (svc == null || !OcrCaptureService.isActive) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val t0 = SystemClock.uptimeMillis()
                val bmp = svc.captureOnce()
                if (bmp == null) {
                    if (!quiet) DiagLog.log(this@CineRatingAccessibilityService, "ocr: no frame")
                    return@launch
                }
                val metrics = resources.displayMetrics
                val cands = OcrReader.readTitles(
                    this@CineRatingAccessibilityService,
                    bmp,
                    bmp.width.toFloat() / metrics.widthPixels.coerceAtLeast(1)
                )
                val ms = SystemClock.uptimeMillis() - t0
                runCatching { bmp.recycle() }
                withContext(Dispatchers.Main) {
                    DiagLog.log(
                        this@CineRatingAccessibilityService,
                        "ocr: capture ${ms}ms titles=${cands.size}"
                    )
                    if (cands.isEmpty()) return@withContext
                    val mapped = cands.map { Candidate(it.title, it.bounds) }
                    val key = "ocr:" + mapped.map { it.title }.sorted().joinToString("|")
                    val now = SystemClock.uptimeMillis()
                    if (key == lastScreenKey && now - lastBadgeShownAt < 15_000) return@withContext
                    lastScreenKey = key
                    lastBadgeShownAt = now
                    overlayManager.clearAll()
                    for (c in mapped.take(MAX_TITLES_PER_SCAN)) {
                        fetchAndShowOverlay(c.title, c.bounds, sourceApp, "ocr")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ocr fallback: ${e.message}")
            }
        }
    }

    /** Filter breakdown so Diagnostics shows WHERE titles get rejected. */
    private class ScanStats {
        var texts = 0
        var skipped = 0
        var headers = 0
        var small = 0
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        seen: MutableSet<String>,
        out: MutableList<Candidate>,
        nodeCount: IntArray,
        stats: ScanStats
    ) {
        if (out.size >= MAX_TITLES_PER_SCAN * 2) return
        nodeCount[0]++
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.density

        val inVerticalBand = rect.top >= screenH * 0.10 && rect.bottom <= screenH * 0.92
        if (inVerticalBand) {
            // Prefer contentDescription (posters) then text — Hotstar exposes posters this way.
            val raw = node.contentDescription?.toString() ?: node.text?.toString()
            val cleaned = cleanTitle(raw)
            if (!cleaned.isNullOrBlank()) {
                stats.texts++
                if (isLikelyMovieTitle(cleaned) && seen.add(cleaned)) {
                    val wDp = rect.width() / density
                    val hDp = rect.height() / density
                    val isMinSize = wDp > 60 && hDp > 30
                    val viewId = node.viewIdResourceName.orEmpty()
                    val isPosterId = viewId.contains("poster", ignoreCase = true) ||
                            viewId.contains("backdrop", ignoreCase = true) ||
                            viewId.contains("thumbnail", ignoreCase = true) ||
                            viewId.contains("card", ignoreCase = true) ||
                            viewId.contains("image", ignoreCase = true)
                    if (isFullWidthHeader(node, rect, screenW)) {
                        stats.headers++
                    } else if (rect.width() <= 0 || rect.height() <= 0) {
                        // Invisible node, ignore.
                    } else {
                        // Small poster labels ACCEPTED: the IMDb lookup itself is
                        // the junk filter (no catalog match -> no badge). The old
                        // 60x30dp floor killed ~10 real titles per Hotstar screen.
                        if (!isMinSize && !isPosterId && !node.isClickable) stats.small++
                        out.add(Candidate(cleaned, Rect(rect)))
                    }
                } else {
                    stats.skipped++
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCandidates(child, seen, out, nodeCount, stats)
            child.recycle()
        }
    }

    private fun fetchAndShowOverlay(
        title: String,
        rect: Rect,
        sourceApp: String,
        tag: String,
        minQuality: MatchQuality = MatchQuality.FALLBACK
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = ratingRepository.getRatings(title, sourceApp)
                if (result != null && result.imdbScore != "N/A" && result.imdbScore.isNotBlank()) {
                    // Focus badges demand EXACT-or-PREFIX: branded row names
                    // ("South Side Swag") fuzzy-match real movies via FALLBACK.
                    if (result.matchQuality.ordinal <= minQuality.ordinal) {
                        DiagLog.log(this@CineRatingAccessibilityService, "★ [$tag] $title = ${result.imdbScore}")
                        overlayManager.showMinimal(result, rect)
                    } else {
                        DiagLog.log(this@CineRatingAccessibilityService, "✕ [$tag] $title: low-confidence match, skipped")
                    }
                } else {
                    DiagLog.log(this@CineRatingAccessibilityService, "✕ [$tag] $title: no rating")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error for $title: ${e.message}")
                DiagLog.log(this@CineRatingAccessibilityService, "✕ [$tag] $title: ${e.message}")
            }
        }
    }

    private fun isLikelyMovieTitle(text: String): Boolean =
        TitleFilters.isLikelyMovieTitle(text)

    private fun cleanTitle(text: String?): String? =
        TitleFilters.cleanTitle(text)

    override fun onInterrupt() {}

    override fun onDestroy() {
        pendingScan?.cancel()
        mainHandler.removeCallbacks(rescanRunnable)
        if (ocrBound) {
            runCatching { unbindService(ocrConn) }
            ocrBound = false
            ocrService = null
        }
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
                    suppressGridUntil = 0L
                    performFullScan("manual", force = true)
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }
}

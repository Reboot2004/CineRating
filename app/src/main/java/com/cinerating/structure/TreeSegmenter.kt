package com.cinerating.structure

import com.cinerating.util.TitleFilters

/** One horizontal rail: structural header (never a title) + card titles. */
data class Row(val header: UiBox?, val cards: List<UiBox>)

data class Segments(val heroTitle: UiBox?, val rows: List<Row>)

/**
 * Structural segmentation of a streaming home screen.
 *
 * Instead of judging every text in isolation (the old whack-a-mole), find
 * ROWS geometrically: wide horizontal containers holding several children.
 * The header is structural (widest direct text child) and can never become
 * a candidate. Metadata rails (year/runtime/ratings/languages) are detected
 * positionally and excluded wholesale. Hero banner title handled separately.
 *
 * Pure logic over [UiBox] — fully unit-tested with synthetic trees.
 */
object TreeSegmenter {

    // Tokens that mark a horizontal strip as a metadata rail, not content.
    private val META_TOKENS = listOf(
        Regex("\\b(19|20)\\d{2}\\b"), // year
        Regex("u/a", RegexOption.IGNORE_CASE),
        Regex("\\d+\\s*h(\\s*\\d+\\s*m)?"), // 2h 46m
        Regex("\\b\\d+\\s*min\\b", RegexOption.IGNORE_CASE),
        Regex("languages?", RegexOption.IGNORE_CASE),
        Regex("\\bseasons?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bhdr\\b", RegexOption.IGNORE_CASE),
        Regex("\\b4k\\b", RegexOption.IGNORE_CASE),
        Regex("5\\.1"),
        Regex("dolby", RegexOption.IGNORE_CASE),
        Regex("\\blive\\b", RegexOption.IGNORE_CASE),
        Regex("episode", RegexOption.IGNORE_CASE)
    )

    fun segment(root: UiBox, screenW: Int, screenH: Int): Segments {
        val heroNode = findHero(root, screenW, screenH)
        val heroTitle = heroNode?.let { largestTitle(it, screenW, screenH, excludeMetadata = true) }
        val rows = ArrayList<Row>()
        collectRows(root, heroNode, screenW, screenH, rows)
        return Segments(heroTitle, rows)
    }

    /** Card + hero titles in screen order (hero first, rows top-down). */
    fun titles(seg: Segments): List<UiBox> {
        val out = ArrayList<UiBox>()
        seg.heroTitle?.let { out.add(it) }
        for (row in seg.rows) out.addAll(row.cards)
        return out
    }

    // ---- hero ----

    private fun findHero(node: UiBox, screenW: Int, screenH: Int): UiBox? {
        // Direct scan of top-level blocks: big banner near the top.
        val queue = ArrayDeque<UiBox>()
        queue.add(node)
        var depth = 0
        while (queue.isNotEmpty() && depth < 4) {
            val levelSize = queue.size
            repeat(levelSize) {
                val n = queue.removeFirst()
                if (n !== node &&
                    n.top < (screenH * 0.35) &&
                    n.height > (screenH * 0.22) &&
                    n.width > (screenW * 0.55)
                ) {
                    return n
                }
                queue.addAll(n.children)
            }
            depth++
        }
        return null
    }

    private fun largestTitle(
        scope: UiBox,
        screenW: Int,
        screenH: Int,
        excludeMetadata: Boolean
    ): UiBox? {
        var best: UiBox? = null
        var bestArea = 0
        // NOTE: the scope itself is never metadata-tested — a hero contains
        // its own metadata rail, which must not disqualify the title.
        fun walk(n: UiBox, inMeta: Boolean) {
            for (c in n.children) {
                val meta = inMeta || (excludeMetadata && isMetadataStrip(c))
                val raw = c.text ?: c.desc
                val cleaned = TitleFilters.cleanTitle(raw)
                if (!cleaned.isNullOrBlank() &&
                    !meta &&
                    TitleFilters.isLikelyMovieTitle(cleaned) &&
                    inBand(c, screenH)
                ) {
                    val area = c.width * c.height
                    if (area > bestArea) {
                        bestArea = area
                        best = c
                    }
                }
                walk(c, meta)
            }
        }
        walk(scope, false)
        return best
    }

    // ---- rows ----

    private fun collectRows(
        node: UiBox,
        heroNode: UiBox?,
        screenW: Int,
        screenH: Int,
        out: MutableList<Row>
    ) {
        if (node === heroNode) return // hero handled separately
        if (isRowContainer(node, screenW, screenH)) {
            parseRow(node, screenW, screenH)?.let { out.add(it) }
            return // don't descend: cards already collected
        }
        for (c in node.children) collectRows(c, heroNode, screenW, screenH, out)
    }

    private fun isRowContainer(n: UiBox, screenW: Int, screenH: Int): Boolean {
        if (n.width < screenW * 0.75) return false
        if (n.height < screenH * 0.06 || n.height > screenH * 0.70) return false
        if (n.textsBelow().size >= 3) return true
        // Image-only poster rail: one header text plus several image/clickable
        // cards carrying no labels. Text count alone would miss these entirely.
        if (n.textsBelow().size >= 1 && imageKids(n) >= 3) return true
        return false
    }

    private fun isImageLike(n: UiBox): Boolean {
        val id = n.viewId.orEmpty()
        if (id.contains("poster", ignoreCase = true) ||
            id.contains("backdrop", ignoreCase = true) ||
            id.contains("thumb", ignoreCase = true) ||
            id.contains("image", ignoreCase = true)
        ) return true
        val c = n.cls.orEmpty()
        return c.contains("ImageView") || c.endsWith(".Image")
    }

    private fun imageKids(n: UiBox): Int {
        var count = 0
        fun walk(box: UiBox) {
            for (c in box.children) {
                if (isImageLike(c) || c.clickable) count++
                walk(c)
            }
        }
        walk(n)
        return count
    }

    /**
     * One-line structural X-ray for Diagnostics: top-level child geometry.
     * Reveals the real rail layout when rows=0 (width x height @ top,
     * text count, first view-id fragment).
     */
    fun probe(root: UiBox): String {
        return root.children.take(8).joinToString(" | ") { c ->
            val idFrag = c.viewId?.substringAfterLast("/")?.take(14) ?: "-"
            "${c.width}x${c.height}@${c.top}t=${c.textsBelow().size}#$idFrag"
        }
    }

    private fun parseRow(n: UiBox, screenW: Int, screenH: Int): Row? {
        // A strip that is entirely metadata (year/runtime/genres row) is not
        // a content row at all — e.g. hero "2023 • U/A • Science Fiction…".
        if (isMetadataStrip(n)) return null
        // Header = widest direct text child (structural, never a candidate).
        var header: UiBox? = null
        var headerW = 0
        for (c in n.children) {
            val raw = c.text ?: c.desc ?: continue
            if (raw.isBlank()) continue
            if (c.width > headerW && c.width >= screenW * 0.20) {
                headerW = c.width
                header = c
            }
        }
        // Cards = text descendants excluding header subtree + metadata strips.
        // (Metadata is tested per-child: a row containing a metadata rail
        // must not disqualify the cards beside it.)
        val cards = ArrayList<UiBox>()
        fun walk(box: UiBox, inMeta: Boolean) {
            if (box === header) return
            for (c in box.children) {
                if (c === header) continue
                val meta = inMeta || isMetadataStrip(c)
                val raw = c.text ?: c.desc
                val cleaned = TitleFilters.cleanTitle(raw)
                if (!cleaned.isNullOrBlank() &&
                    !meta &&
                    TitleFilters.isLikelyMovieTitle(cleaned) &&
                    inBand(c, screenH)
                ) {
                    // Structural card test: modest box, not full-width.
                    if (c.width in 1 until (screenW * 0.85).toInt() &&
                        c.height > 0
                    ) {
                        cards.add(c)
                    }
                }
                walk(c, meta)
            }
        }
        walk(n, false)
        // Header with zero cards is still a rail (image-only posters):
        // callers trust "rows seen" to skip the legacy walk.
        if (header == null && cards.isEmpty()) return null
        return Row(header, cards)
    }

    private fun isMetadataStrip(n: UiBox): Boolean {
        val texts = n.textsBelow().mapNotNull { it.text ?: it.desc }
        if (texts.size < 2) return false
        val hits = texts.count { t -> META_TOKENS.any { it.containsMatchIn(t) } }
        return hits >= 2
    }

    private fun inBand(box: UiBox, screenH: Int): Boolean =
        box.top >= screenH * 0.10 && box.bottom <= screenH * 0.92
}

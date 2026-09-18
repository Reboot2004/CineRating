package com.cinerating.overlay

/**
 * Pure row state for the X-Ray panel: which titles are listed, in what
 * order, with what scores. No Android classes — unit-tested (XrayStateTest).
 */
class XrayState(private val maxRows: Int = MAX_ROWS) {

    data class Row(val title: String, var score: String?)

    private val rows = ArrayList<Row>()

    /** Rebuild the list for a new screen; scores arrive later via [setScore]. */
    fun setTitles(titles: List<String>) {
        rows.clear()
        titles.distinct().take(maxRows).forEach { rows.add(Row(it, null)) }
    }

    /** Fill in a score in place (no flicker); returns false if row unknown. */
    fun setScore(title: String, score: String): Boolean {
        val row = rows.firstOrNull { it.title == title } ?: return false
        row.score = score
        return true
    }

    /** Drop a row (e.g. lookup failed); returns false if row unknown. */
    fun remove(title: String): Boolean = rows.removeIf { it.title == title }

    /** Ensure a single title has a row (focus/detail path). */
    fun ensureRow(title: String) {
        if (rows.none { it.title == title }) {
            if (rows.size >= maxRows) rows.removeAt(rows.size - 1)
            rows.add(Row(title, null))
        }
    }

    fun snapshot(): List<Row> = rows.toList()

    fun isEmpty(): Boolean = rows.isEmpty()

    companion object {
        const val MAX_ROWS = 8
    }
}

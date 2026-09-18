package com.cinerating.structure

/**
 * Detached, framework-free snapshot of one accessibility node.
 * Plain ints (not android.graphics.Rect) so segmentation stays pure JVM
 * and unit-testable — no "not mocked" surprises.
 */
data class UiBox(
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val children: List<UiBox> = emptyList(),
    val cls: String? = null
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** All text-bearing descendants, depth-first, excluding this node itself. */
    fun textsBelow(): List<UiBox> {
        val out = ArrayList<UiBox>()
        fun walk(n: UiBox) {
            for (c in n.children) {
                if (!c.text.isNullOrBlank() || !c.desc.isNullOrBlank()) out.add(c)
                walk(c)
            }
        }
        walk(this)
        return out
    }
}

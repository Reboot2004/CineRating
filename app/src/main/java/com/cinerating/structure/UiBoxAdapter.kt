package com.cinerating.structure

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/**
 * One-way adapter: live tree -> detached [UiBox] tree.
 * Children are recycled as they are copied; the caller still owns (and must
 * recycle) the root — mirroring the existing collectCandidates discipline.
 */
object UiBoxAdapter {
    fun from(node: AccessibilityNodeInfo): UiBox {
        val r = Rect()
        node.getBoundsInScreen(r)
        val kids = ArrayList<UiBox>(node.childCount)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            kids.add(from(c))
            c.recycle()
        }
        return UiBox(
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            left = r.left,
            top = r.top,
            right = r.right,
            bottom = r.bottom,
            children = kids
        )
    }
}

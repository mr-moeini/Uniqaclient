package com.mrmoeini.smsforwarder

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** One element of the UI hierarchy, as reported by the Accessibility Service. */
class UiNode(
    val className: String,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val packageName: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val checked: Boolean,
    val checkable: Boolean,
    val password: Boolean,
    val editable: Boolean,
    val index: Int,
    val children: List<UiNode>
) {
    fun centerX() = bounds.centerX()
    fun centerY() = bounds.centerY()

    fun toJson(): JSONObject = JSONObject().apply {
        put("class", className)
        put("text", text)
        put("desc", contentDesc)
        put("resourceId", resourceId)
        put("package", packageName)
        put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("focused", focused)
        put("checked", checked)
        put("checkable", checkable)
        put("password", password)
        put("editable", editable)
        put("index", index)
        put("children", JSONArray().also { a -> children.forEach { a.put(it.toJson()) } })
    }

    /** Depth-first walk over this node and its descendants. */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.walk())
    }

    companion object {
        /** Snapshots an [AccessibilityNodeInfo] tree into plain data (the source nodes must be recycled by the caller). */
        fun from(info: AccessibilityNodeInfo, index: Int = 0, depth: Int = 0): UiNode {
            val r = Rect()
            info.getBoundsInScreen(r)
            val kids = mutableListOf<UiNode>()
            if (depth < 60) {
                for (i in 0 until info.childCount) {
                    val c = info.getChild(i) ?: continue
                    try {
                        kids.add(from(c, i, depth + 1))
                    } finally {
                        c.recycle()
                    }
                }
            }
            return UiNode(
                className = info.className?.toString() ?: "",
                text = info.text?.toString() ?: "",
                contentDesc = info.contentDescription?.toString() ?: "",
                resourceId = info.viewIdResourceName ?: "",
                packageName = info.packageName?.toString() ?: "",
                bounds = r,
                clickable = info.isClickable,
                longClickable = info.isLongClickable,
                scrollable = info.isScrollable,
                enabled = info.isEnabled,
                focused = info.isFocused,
                checked = info.isChecked,
                checkable = info.isCheckable,
                password = info.isPassword,
                editable = info.isEditable,
                index = index,
                children = kids
            )
        }
    }
}

/** Finds the first node matching a selector (resourceId / text / desc / className), by priority, exact or contains. */
class Selector(
    val resourceId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val contains: Boolean = true,
    val index: Int = 0
) {
    fun matches(n: UiNode): Boolean {
        fun eq(value: String, query: String) =
            if (contains) value.contains(query, ignoreCase = true) else value.equals(query, ignoreCase = true)
        if (resourceId != null && !eq(n.resourceId, resourceId)) return false
        if (text != null && !eq(n.text, text)) return false
        if (desc != null && !eq(n.contentDesc, desc)) return false
        if (className != null && !eq(n.className, className)) return false
        return resourceId != null || text != null || desc != null || className != null
    }

    fun find(root: UiNode): UiNode? {
        val all = root.walk().filter { matches(it) }.toList()
        return all.getOrNull(index)
    }

    companion object {
        fun fromJson(o: JSONObject): Selector = Selector(
            resourceId = o.optString("resourceId").ifBlank { null },
            text = o.optString("text").ifBlank { null },
            desc = o.optString("desc").ifBlank { null },
            className = o.optString("class").ifBlank { null },
            contains = o.optBoolean("contains", true),
            index = o.optInt("index", 0)
        )
    }
}

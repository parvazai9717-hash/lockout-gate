package com.lockout.gate

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Personal preview only: own Accessibility page and own removal UI. */
object PersonalAccessibilityGuard {
    fun shouldReturnHome(event: AccessibilityEvent, appLabel: String, description: String,
                         readRoot: () -> AccessibilityNodeInfo?): Boolean {
        // Both code and service metadata disable content inspection in release.
        val pkg = event.packageName?.toString() ?: return false
        if (!BuildConfig.DEBUG || !OwnRemovalPage.canInspect(pkg)) return false
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false
        val root = readRoot() ?: return false
        if (root.packageName?.toString() != pkg) {
            @Suppress("DEPRECATION")
            root.recycle()
            return false
        }
        var count = 0
        var overflow = false
        @Suppress("DEPRECATION")
        fun snapshot(node: AccessibilityNodeInfo, depth: Int): SettingsNode {
            if (++count > OwnAccessibilityPage.MAX_NODES || depth > OwnAccessibilityPage.MAX_DEPTH) {
                overflow = true
                return SettingsNode()
            }
            val children = mutableListOf<SettingsNode>()
            for (i in 0 until node.childCount) {
                if (overflow) break
                val child = node.getChild(i) ?: continue
                try { children += snapshot(child, depth + 1) } finally { child.recycle() }
            }
            return SettingsNode(
                text = node.text?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                heading = Build.VERSION.SDK_INT >= 28 && node.isHeading,
                checked = node.isCheckable && node.isChecked,
                children = children,
            )
        }
        @Suppress("DEPRECATION")
        val tree = try { snapshot(root, 0) } finally { root.recycle() }
        if (overflow) return false
        val titles = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.text.map { it.toString() } else emptyList()
        val windowClass = event.className?.toString().orEmpty()
        return (pkg == "com.android.settings" && OwnAccessibilityPage.shouldReturnHome(appLabel, description, windowClass, titles, tree)) ||
            OwnRemovalPage.shouldReturnHome(pkg, appLabel, windowClass, tree)
    }
}

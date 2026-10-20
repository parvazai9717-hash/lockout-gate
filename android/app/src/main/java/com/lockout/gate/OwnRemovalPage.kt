package com.lockout.gate

/** Identifies our own removal UI without matching another app or an app-list row. */
object OwnRemovalPage {
    private val installerPackages = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")
    private val whitespace = Regex("\\s+")
    fun canInspect(packageName: String) = packageName == "com.android.settings" ||
        packageName in installerPackages || packageName == "com.android.vending"

    fun shouldReturnHome(packageName: String, appLabel: String, windowClass: String, root: SettingsNode): Boolean {
        if (!canInspect(packageName) || appLabel.isBlank()) return false
        fun String.normalized() = trim().replace(whitespace, " ")
        val name = appLabel.normalized()
        val ownQuestion = Regex("""(?i)^(?:Do you want to uninstall|Uninstall|Delete)\s+["']?${Regex.escape(name)}["']?[?.!]?$""")
        var hasOwnLabel = false
        var ownAppInfoHeader = false
        var ownAlertTitle = false
        var removalAction = false
        var removalQuestion = false
        var namedRemovalQuestion = false
        var count = 0
        var overflow = false
        fun visit(node: SettingsNode, depth: Int) {
            if (++count > OwnAccessibilityPage.MAX_NODES || depth > OwnAccessibilityPage.MAX_DEPTH) {
                overflow = true
                return
            }
            val text = node.text.normalized()
            if (text.equals(name, ignoreCase = true)) {
                hasOwnLabel = true
                // Samsung's observed App info identity, not android:id/title in an app list.
                if (node.viewId.substringAfterLast('/') == "entity_header_title") ownAppInfoHeader = true
                if (node.viewId.substringAfterLast('/') == "alertTitle") ownAlertTitle = true
            }
            if (text.equals("Uninstall", ignoreCase = true) || text.equals("Delete app", ignoreCase = true)) removalAction = true
            val question = text.startsWith("Do you want to uninstall", ignoreCase = true) ||
                text.startsWith("Uninstall ", ignoreCase = true) ||
                text.startsWith("Delete this app", ignoreCase = true)
            if (question) {
                removalQuestion = true
                if (ownQuestion.matches(text)) namedRemovalQuestion = true
            }
            node.children.forEach { if (!overflow) visit(it, depth + 1) }
        }
        visit(root, 0)
        if (overflow) return false
        return when (packageName) {
            "com.android.settings" -> (ownAppInfoHeader && removalAction) ||
                (windowClass.contains("Dialog", ignoreCase = true) && removalQuestion && (ownAlertTitle || namedRemovalQuestion))
            in installerPackages -> hasOwnLabel && (windowClass.contains("Uninstall", ignoreCase = true) || removalQuestion || removalAction)
            // Never treat a visible app-list row as the target of a Play Store removal.
            "com.android.vending" -> windowClass.contains("Dialog", ignoreCase = true) && namedRemovalQuestion
            else -> false
        }
    }
}

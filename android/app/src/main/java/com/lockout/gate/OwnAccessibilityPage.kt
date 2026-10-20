package com.lockout.gate

/** Labels are used only in the personal preview's Android Settings page guard. */
data class SettingsNode(
    val text: String = "",
    val className: String = "",
    val viewId: String = "",
    val heading: Boolean = false,
    val checked: Boolean = false,
    val children: List<SettingsNode> = emptyList(),
)

object OwnAccessibilityPage {
    internal const val MAX_NODES = 250
    // Samsung's observed service detail hierarchy is 21 nodes deep.
    internal const val MAX_DEPTH = 32
    private val whitespace = Regex("\\s+")
    private val titleIds = setOf("action_bar_title", "toolbar_title", "collapsing_toolbar_title", "title")
    private val toolbarIds = setOf("action_bar", "toolbar")
    fun shouldReturnHome(
        appLabel: String,
        serviceDescription: String,
        windowClass: String,
        windowTitles: List<String>,
        root: SettingsNode,
    ): Boolean {
        fun String.normalized() = trim().replace(whitespace, " ")
        val name = appLabel.normalized()
        if (name.isEmpty()) return false
        val descriptionPrefix = serviceDescription.normalized().take(64)
        val knownDetailsClass = windowClass.contains("AccessibilityDetails", ignoreCase = true) ||
            windowClass.contains("ToggleAccessibilityService", ignoreCase = true)
        var ownHeading = knownDetailsClass && windowTitles.any { it.normalized().equals(name, ignoreCase = true) }
        var enabledSwitch = false
        val toolbarTitles = mutableSetOf<String>()
        var serviceDetail = knownDetailsClass
        var visited = 0
        var overflow = false

        fun visit(node: SettingsNode, inList: Boolean, inToolbar: Boolean, depth: Int) {
            if (++visited > MAX_NODES || depth > MAX_DEPTH) { overflow = true; return }
            val list = inList || node.className.contains("RecyclerView") || node.className.contains("ListView")
            // Samsung exposes its action bar as android.view.ViewGroup, with
            // an unlabelled TextView child rather than a Toolbar/title view ID.
            val toolbar = inToolbar || node.className.contains("Toolbar", ignoreCase = true) ||
                node.viewId.substringAfterLast('/') in toolbarIds
            val text = node.text.normalized()
            if (!list && toolbar && text.isNotEmpty()) toolbarTitles += text
            val titleId = node.viewId.substringAfterLast('/') in titleIds
            if (!list && (node.heading || toolbar || titleId) && text.equals(name, ignoreCase = true)) ownHeading = true
            if (node.checked && node.className.contains("Switch", ignoreCase = true)) enabledSwitch = true
            if (text.equals("Use $name", ignoreCase = true) ||
                text.equals("Use service", ignoreCase = true) ||
                text.equals("Accessibility shortcut", ignoreCase = true) ||
                (descriptionPrefix.length >= 24 && text.contains(descriptionPrefix, ignoreCase = true))) {
                serviceDetail = true
            }
            node.children.forEach { if (!overflow) visit(it, list, toolbar, depth + 1) }
        }
        visit(root, false, false, 0)
        // An app name in an Installed Apps row is not the page title. If unsure,
        // leave Settings alone rather than redirecting a different app's controls.
        val differentToolbar = toolbarTitles.isNotEmpty() && toolbarTitles.none { it.equals(name, ignoreCase = true) }
        return !overflow && !differentToolbar && ownHeading && enabledSwitch && serviceDetail
    }
}

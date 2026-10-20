package com.lockout.gate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class OwnAccessibilityPageTest {
    private val own = "Lockout Gate Preview"
    private val description = "Personal preview: detects foreground apps to block your selections."
    private fun title(label: String) = SettingsNode(children = listOf(SettingsNode(text = label)), className = "android.widget.Toolbar")
    private fun toggle(on: Boolean = true) = SettingsNode(className = "android.widget.Switch", checked = on)
    private fun page(vararg nodes: SettingsNode) = SettingsNode(children = nodes.toList())
    private fun matches(root: SettingsNode, className: String = "com.android.settings.SubSettings", titles: List<String> = emptyList()) =
        OwnAccessibilityPage.shouldReturnHome(own, description, className, titles, root)

    @Test fun ownServicePageReturnsHome() {
        assertTrue(matches(page(title(own), toggle(), SettingsNode(text = "Use service"))))
    }

    @Test fun samsungGenericPageUsesOurExactHeadingAndDescription() {
        assertTrue(matches(page(SettingsNode(text = own, viewId = "com.android.settings:id/collapsing_toolbar_title"),
            toggle(), SettingsNode(text = description))))
    }

    @Test fun wisprFlowPageIsNotBlockedEvenIfOurNameAppearsInBody() {
        assertFalse(matches(page(title("Wispr Flow"), toggle(), SettingsNode(text = "Use service"), SettingsNode(text = own))))
    }

    @Test fun wisprToolbarWinsOverAnOutdatedWindowTitle() {
        assertFalse(matches(page(title("Wispr Flow"), toggle()), "Settings\$AccessibilityDetailsSettingsActivity", listOf(own)))
    }

    @Test fun installedAppsListIsNotOurDetailPage() {
        val list = SettingsNode(className = "androidx.recyclerview.widget.RecyclerView", children = listOf(
            SettingsNode(text = own, heading = true, viewId = "com.android.settings:id/title"), toggle(),
            SettingsNode(text = "Wispr Flow"), toggle(),
        ))
        assertFalse(matches(page(title("Installed apps"), list), "InstalledAccessibilityServices", listOf(own)))
    }

    @Test fun ownNotificationSettingsStayOpen() {
        assertFalse(matches(page(title(own), toggle(), SettingsNode(text = "Allow notifications"))))
    }

    @Test fun disabledServiceCanBeEnabledNormally() {
        assertFalse(matches(page(title(own), toggle(false), SettingsNode(text = "Use service"))))
    }

    @Test fun initialPermissionDialogIsNotBlocked() {
        assertFalse(matches(page(SettingsNode(text = "Allow $own full control of your device?"), SettingsNode(text = "Allow"))))
    }

    @Test fun anotherLockoutInstallIsNotOurPage() {
        assertFalse(matches(page(title("Lockout Gate"), toggle(), SettingsNode(text = "Use service"))))
    }

    @Test fun knownDetailsActivityCanUseItsWindowTitle() {
        assertTrue(matches(page(toggle()), "Settings\$AccessibilityDetailsSettingsActivity", listOf(own)))
    }

    @Test fun nameSubstringIsNotEnough() {
        assertFalse(matches(page(title("$own Helper"), toggle(), SettingsNode(text = "Use service"))))
    }

    @Test fun oversizedPageIsLeftAlone() {
        val many = List(260) { SettingsNode(text = "Setting $it") }
        assertFalse(matches(page(title(own), toggle(), SettingsNode(text = "Use service"), SettingsNode(children = many))))
    }

    private fun observedSamsungPage(): SettingsNode {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = checkNotNull(javaClass.getResourceAsStream("/samsung_accessibility_detail.xml")).use {
            factory.newDocumentBuilder().parse(it)
        }
        fun read(element: Element): SettingsNode {
            val children = (0 until element.childNodes.length).mapNotNull {
                (element.childNodes.item(it) as? Element)?.let { child -> read(child) }
            }
            return SettingsNode(text = element.getAttribute("text"), className = element.getAttribute("class"),
                viewId = element.getAttribute("resource-id"), checked = element.getAttribute("checkable") == "true" &&
                    element.getAttribute("checked") == "true", children = children)
        }
        return read(document.documentElement)
    }

    @Test fun actualSamsungHierarchyRecognizesOurPage() {
        assertTrue(matches(observedSamsungPage()))
    }

    @Test fun samsungHierarchyWithWisprHeadingIsNotOurPage() {
        fun otherApp(node: SettingsNode): SettingsNode = node.copy(
            text = node.text.replace(own, "Wispr Flow"), children = node.children.map { otherApp(it) },
        )
        assertFalse(matches(otherApp(observedSamsungPage())))
    }

    @Test fun unexpectedlyDeepHierarchyIsLeftAlone() {
        var tree = page(title(own), toggle(), SettingsNode(text = "Use service"))
        repeat(OwnAccessibilityPage.MAX_DEPTH + 1) { tree = page(tree) }
        assertFalse(matches(tree))
    }
}

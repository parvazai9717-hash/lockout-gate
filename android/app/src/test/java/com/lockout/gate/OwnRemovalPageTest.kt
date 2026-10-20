package com.lockout.gate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class OwnRemovalPageTest {
    private val own = "Lockout Gate Preview"
    private fun page(vararg nodes: SettingsNode) = SettingsNode(children = nodes.toList())
    private fun label(text: String) = SettingsNode(text = text)
    private fun matches(root: SettingsNode, pkg: String = "com.android.settings", cls: String = "InstalledAppDetails") =
        OwnRemovalPage.shouldReturnHome(pkg, own, cls, root)

    private fun samsungPage(): SettingsNode {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = checkNotNull(javaClass.getResourceAsStream("/samsung_app_info.xml")).use {
            factory.newDocumentBuilder().parse(it)
        }
        fun read(element: Element): SettingsNode = SettingsNode(
            text = element.getAttribute("text"), className = element.getAttribute("class"),
            viewId = element.getAttribute("resource-id"), children = (0 until element.childNodes.length).mapNotNull {
                (element.childNodes.item(it) as? Element)?.let { child -> read(child) }
            },
        )
        return read(document.documentElement)
    }

    @Test fun observedSamsungAppInfoIsProtected() { assertTrue(matches(samsungPage())) }

    @Test fun wisprAppInfoWithSameSamsungLayoutIsNotProtected() {
        fun other(node: SettingsNode): SettingsNode = node.copy(text = node.text.replace(own, "Wispr Flow"),
            children = node.children.map { other(it) })
        assertFalse(matches(other(samsungPage())))
    }

    @Test fun installedAppsRowIsNotAnAppInfoIdentity() {
        assertFalse(matches(page(SettingsNode(className = "RecyclerView", children = listOf(
            SettingsNode(text = own, viewId = "android:id/title"))), label("Uninstall"))))
    }

    @Test fun anotherAppCanMentionOurNameInItsBody() {
        assertFalse(matches(page(SettingsNode(text = "Wispr Flow", viewId = "com.android.settings:id/entity_header_title"),
            label(own), label("Uninstall"))))
    }

    @Test fun systemUninstallConfirmationForUsIsProtected() {
        assertTrue(matches(page(label(own), label("Do you want to uninstall this app?"), label("OK")),
            "com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"))
    }

    @Test fun systemUninstallConfirmationForAnotherAppStaysOpen() {
        assertFalse(matches(page(label("Wispr Flow"), label("Do you want to uninstall this app?"), label("OK")),
            "com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"))
    }

    @Test fun installingOrUpdatingOurAppIsNotRemoval() {
        assertFalse(matches(page(label(own), label("Do you want to install this app?"), label("Install")),
            "com.google.android.packageinstaller", "InstallActivity"))
    }

    @Test fun explicitPlayStoreRemovalQuestionForUsIsProtected() {
        assertTrue(matches(page(label("Do you want to uninstall $own?")), "com.android.vending", "android.app.Dialog"))
    }

    @Test fun anotherPlayStoreRemovalWithOurNameInBackgroundIsNotProtected() {
        assertFalse(matches(page(label(own), label("Do you want to uninstall Wispr Flow?")),
            "com.android.vending", "android.app.Dialog"))
    }

    @Test fun similarLongerNameIsNotOurApp() {
        assertFalse(matches(page(label("Do you want to uninstall $own Helper?")), "com.android.vending", "android.app.Dialog"))
    }

    @Test fun playStoreAppListIsNotARemovalConfirmation() {
        assertFalse(matches(page(label(own), label("Uninstall")), "com.android.vending", "ManageAppsActivity"))
    }

    @Test fun unrelatedPackageIsNeverInspectedAsARemovalPage() {
        assertFalse(matches(page(label(own), label("Uninstall")), "com.wispr.flowapp", "Dialog"))
    }

    @Test fun ownSettingsRemovalDialogIsProtected() {
        assertTrue(matches(page(SettingsNode(text = own, viewId = "android:id/alertTitle"),
            label("Do you want to uninstall this app?")), cls = "android.app.AlertDialog"))
    }

    @Test fun permissionSetupDialogIsNotRemoval() {
        assertFalse(matches(page(SettingsNode(text = own, viewId = "android:id/alertTitle"),
            label("Allow full control of your device?")), cls = "android.app.AlertDialog"))
    }
}

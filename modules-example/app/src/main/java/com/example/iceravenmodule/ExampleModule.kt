package com.example.iceravenmodule

import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleHost
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModulePermission
import org.mozilla.fenix.modules.api.ModuleSetting
import org.mozilla.fenix.modules.api.ModuleThemeScope
import org.mozilla.fenix.modules.api.ModuleToolbarAction
import org.mozilla.fenix.modules.api.ModuleColorSlot

/**
 * Example module touching every extension point: a menu entry, a toolbar button, a settings row and a theme override.
 *
 * Note how the API is used: no resource ids from this module's `R` class, only [ModuleHost.resources], and only the
 * capabilities declared in [permissions].
 */
class ExampleModule : BrowserModule {

    override val id = "com.example.iceravenmodule"

    override val name = "Example module"

    override val version = 1

    override val description = "Menu entry, toolbar button, a setting and a green theme."

    override val permissions = setOf(
        ModulePermission.MENU_ITEMS,
        ModulePermission.PREFERENCES_READ,
        ModulePermission.TOOLBAR,
        ModulePermission.SETTINGS,
        ModulePermission.THEME,
    )

    private var showToolbarButton = true
    private var clicks = 0

    override fun onEnable(host: ModuleHost) {
        showToolbarButton = host.getBooleanPref(PREF_TOOLBAR_BUTTON, true)
        clicks = host.getBooleanPref(PREF_CLICKED, false).let { if (it) 1 else 0 }

        host.addMenuItem(
            ModuleMenuItem(
                id = "example-action",
                label = "Example module (opened $clicks times)",
                onClick = {
                    clicks++
                    host.log("menu entry tapped, clicks=$clicks")
                },
            ),
        )

        host.addToolbarAction(
            ModuleToolbarAction(
                id = "example-toolbar-button",
                label = "Example module",
                visible = showToolbarButton,
                onClick = {
                    host.log("toolbar button tapped")
                    host.setPref(PREF_CLICKED, true)
                },
            ),
        )

        host.addSetting(
            ModuleSetting.Switch(
                key = "toolbar-button",
                title = "Show toolbar button",
                summary = "Adds a button to the left side of the toolbar.",
                checked = showToolbarButton,
                onCheckedChange = { checked ->
                    showToolbarButton = checked
                    host.setPref(PREF_TOOLBAR_BUTTON, checked)
                    // Republish so the toolbar and the settings row both pick up the new value.
                    host.addToolbarAction(
                        ModuleToolbarAction(
                            id = "example-toolbar-button",
                            label = "Example module",
                            visible = checked,
                            onClick = {
                                host.log("toolbar button tapped")
                                host.setPref(PREF_CLICKED, true)
                            },
                        ),
                    )
                    publishSettings(host)
                },
            ),
        )

        // Colors are plain ARGB ints, no Compose types involved.
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.PRIMARY, GREEN)
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.PRIMARY_CONTAINER, DARK_GREEN)
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.ON_PRIMARY_CONTAINER, WHITE)
        host.setThemeColor(ModuleThemeScope.PRIVATE, ModuleColorSlot.PRIMARY, RED)
    }

    override fun onDisable(host: ModuleHost) {
        host.removeMenuItems()
        host.removeToolbarActions()
        host.removeSettings()
        host.clearThemeColors()
    }

    private fun publishSettings(host: ModuleHost) {
        host.addSetting(
            ModuleSetting.Switch(
                key = "toolbar-button",
                title = "Show toolbar button",
                summary = "Adds a button to the left side of the toolbar.",
                checked = showToolbarButton,
                onCheckedChange = { checked ->
                    showToolbarButton = checked
                    host.setPref(PREF_TOOLBAR_BUTTON, checked)
                    publishSettings(host)
                },
            ),
        )
    }

    private companion object {
        const val PREF_CLICKED = "example_module_clicked"
        const val PREF_TOOLBAR_BUTTON = "example_module_toolbar_button"

        // Hex literals above Int.MAX_VALUE are Long in Kotlin, hence the explicit narrowing.
        val GREEN = 0xFF00AA00.toInt()
        val DARK_GREEN = 0xFF003300.toInt()
        val RED = 0xFFFF5555.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
package com.example.iceravenmodule

import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleHost
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModulePermission
import org.mozilla.fenix.modules.api.ModuleThemeScope

/**
 * Example module: adds an entry to the browser menu, recolours the browser and remembers whether the user opened it.
 *
 * Note how the API is used: no resource ids from this module's `R` class, only [ModuleHost.resources], and only the
 * capabilities declared in [permissions]. Colours are plain ARGB integers, so a module needs no Compose dependency.
 */
class ExampleModule : BrowserModule {

    override val id = "com.example.iceravenmodule"

    override val name = "Example module"

    override val version = 1

    override val description = "Adds a menu entry and a green accent colour."

    override val permissions = setOf(
        ModulePermission.MENU_ITEMS,
        ModulePermission.PREFERENCES_READ,
        ModulePermission.THEME,
    )

    override fun onEnable(host: ModuleHost) {
        val opened = host.getBooleanPref(PREF_LAST_OPENED, false)

        host.addMenuItem(
            ModuleMenuItem(
                id = "example-action",
                label = if (opened) "Example module (used)" else "Example module",
                onClick = {
                    host.setPref(PREF_LAST_OPENED, true)
                    host.log("menu entry tapped")
                },
            ),
        )

        // Theme: every slot left alone keeps the value the browser shipped with.
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.PRIMARY, GREEN.toInt())
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.PRIMARY_CONTAINER, DARK_GREEN.toInt())
        host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.ON_PRIMARY_CONTAINER, WHITE.toInt())

        // Private browsing gets its own accent.
        host.setThemeColor(ModuleThemeScope.PRIVATE, ModuleColorSlot.PRIMARY, RED.toInt())
    }

    override fun onDisable(host: ModuleHost) {
        host.removeMenuItems()
        host.clearThemeColors()
    }

    private companion object {
        const val PREF_LAST_OPENED = "example_module_opened"

        val GREEN = 0xFF00AA00
        val DARK_GREEN = 0xFF003300
        val RED = 0xFFFF5555
        val WHITE = 0xFFFFFFFF
    }
}
package com.example.geckomod

import de.robv.android.xposed.IXposedHookLoadPackage

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleHost
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModulePermission
import org.mozilla.fenix.modules.api.ModuleSetting
import org.mozilla.fenix.modules.api.ModuleThemeScope
import org.mozilla.fenix.modules.api.ModuleToolbarAction

/**
 * Example GeckoMod: shows how a module attaches to the browser itself.
 *
 * Name, description, version, author, update URL and permissions come from the archive manifest, so the values below
 * are only the fallback used when a GeckoMod ships without that metadata. The switch in the module manager comes from
 * [ModuleSetting], the menu entry and toolbar button from [ModuleMenuItem] and [ModuleToolbarAction], and the colours
 * from [ModuleThemeScope].
 */
class ExampleModule : BrowserModule, IXposedHookLoadPackage {

    override val id = "com.example.geckomod"

    override val name = "Example GeckoMod"

    override val version = 1

    override val description = "Adds a menu entry, a toolbar button, a setting and a colour theme."

    override val permissions = setOf(
        ModulePermission.MENU_ITEMS,
        ModulePermission.PREFERENCES_READ,
        ModulePermission.TOOLBAR,
        ModulePermission.SETTINGS,
        ModulePermission.THEME,
    )

    private var buttonVisible = true

    private var host: ModuleHost? = null

    override fun onEnable(host: ModuleHost) {
        this.host = host
        buttonVisible = host.getBooleanPref(PREF_BUTTON_VISIBLE, true)

        host.addMenuItem(
            ModuleMenuItem(
                id = "example-menu",
                label = "Example GeckoMod",
                onClick = { showAlert(host, "Example GeckoMod is alive") },
            ),
        )

        publishToolbarAction(host)
        publishSetting(host)

        showAlert(host, "Example GeckoMod loaded")

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

    private fun publishToolbarAction(host: ModuleHost) {
        host.addToolbarAction(
            ModuleToolbarAction(
                id = "example-toolbar",
                label = "Example",
                visible = buttonVisible,
                onClick = { host.log("toolbar button tapped") },
            ),
        )
    }

    private fun publishSetting(host: ModuleHost) {
        host.addSetting(
            ModuleSetting.Switch(
                key = "show-toolbar-button",
                title = "Show toolbar button",
                summary = "Adds a button to the browser toolbar while this GeckoMod is enabled",
                checked = buttonVisible,
            ) { checked ->
                buttonVisible = checked
                publishToolbarAction(host)
                publishSetting(host)
            },
        )
    }

    private fun showAlert(host: ModuleHost, message: String) {
        // onEnable runs on the loader thread, and a dialog needs a main thread window.
        Handler(Looper.getMainLooper()).post {
            runCatching {
                AlertDialog.Builder(host.context)
                    .setTitle(name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure {
                // Application context without a window: fall back to something that always works.
                Toast.makeText(host.context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Hex literals above Int.MAX_VALUE are Longs, so they can not be const val.
    private val GREEN = 0xFF00AA00.toInt()
    private val DARK_GREEN = 0xFF003300.toInt()
    private val RED = 0xFFFF5555.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Called by GeckoLoader through [ModuleXposedBridge] when the browser runs under LSPosed or another Xposed
     * implementation. Without it the same code would be reached with [XposedHelpers] and friends; here the point is to
     * show the entry point being handed to the framework.
     */
    override fun handleLoadPackage(param: IXposedHookLoadPackage.LoadPackageParam) {
        host?.log("Xposed hook for ${param.packageName} in process ${param.processName}")
        showAlert(host ?: return, "Xposed hooks are live")
    }

    private companion object {
        const val PREF_BUTTON_VISIBLE = "geckomod_example_button_visible"
    }
}
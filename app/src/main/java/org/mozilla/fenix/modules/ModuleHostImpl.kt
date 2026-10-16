/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log
import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleHost
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModulePermission
import org.mozilla.fenix.modules.api.ModuleSetting
import org.mozilla.fenix.modules.api.ModuleThemeScope
import org.mozilla.fenix.modules.api.ModuleToolbarAction
import org.mozilla.fenix.utils.Settings

/**
 * Browser side implementation of [ModuleHost] handed to a single loaded module.
 *
 * Every method checks the permissions declared in the module's manifest first, so a module can not do more than what
 * the user agreed to install, even if the archive was modified afterwards. All mutable state is guarded by [lock]
 * because modules are enabled on the loader thread while menu items are read on the main thread.
 */
internal class ModuleHostImpl(
    override val context: Context,
    private val module: BrowserModule,
    override val resources: Resources,
    private val granted: Set<ModulePermission>,
    browserSettings: Settings,
    private val onChanged: () -> Unit,
) : ModuleHost {

    private val lock = Any()
    private val items = mutableListOf<ModuleMenuItem>()
    private val toolbar = mutableListOf<ModuleToolbarAction>()
    private val settings = mutableListOf<ModuleSetting>()
    private val themeOverrides = mutableMapOf<ModuleThemeScope, MutableMap<ModuleColorSlot, Int>>()
    private val browserPreferences: SharedPreferences = browserSettings.preferences

    /** Menu items currently contributed by this module. */
    val menuItems: List<ModuleMenuItem>
        get() = synchronized(lock) { items.toList() }

    /** Toolbar buttons currently contributed by this module. */
    val toolbarActions: List<ModuleToolbarAction>
        get() = synchronized(lock) { toolbar.toList() }

    /** Settings rows currently contributed by this module. */
    val moduleSettings: List<ModuleSetting>
        get() = synchronized(lock) { settings.toList() }

    /** Colour overrides currently registered by this module, as `scope -> slot -> ARGB`. */
    val colors: Map<ModuleThemeScope, Map<ModuleColorSlot, Int>>
        get() = synchronized(lock) { themeOverrides.mapValues { it.value.toMap() } }

    override fun addMenuItem(item: ModuleMenuItem) {
        require(ModulePermission.MENU_ITEMS in granted) {
            "Module ${module.id} did not declare ${ModulePermission.MENU_ITEMS}"
        }
        synchronized(lock) {
            items.removeAll { it.id == item.id }
            items.add(item)
        }
        onChanged()
    }

    override fun removeMenuItems() {
        synchronized(lock) {
            if (items.isEmpty()) return
            items.clear()
        }
        onChanged()
    }

    override fun addToolbarAction(action: ModuleToolbarAction) {
        require(ModulePermission.TOOLBAR in granted) {
            "Module ${module.id} did not declare ${ModulePermission.TOOLBAR}"
        }
        synchronized(lock) {
            toolbar.removeAll { it.id == action.id }
            toolbar.add(action)
        }
        onChanged()
    }

    override fun removeToolbarActions() {
        synchronized(lock) {
            if (toolbar.isEmpty()) return
            toolbar.clear()
        }
        onChanged()
    }

    override fun addSetting(setting: ModuleSetting) {
        require(ModulePermission.SETTINGS in granted) {
            "Module ${module.id} did not declare ${ModulePermission.SETTINGS}"
        }
        synchronized(lock) {
            settings.removeAll { it.key == setting.key }
            settings.add(setting)
        }
        onChanged()
    }

    override fun removeSettings() {
        synchronized(lock) {
            if (settings.isEmpty()) return
            settings.clear()
        }
        onChanged()
    }

    override fun getBooleanPref(key: String, default: Boolean): Boolean {
        requireReadAccess()
        return browserPreferences.getBoolean(key, default)
    }

    override fun getStringPref(key: String, default: String): String {
        requireReadAccess()
        return browserPreferences.getString(key, default) ?: default
    }

    override fun setPref(key: String, value: Any) {
        require(ModulePermission.PREFERENCES_WRITE in granted) {
            "Module ${module.id} did not declare ${ModulePermission.PREFERENCES_WRITE}"
        }
        val editor = browserPreferences.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            else -> error("Unsupported preference type ${value::class.java.name}")
        }
        editor.apply()
    }

    override fun log(message: String) {
        Log.i(LOG_TAG, "[${module.id}] $message")
    }

    override fun setThemeColor(scope: ModuleThemeScope, slot: ModuleColorSlot, argb: Int) {
        require(ModulePermission.THEME in granted) {
            "Module ${module.id} did not declare ${ModulePermission.THEME}"
        }
        synchronized(lock) {
            themeOverrides.getOrPut(scope) { mutableMapOf() }[slot] = argb
        }
        onChanged()
    }

    override fun clearThemeColors() {
        synchronized(lock) {
            if (themeOverrides.isEmpty()) return
            themeOverrides.clear()
        }
        onChanged()
    }

    /** Called when the module is disabled or uninstalled. Clears state and notifies the module. */
    fun dispose() {
        runCatching { module.onDisable(this) }
            .onFailure { Log.w(LOG_TAG, "onDisable failed for ${module.id}", it) }
        synchronized(lock) {
            items.clear()
            toolbar.clear()
            settings.clear()
            themeOverrides.clear()
        }
        onChanged()
    }

    private fun requireReadAccess() {
        require(
            ModulePermission.PREFERENCES_READ in granted ||
                ModulePermission.PREFERENCES_WRITE in granted,
        ) {
            "Module ${module.id} did not declare any preference permission"
        }
    }

    companion object {
        private const val LOG_TAG = "IceravenModule"
    }
}
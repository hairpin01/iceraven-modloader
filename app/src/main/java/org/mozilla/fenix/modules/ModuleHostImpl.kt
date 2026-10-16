/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log
import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleHost
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModulePermission
import org.mozilla.fenix.modules.api.ModuleThemeScope
import org.mozilla.fenix.utils.Settings

/**
 * Browser side implementation of [ModuleHost] handed to a single loaded module.
 *
 * Every method checks [BrowserModule.permissions] first, so a module can not do more than it declared even if it is
 * modified after installation. All mutable state is guarded by [lock] because modules are enabled on the loader thread
 * while menu items are read on the main thread.
 */
internal class ModuleHostImpl(
    private val module: BrowserModule,
    override val resources: Resources,
    browserSettings: Settings,
    private val onChanged: () -> Unit,
) : ModuleHost {

    private val lock = Any()
    private val items = mutableListOf<ModuleMenuItem>()
    private val themeOverrides = mutableMapOf<ModuleThemeScope, MutableMap<ModuleColorSlot, Int>>()
    private val browserPreferences: SharedPreferences = browserSettings.preferences

    /** Menu items currently contributed by this module. */
    val menuItems: List<ModuleMenuItem>
        get() = synchronized(lock) { items.toList() }

    /** Colour overrides currently registered by this module, as `scope -> slot -> ARGB`. */
    val colors: Map<ModuleThemeScope, Map<ModuleColorSlot, Int>>
        get() = synchronized(lock) { themeOverrides.mapValues { it.value.toMap() } }

    override fun addMenuItem(item: ModuleMenuItem) {
        require(ModulePermission.MENU_ITEMS in module.permissions) {
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

    override fun getBooleanPref(key: String, default: Boolean): Boolean {
        requireReadAccess()
        return browserPreferences.getBoolean(key, default)
    }

    override fun getStringPref(key: String, default: String): String {
        requireReadAccess()
        return browserPreferences.getString(key, default) ?: default
    }

    override fun setPref(key: String, value: Any) {
        require(ModulePermission.PREFERENCES_WRITE in module.permissions) {
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
        require(ModulePermission.THEME in module.permissions) {
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
            themeOverrides.clear()
        }
        onChanged()
    }

    private fun requireReadAccess() {
        require(
            ModulePermission.PREFERENCES_READ in module.permissions ||
                ModulePermission.PREFERENCES_WRITE in module.permissions,
        ) {
            "Module ${module.id} did not declare any preference permission"
        }
    }

    companion object {
        private const val LOG_TAG = "IceravenModule"
    }
}
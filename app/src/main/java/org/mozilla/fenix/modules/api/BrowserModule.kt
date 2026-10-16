/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules.api

import android.content.res.Resources
import android.graphics.Bitmap

/**
 * Capabilities a module asks the browser for.
 *
 * Permissions are declared in the plugin archive manifest and must match the set returned by
 * [BrowserModule.permissions]. The host refuses to expose a capability that was not declared, so a module can never
 * silently gain more power than the user agreed to install.
 */
enum class ModulePermission {
    /** Allows reading browser preferences through [ModuleHost.getBooleanPref] and [ModuleHost.getStringPref]. */
    PREFERENCES_READ,

    /** Allows writing browser preferences through [ModuleHost.setPref]. */
    PREFERENCES_WRITE,

    /** Allows adding entries to the browser menu through [ModuleHost.addMenuItem]. */
    MENU_ITEMS,

    /** Allows recolouring the browser through [ModuleHost.setThemeColor] and [ModuleHost.clearThemeColors]. */
    THEME,
}

/**
 * Which built in theme a colour override applies to.
 *
 * The browser ships four themes (light, dark, OLED and private browsing). Overrides registered with [ALL] are applied to
 * every one of them, which is what most theme modules want; the other scopes are for modules that only care about, say,
 * private browsing.
 */
enum class ModuleThemeScope {
    ALL,
    LIGHT,
    DARK,
    OLED,
    PRIVATE,
}

/**
 * Colour slots a module can override.
 *
 * The first group is the browser's own palette (the "Acorn" tokens, e.g. form defaults, information, success and warning
 * colours), the second group is the Material 3 colour scheme most components are built on. Slots that are not overridden
 * keep the value the browser shipped with, so a module only has to name the handful of colours it actually changes.
 */
enum class ModuleColorSlot {
    // Acorn palette.
    FORM_DEFAULT,
    INFORMATION,
    ON_INFORMATION,
    INFORMATION_CONTAINER,
    ON_INFORMATION_CONTAINER,
    SUCCESS,
    ON_SUCCESS,
    SUCCESS_CONTAINER,
    ON_SUCCESS_CONTAINER,
    WARNING,
    ON_WARNING,
    WARNING_CONTAINER,
    ON_WARNING_CONTAINER,
    SURFACE_DIM_VARIANT,
    SURFACE_CONTAINER_SELECTED,
    AUTOFILL_TEXT,
    SELECTED_TEXT,
    ICON_PRIVATE,
    SHEET_OUTLINE,

    // Material 3 colour scheme.
    PRIMARY,
    ON_PRIMARY,
    PRIMARY_CONTAINER,
    ON_PRIMARY_CONTAINER,
    INVERSE_PRIMARY,
    SECONDARY,
    ON_SECONDARY,
    SECONDARY_CONTAINER,
    ON_SECONDARY_CONTAINER,
    TERTIARY,
    ON_TERTIARY,
    TERTIARY_CONTAINER,
    ON_TERTIARY_CONTAINER,
    BACKGROUND,
    ON_BACKGROUND,
    SURFACE,
    ON_SURFACE,
    SURFACE_VARIANT,
    ON_SURFACE_VARIANT,
    SURFACE_TINT,
    INVERSE_SURFACE,
    INVERSE_ON_SURFACE,
    ERROR,
    ON_ERROR,
    ERROR_CONTAINER,
    ON_ERROR_CONTAINER,
    OUTLINE,
    OUTLINE_VARIANT,
    SCRIM,
    SURFACE_BRIGHT,
    SURFACE_DIM,
    SURFACE_CONTAINER,
    SURFACE_CONTAINER_HIGH,
    SURFACE_CONTAINER_HIGHEST,
    SURFACE_CONTAINER_LOW,
    SURFACE_CONTAINER_LOWEST,
}

/**
 * Entry point of a runtime module.
 *
 * A module is a plain object with a no-argument constructor. The host instantiates it through a
 * [dalvik.system.DexClassLoader] whose parent is the browser class loader, so this interface resolves to the browser's
 * own copy of the class.
 *
 * Implementations must be resilient: [onEnable] and [onDisable] run on the host's loader thread and any exception
 * thrown from them is caught, logged and surfaced in the module manager instead of crashing the browser.
 */
interface BrowserModule {

    /**
     * Stable unique identifier of the module.
     *
     * Must match `[A-Za-z0-9_.-]+` and stay the same across updates, otherwise user preferences are lost.
     */
    val id: String

    /** Human readable name shown in the module manager. */
    val name: String

    /** Module version, bumped by the author on every update. */
    val version: Int

    /** Optional one line description shown under [name] in the module manager. */
    val description: String get() = ""

    /** Capabilities this module needs. Must match the manifest declaration. */
    val permissions: Set<ModulePermission> get() = emptySet()

    /**
     * Called every time the module is enabled, including on browser startup.
     *
     * Register menu items and apply preferences here, keep it fast and side effect free otherwise.
     */
    fun onEnable(host: ModuleHost) = Unit

    /** Called when the module is disabled or uninstalled, before the module is dropped. */
    fun onDisable(host: ModuleHost) = Unit
}

/**
 * The browser side API handed to a [BrowserModule].
 *
 * Everything a module can do to the browser goes through this interface, which keeps the attack surface of the plugin
 * system explicit and versionable.
 */
interface ModuleHost {

    /**
     * Resources packaged inside the plugin archive.
     *
     * Never use the plugin's own `R` class: resource ids of the host and of the plugin may collide. Resolve ids by name
     * instead: `host.resources.getIdentifier("my_label", "string", "com.example.my_module")`.
     */
    val resources: Resources

    /**
     * Adds an entry to the browser menu, right below the WebExtension entries.
     *
     * Requires [ModulePermission.MENU_ITEMS]. Items with the same [ModuleMenuItem.id] are replaced.
     */
    fun addMenuItem(item: ModuleMenuItem)

    /** Removes every menu item registered by this module. */
    fun removeMenuItems()

    /** Reads a preference. Requires [ModulePermission.PREFERENCES_READ] or [ModulePermission.PREFERENCES_WRITE]. */
    fun getBooleanPref(key: String, default: Boolean): Boolean

    /** Reads a preference. Requires [ModulePermission.PREFERENCES_READ] or [ModulePermission.PREFERENCES_WRITE]. */
    fun getStringPref(key: String, default: String): String

    /** Writes a browser preference. Requires [ModulePermission.PREFERENCES_WRITE]. */
    fun setPref(key: String, value: Any)

    /**
     * Overrides a colour of the browser theme.
     *
     * Requires [ModulePermission.THEME]. [argb] is a plain ARGB integer such as `0xFF00AA00`, so a module needs no
     * Compose dependency to ship a theme. Values are applied the next time the UI is composed, which usually means
     * immediately for a running app and on the next start after a module was enabled or disabled.
     *
     * @param scope which built in theme the override applies to.
     * @param slot the colour slot to replace.
     * @param argb the new colour, as a packed ARGB integer.
     */
    fun setThemeColor(scope: ModuleThemeScope, slot: ModuleColorSlot, argb: Int)

    /** Removes every colour override registered by this module. Requires [ModulePermission.THEME]. */
    fun clearThemeColors()

    /** Writes to logcat under the `IceravenModule` tag. */
    fun log(message: String)
}

/**
 * Description of a browser menu entry contributed by a module.
 *
 * Mirrors the browser's WebExtension menu item shape, so module entries are rendered by the exact same code path.
 */
data class ModuleMenuItem(
    /** Identifier of the item, unique within the module. */
    val id: String,
    /** Text shown in the menu. */
    val label: String,
    /** Optional icon, rendered at the browser's standard menu icon size. */
    val icon: Bitmap? = null,
    /** Whether the entry is clickable. */
    val enabled: Boolean = true,
    /** Optional short badge drawn on the right side of the entry. */
    val badgeText: String? = null,
    /** Invoked on the main thread when the entry is tapped. */
    val onClick: () -> Unit,
)
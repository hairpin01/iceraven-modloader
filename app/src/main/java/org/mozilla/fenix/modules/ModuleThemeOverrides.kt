/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import mozilla.components.compose.base.theme.AcornColors
import mozilla.components.compose.base.theme.Theme
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleThemeScope

/**
 * Applies the colour overrides registered by modules to the browser palettes.
 *
 * Everything funnels through here so the theme itself stays untouched: [org.mozilla.fenix.theme.FirefoxTheme] asks for
 * "the palette for this theme" and gets the shipped one with module colours layered on top. A module that overrides
 * nothing costs a single empty map lookup.
 *
 * Both palette types are copied with named arguments, which keeps every slot the module did not touch at the value the
 * browser shipped with.
 */
internal object ModuleThemeOverrides {

    /** Applies the overrides for [theme] to the Acorn palette. */
    fun applyTo(colors: AcornColors, theme: Theme): AcornColors {
        val overrides = overridesFor(theme)
        if (overrides.isEmpty()) return colors

        var result = colors
        overrides[ModuleColorSlot.FORM_DEFAULT]?.let { result = result.copy(formDefault = it.asColor()) }
        overrides[ModuleColorSlot.INFORMATION]?.let { result = result.copy(information = it.asColor()) }
        overrides[ModuleColorSlot.ON_INFORMATION]?.let { result = result.copy(onInformation = it.asColor()) }
        overrides[ModuleColorSlot.INFORMATION_CONTAINER]?.let {
            result = result.copy(informationContainer = it.asColor())
        }
        overrides[ModuleColorSlot.ON_INFORMATION_CONTAINER]?.let {
            result = result.copy(onInformationContainer = it.asColor())
        }
        overrides[ModuleColorSlot.SUCCESS]?.let { result = result.copy(success = it.asColor()) }
        overrides[ModuleColorSlot.ON_SUCCESS]?.let { result = result.copy(onSuccess = it.asColor()) }
        overrides[ModuleColorSlot.SUCCESS_CONTAINER]?.let { result = result.copy(successContainer = it.asColor()) }
        overrides[ModuleColorSlot.ON_SUCCESS_CONTAINER]?.let {
            result = result.copy(onSuccessContainer = it.asColor())
        }
        overrides[ModuleColorSlot.WARNING]?.let { result = result.copy(warning = it.asColor()) }
        overrides[ModuleColorSlot.ON_WARNING]?.let { result = result.copy(onWarning = it.asColor()) }
        overrides[ModuleColorSlot.WARNING_CONTAINER]?.let { result = result.copy(warningContainer = it.asColor()) }
        overrides[ModuleColorSlot.ON_WARNING_CONTAINER]?.let {
            result = result.copy(onWarningContainer = it.asColor())
        }
        overrides[ModuleColorSlot.SURFACE_DIM_VARIANT]?.let { result = result.copy(surfaceDimVariant = it.asColor()) }
        overrides[ModuleColorSlot.SURFACE_CONTAINER_SELECTED]?.let {
            result = result.copy(surfaceContainerSelected = it.asColor())
        }
        overrides[ModuleColorSlot.AUTOFILL_TEXT]?.let { result = result.copy(autofillText = it.asColor()) }
        overrides[ModuleColorSlot.SELECTED_TEXT]?.let { result = result.copy(selectedText = it.asColor()) }
        overrides[ModuleColorSlot.ICON_PRIVATE]?.let { result = result.copy(iconPrivate = it.asColor()) }
        overrides[ModuleColorSlot.SHEET_OUTLINE]?.let { result = result.copy(sheetOutline = it.asColor()) }
        return result
    }

    /** Applies the overrides for [theme] to the Material colour scheme. */
    fun applyTo(scheme: ColorScheme, theme: Theme): ColorScheme {
        val overrides = overridesFor(theme)
        if (overrides.isEmpty()) return scheme

        fun slot(slot: ModuleColorSlot, fallback: Color) = overrides[slot]?.asColor() ?: fallback

        return scheme.copy(
            primary = slot(ModuleColorSlot.PRIMARY, scheme.primary),
            onPrimary = slot(ModuleColorSlot.ON_PRIMARY, scheme.onPrimary),
            primaryContainer = slot(ModuleColorSlot.PRIMARY_CONTAINER, scheme.primaryContainer),
            onPrimaryContainer = slot(ModuleColorSlot.ON_PRIMARY_CONTAINER, scheme.onPrimaryContainer),
            inversePrimary = slot(ModuleColorSlot.INVERSE_PRIMARY, scheme.inversePrimary),
            secondary = slot(ModuleColorSlot.SECONDARY, scheme.secondary),
            onSecondary = slot(ModuleColorSlot.ON_SECONDARY, scheme.onSecondary),
            secondaryContainer = slot(ModuleColorSlot.SECONDARY_CONTAINER, scheme.secondaryContainer),
            onSecondaryContainer = slot(ModuleColorSlot.ON_SECONDARY_CONTAINER, scheme.onSecondaryContainer),
            tertiary = slot(ModuleColorSlot.TERTIARY, scheme.tertiary),
            onTertiary = slot(ModuleColorSlot.ON_TERTIARY, scheme.onTertiary),
            tertiaryContainer = slot(ModuleColorSlot.TERTIARY_CONTAINER, scheme.tertiaryContainer),
            onTertiaryContainer = slot(ModuleColorSlot.ON_TERTIARY_CONTAINER, scheme.onTertiaryContainer),
            background = slot(ModuleColorSlot.BACKGROUND, scheme.background),
            onBackground = slot(ModuleColorSlot.ON_BACKGROUND, scheme.onBackground),
            surface = slot(ModuleColorSlot.SURFACE, scheme.surface),
            onSurface = slot(ModuleColorSlot.ON_SURFACE, scheme.onSurface),
            surfaceVariant = slot(ModuleColorSlot.SURFACE_VARIANT, scheme.surfaceVariant),
            onSurfaceVariant = slot(ModuleColorSlot.ON_SURFACE_VARIANT, scheme.onSurfaceVariant),
            surfaceTint = slot(ModuleColorSlot.SURFACE_TINT, scheme.surfaceTint),
            inverseSurface = slot(ModuleColorSlot.INVERSE_SURFACE, scheme.inverseSurface),
            inverseOnSurface = slot(ModuleColorSlot.INVERSE_ON_SURFACE, scheme.inverseOnSurface),
            error = slot(ModuleColorSlot.ERROR, scheme.error),
            onError = slot(ModuleColorSlot.ON_ERROR, scheme.onError),
            errorContainer = slot(ModuleColorSlot.ERROR_CONTAINER, scheme.errorContainer),
            onErrorContainer = slot(ModuleColorSlot.ON_ERROR_CONTAINER, scheme.onErrorContainer),
            outline = slot(ModuleColorSlot.OUTLINE, scheme.outline),
            outlineVariant = slot(ModuleColorSlot.OUTLINE_VARIANT, scheme.outlineVariant),
            scrim = slot(ModuleColorSlot.SCRIM, scheme.scrim),
            surfaceBright = slot(ModuleColorSlot.SURFACE_BRIGHT, scheme.surfaceBright),
            surfaceDim = slot(ModuleColorSlot.SURFACE_DIM, scheme.surfaceDim),
            surfaceContainer = slot(ModuleColorSlot.SURFACE_CONTAINER, scheme.surfaceContainer),
            surfaceContainerHigh = slot(ModuleColorSlot.SURFACE_CONTAINER_HIGH, scheme.surfaceContainerHigh),
            surfaceContainerHighest = slot(ModuleColorSlot.SURFACE_CONTAINER_HIGHEST, scheme.surfaceContainerHighest),
            surfaceContainerLow = slot(ModuleColorSlot.SURFACE_CONTAINER_LOW, scheme.surfaceContainerLow),
            surfaceContainerLowest = slot(ModuleColorSlot.SURFACE_CONTAINER_LOWEST, scheme.surfaceContainerLowest),
        )
    }

    /**
     * Merges the overrides registered for [theme].
     *
     * `ALL` scope entries are the base, the theme specific scope is layered on top. When two modules set the same slot,
     * the one loaded last wins, which follows installation order and stays stable between runs.
     */
    private fun overridesFor(theme: Theme): Map<ModuleColorSlot, Int> {
        val overrides = ModuleManager.themeOverrides
        if (overrides.isEmpty()) return emptyMap()

        val scope = when (theme) {
            Theme.Light -> ModuleThemeScope.LIGHT
            Theme.Dark -> ModuleThemeScope.DARK
            Theme.Oled -> ModuleThemeScope.OLED
            Theme.Private -> ModuleThemeScope.PRIVATE
        }

        val result = LinkedHashMap<ModuleColorSlot, Int>()
        overrides[ModuleThemeScope.ALL]?.let(result::putAll)
        overrides[scope]?.let(result::putAll)
        return result
    }

    private fun Int.asColor() = Color(this)
}
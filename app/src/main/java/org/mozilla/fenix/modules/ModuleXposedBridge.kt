/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.util.Log
import java.io.File
import org.mozilla.fenix.modules.api.BrowserModule

/**
 * Optional hook support for GeckoMods, so a module can patch browser internals instead of only extending the UI.
 *
 * A module opts in by declaring the usual Xposed meta-data in its archive manifest and by implementing
 * `IXposedHookZygoteInit.initZygote` and `IXposedHookLoadPackage.handleLoadPackage` on its entry point, exactly like a
 * regular LSPosed module:
 *
 * ```xml
 * <meta-data android:name="xposedmodule" android:value="true" />
 * ```
 *
 * Everything here is reflective on purpose. The browser has to build and run without the Xposed API on the classpath,
 * and that API only exists at runtime when the browser itself is started under LSPosed or another Xposed framework.
 * When it does not, hooks stay inactive and the rest of [ModuleHost] keeps working.
 *
 * Entry point methods are looked up by name rather than by interface type, so a module can be compiled against the real
 * `de.robv.android.xposed:api` artifact or against hand written stubs of it. Both work.
 */
internal object ModuleXposedBridge {

    private const val TAG = "GeckoLoader"

    /** Manifest meta-data declaring that the module wants its hooks installed. */
    const val META_XPOSED_MODULE = "xposedmodule"

    /** Manifest meta-data with the description LSPosed shows. Optional. */
    const val META_XPOSED_DESCRIPTION = "xposeddescription"

    private const val BRIDGE_CLASS = "de.robv.android.xposed.XposedBridge"

    /** Whether the browser runs under an Xposed framework that exposes the API to apps. */
    fun isAvailable(context: Context): Boolean = runCatching {
        Class.forName(BRIDGE_CLASS, false, context.applicationContext.classLoader)
    }.isSuccess

    /**
     * Installs the hooks of [module] if the browser runs under Xposed.
     *
     * Only called for enabled modules that declared `META_XPOSED_MODULE` in their manifest.
     *
     * @param archive the module archive, handed to `initZygote` as its module path.
     * @return whether hooks are active. `false` means no Xposed framework is present, which is not an error.
     */
    fun attach(context: Context, archive: File, module: BrowserModule): Boolean {
        val app = context.applicationContext
        val browserClassLoader = app.classLoader

        if (!runCatching { Class.forName(BRIDGE_CLASS, false, browserClassLoader) }.isSuccess) {
            Log.i(TAG, "GeckoMod ${module.id} wants Xposed hooks, but the browser is not running under Xposed")
            return false
        }

        // IXposedHookZygoteInit runs before the app exists, so it only gets the module path.
        hookMethod(module, "initZygote") { parameterType ->
            val startup = parameterType.getDeclaredConstructor().newInstance()
            fill(
                startup,
                mapOf(
                    "modulePath" to archive.absolutePath,
                    "workingDir" to (archive.parentFile?.absolutePath ?: ""),
                    "startsServer" to false,
                    "isSystemServer" to false,
                ),
            )
            startup
        }

        // IXposedHookLoadPackage is the one that matters: it hands the module the browser's class loader.
        val loadPackage = methodOf(module, "handleLoadPackage") ?: return false

        return runCatching {
            val param = loadPackage.parameterTypes.first().getDeclaredConstructor().newInstance()
            fill(
                param,
                mapOf(
                    "packageName" to app.packageName,
                    "processName" to app.packageName,
                    "classLoader" to browserClassLoader,
                    "appInfo" to app.applicationInfo,
                    "firstApplication" to true,
                    "isFirstApplication" to true,
                ),
            )
            loadPackage.invoke(module, param)
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Xposed hooks of GeckoMod ${module.id} failed", failure)
            false
        }.also { active ->
            if (active) Log.i(TAG, "Xposed hooks of GeckoMod ${module.id} are active")
        }
    }

    /**
     * Invokes a single argument hook method when the module declares it.
     *
     * This is how the optional `initZygote` stage is supported without having to know whether the module implements
     * `IXposedHookZygoteInit` at all, and without a compile time dependency on the Xposed API.
     */
    private fun hookMethod(
        module: BrowserModule,
        name: String,
        argument: (Class<*>) -> Any?,
    ) {
        val method = methodOf(module, name) ?: return

        runCatching {
            method.invoke(module, argument(method.parameterTypes.first()))
        }.onFailure { failure -> Log.w(TAG, "$name failed for ${module.javaClass.name}", failure) }
    }

    private fun methodOf(module: BrowserModule, name: String) =
        module.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }

    /**
     * Fills the public fields of a hook parameter by name.
     *
     * Unknown fields are skipped on purpose: the Xposed API carries both `firstApplication` and `isFirstApplication`
     * depending on its version, and a module compiled against stubs only has the fields its stubs declare.
     */
    private fun fill(target: Any, values: Map<String, Any?>) {
        values.forEach { (name, value) ->
            val field = target.javaClass.fields.firstOrNull { it.name == name } ?: return@forEach
            runCatching { field.set(target, value) }
                .onFailure { Log.w(TAG, "Could not set hook parameter $name", it) }
        }
    }
}
package de.robv.android.xposed

import android.content.pm.ApplicationInfo

/**
 * Minimal stand-ins for the Xposed API, so this example compiles without the `de.robv.android.xposed:api` artifact.
 *
 * A real GeckoMod compiles against the official API jar (or `api-82.jar` from LSPosed) instead of shipping these
 * declarations. GeckoLoader never links against them either: it looks the entry point up by name and calls it
 * reflectively, so both variants work. See `modules/ModuleXposedBridge.kt`.
 */
interface IXposedHookLoadPackage {

    fun handleLoadPackage(param: LoadPackageParam)

    class LoadPackageParam {
        @JvmField
        var packageName: String = ""

        @JvmField
        var processName: String = ""

        @JvmField
        var classLoader: ClassLoader = ClassLoader.getSystemClassLoader()

        @JvmField
        var appInfo: ApplicationInfo? = null

        @JvmField
        var firstApplication: String = ""

        @JvmField
        var isFirstApplication: Boolean = false
    }
}

/** Optional counterpart, called once before any process is forked. */
interface IXposedHookZygoteInit {

    fun initZygote(startupParam: StartupParam)

    class StartupParam {
        @JvmField
        var modulePath: String = ""

        @JvmField
        var workingDir: String = ""

        @JvmField
        var startsServer: Boolean = false

        @JvmField
        var isSystemServer: Boolean = false
    }
}
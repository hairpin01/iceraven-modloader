/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.fenix.modules.ModulePluginLoader.LoadedModule
import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModuleColorSlot
import org.mozilla.fenix.modules.api.ModuleMenuItem
import org.mozilla.fenix.modules.api.ModuleSetting
import org.mozilla.fenix.modules.api.ModuleThemeScope
import org.mozilla.fenix.modules.api.ModuleToolbarAction
import org.mozilla.fenix.utils.Settings

/**
 * Registry of the runtime modules installed in the browser.
 *
 * Lifecycle: [ensureStarted] is called once during process startup and loads every enabled archive on a background
 * thread. The rest of the app observes [state] for the module manager UI and [menuItems] for browser menu entries.
 * Any change to the installed set goes through [reload], so loading happens in exactly one place and a module is never
 * half initialised from two threads at once.
 *
 * Archives live in `filesDir/modules` and are named `<installId>.apk`, where `installId` is the first 16 hex characters
 * of the archive's SHA-256. That keeps install ids stable across reinstalls without a separate index to keep in sync.
 */
object ModuleManager {

    private const val TAG = "IceravenModules"
    private const val MODULES_DIR = "modules"
    private const val INSTALL_ID_LENGTH = 16
    private const val LOADER_THREAD_NAME = "iceraven-modules"

    /** Everything the module manager screen needs to render one installed archive. */
    data class ModuleRecord(
        /** Stable id of the installation, also used as the archive file name. */
        val installId: String,
        /** Module metadata, or `null` when the archive could not be loaded. */
        val module: BrowserModule?,
        /** Whether the user has this module switched on. */
        val enabled: Boolean,
        /** Archive file name, shown in the UI so the user can identify it. */
        val fileName: String,
        /** Application id of the archive, for support purposes. */
        val packageName: String? = null,
        /** Human readable reason why the archive was rejected. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow<List<ModuleRecord>>(emptyList())
    val state: StateFlow<List<ModuleRecord>> = _state.asStateFlow()

    private val _menuItems = MutableStateFlow<List<ModuleMenuItem>>(emptyList())
    val menuItems: StateFlow<List<ModuleMenuItem>> = _menuItems.asStateFlow()

    private val _toolbarActions = MutableStateFlow<List<ModuleToolbarAction>>(emptyList())
    val toolbarActions: StateFlow<List<ModuleToolbarAction>> = _toolbarActions.asStateFlow()

    /**
     * Settings rows of every enabled module, as `installId -> rows`, in load order.
     *
     * Rendered by the module manager below the module's own switch.
     */
    private val _settings = MutableStateFlow<Map<String, List<ModuleSetting>>>(emptyMap())
    val settings: StateFlow<Map<String, List<ModuleSetting>>> = _settings.asStateFlow()

    /**
     * Colour overrides of every enabled module, as `scope -> slot -> ARGB`, in load order.
     *
     * Held as Compose state so that enabling a theme module re-themes a running browser instead of waiting for the next
     * start. Read it through [ModuleThemeOverrides] rather than directly.
     */
    var themeOverrides: Map<ModuleThemeScope, Map<ModuleColorSlot, Int>> by mutableStateOf(emptyMap())
        private set

    /** Live state, guarded by [lock]. */
    private val lock = Any()
    private val hosts = LinkedHashMap<String, ModuleHostImpl>()
    private val records = LinkedHashMap<String, ModuleRecord>()

    private val started = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preferences: ModulePreferences? = null

    /**
     * Starts the module system for this process if it was not started yet.
     *
     * Safe to call from anywhere, including [android.app.Application.onCreate]: only the application context is
     * remembered here, everything that touches disk runs on the loader thread. Fenix runs StrictMode with a death
     * penalty for main thread disk access, so touching [ModulePreferences] here would kill the browser on startup.
     * Observe [state] to learn when the modules are ready.
     */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return

        appContext = context.applicationContext
        reload()
    }

    /**
     * Reloads every archive from disk, dropping the hosts of modules that disappeared or got disabled.
     *
     * Called after install, uninstall, a signature policy change and during startup. Loading runs on a background
     * thread and publishes into [state] when it is done.
     */
    fun reload() {
        val context = appContext ?: return

        onLoaderThread { loadAll(context, preferencesFor(context)) }
    }

    /** Preferences handle, created on first use. Must only be called from the loader thread. */
    private fun preferencesFor(context: Context): ModulePreferences =
        preferences ?: synchronized(this) {
            preferences ?: ModulePreferences(context).also { preferences = it }
        }

    private fun onLoaderThread(block: () -> Unit) {
        Thread(block, LOADER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Copies the archive behind [uri] into the private modules directory and loads it.
     *
     * Returns the install id on success, or a failure with a message suitable for showing to the user. The caller does
     * not need to call [reload] afterwards.
     */
    fun install(context: Context, uri: Uri): Result<String> {
        val app = context.applicationContext
        val directory = File(app.filesDir, MODULES_DIR).apply { mkdirs() }
        val incoming = File(directory, "incoming_${System.currentTimeMillis()}.apk")

        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open the selected file")

            val installId = incoming.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
                    .take(INSTALL_ID_LENGTH)
            }

            val target = File(directory, "$installId.apk")
            if (!incoming.renameTo(target)) {
                incoming.copyTo(target, overwrite = true)
                incoming.delete()
            }
            installId
        }.onSuccess { installId ->
            Log.i(TAG, "Installed module archive $installId")
            reload()
        }.onFailure { failure ->
            Log.w(TAG, "Module install failed", failure)
            incoming.delete()
        }
    }

    /** Removes the archive and all state of the module with the given [installId]. */
    fun uninstall(context: Context, installId: String) {
        val app = context.applicationContext

        // Drop the running module first so the UI reacts immediately, then delete the archive on the loader thread:
        // File.exists and the preferences file both read from disk, which is fatal on the main thread.
        synchronized(lock) {
            hosts.remove(installId)?.dispose()
            records.remove(installId)
        }
        publish()

        onLoaderThread {
            val archive = File(File(app.filesDir, MODULES_DIR), "$installId.apk")
            if (archive.exists() && !archive.delete()) {
                Log.w(TAG, "Could not delete archive ${archive.name}")
            }
            preferencesFor(app).forget(installId)
        }
    }

    /** Enables or disables a module. Both directions apply immediately, no browser restart needed. */
    fun setEnabled(context: Context, installId: String, enabled: Boolean) {
        val app = context.applicationContext

        onLoaderThread { preferencesFor(app).setEnabled(installId, enabled) }

        if (enabled) {
            reload()
            return
        }

        synchronized(lock) {
            hosts.remove(installId)?.dispose()
            records[installId]?.let { record -> records[installId] = record.copy(enabled = false) }
        }
        publish()
    }

    /**
 * Runs the callback of the toolbar button with the given id, on the main thread.
 *
 * Buttons disappear from the toolbar as soon as their module is disabled, but a tap can still be in flight, so unknown
 * ids are ignored instead of failing.
 */
    fun invokeToolbarAction(actionId: String) {
        val action = synchronized(lock) {
            hosts.values.flatMap { it.toolbarActions }.firstOrNull { it.id == actionId }
        } ?: return

        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.onClick()
            } else {
                Handler(Looper.getMainLooper()).post { action.onClick() }
            }
        }.onFailure { Log.w(TAG, "Toolbar action $actionId of a module failed", it) }
    }

    private fun loadAll(context: Context, prefs: ModulePreferences) {
        val settings = Settings(context)
        val directory = File(context.filesDir, MODULES_DIR)
        val archives = directory.listFiles { file ->
            file.isFile && (
                file.name.endsWith(".apk", ignoreCase = true) ||
                    file.name.endsWith(".jar", ignoreCase = true)
                )
        }.orEmpty().sortedBy { it.name }

        val loadedHosts = LinkedHashMap<String, ModuleHostImpl>()
        val loadedRecords = archives.mapNotNull { archive ->
            loadArchive(context, prefs, settings, archive, loadedHosts)
        }

        synchronized(lock) {
            (hosts.keys - loadedHosts.keys).forEach { staleId ->
                Log.i(TAG, "Disposing module $staleId")
                hosts.remove(staleId)?.dispose()
            }
            hosts.clear()
            hosts.putAll(loadedHosts)
            records.clear()
            records.putAll(loadedRecords.associateBy { it.installId })
        }
        publish()
    }

    private fun loadArchive(
        context: Context,
        prefs: ModulePreferences,
        settings: Settings,
        archive: File,
        loadedHosts: MutableMap<String, ModuleHostImpl>,
    ): ModuleRecord? {
        val installId = archive.nameWithoutExtension
        val enabled = prefs.isEnabled(installId)

        val loaded: LoadedModule = ModulePluginLoader.load(context, archive, installId, prefs)
            .getOrElse { failure ->
                Log.w(TAG, "Module ${archive.name} rejected", failure)
                return ModuleRecord(
                    installId = installId,
                    module = null,
                    enabled = enabled,
                    fileName = archive.name,
                    error = failure.message ?: failure::class.java.simpleName,
                )
            }

        if (!enabled) {
            return ModuleRecord(
                installId = installId,
                module = loaded.module,
                enabled = false,
                fileName = archive.name,
                packageName = loaded.packageName,
            )
        }

        val host = ModuleHostImpl(
            module = loaded.module,
            resources = loaded.resources,
            browserSettings = settings,
            onChanged = ::publish,
        )

        try {
            loaded.module.onEnable(host)
        } catch (failure: Throwable) {
            Log.w(TAG, "onEnable failed for ${loaded.module.id}", failure)
            host.dispose()
            return ModuleRecord(
                installId = installId,
                module = loaded.module,
                enabled = enabled,
                fileName = archive.name,
                packageName = loaded.packageName,
                error = "onEnable failed: ${failure.message ?: failure::class.java.simpleName}",
            )
        }

        loadedHosts[installId] = host
        return ModuleRecord(
            installId = installId,
            module = loaded.module,
            enabled = true,
            fileName = archive.name,
            packageName = loaded.packageName,
        )
    }

    private fun publish() {
        synchronized(lock) {
            _state.value = records.values.toList()
            _menuItems.value = hosts.values.flatMap { it.menuItems }
            _toolbarActions.value = hosts.values.flatMap { it.toolbarActions }
            _settings.value = hosts.mapValues { (_, host) -> host.moduleSettings }

            val overrides = LinkedHashMap<ModuleThemeScope, MutableMap<ModuleColorSlot, Int>>()
            hosts.values.forEach { host ->
                host.colors.forEach { (scope, slots) ->
                    overrides.getOrPut(scope) { mutableMapOf() }.putAll(slots)
                }
            }
            themeOverrides = overrides.mapValues { it.value.toMap() }
        }
    }
}
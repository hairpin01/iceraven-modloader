/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest
import org.mozilla.fenix.modules.api.BrowserModule
import org.mozilla.fenix.modules.api.ModulePermission

/**
 * Turns an archive (`.apk` or `.jar`) into an instantiated [BrowserModule].
 *
 * The plugin class loader uses the browser class loader as its parent, so every class that lives in the browser or in
 * android-components is shared, not duplicated. That is what allows a plugin to implement [BrowserModule] and receive a
 * real [org.mozilla.fenix.modules.api.ModuleHost] without any stub jar or shared library plumbing.
 */
internal object ModulePluginLoader {

    private const val TAG = "IceravenModules"

    /** Manifest meta-data holding the fully qualified name of the [BrowserModule] implementation. */
    const val META_ENTRY_POINT = "org.mozilla.fenix.modules.entrypoint"

    /** Manifest meta-data holding a comma separated list of [ModulePermission] names. */
    const val META_PERMISSIONS = "org.mozilla.fenix.modules.permissions"

    /** A successfully loaded module. */
    data class LoadedModule(
        val installId: String,
        val module: BrowserModule,
        val resources: Resources,
        val packageName: String,
    )

    /**
     * Loads [archive] and instantiates its entry point.
     *
     * @return the loaded module, or a failure describing what went wrong. Failures are expected here: archives can be
     * signed by an untrusted key, miss the entry point meta-data or simply crash on construction. All of them are
     * reported to the user through the module manager instead of being thrown into the browser's startup path.
     */
    fun load(
        context: Context,
        archive: File,
        installId: String,
        preferences: ModulePreferences,
    ): Result<LoadedModule> = runCatching {
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            archive.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA,
        ) ?: error("Not a readable package archive")

        verifySignature(context, archive, preferences)

        val applicationInfo = packageInfo.applicationInfo
            ?: error("Archive has no application element")

        val entryPoint = applicationInfo.metaData?.getString(META_ENTRY_POINT)
            ?: error("Manifest is missing $META_ENTRY_POINT meta-data")

        val classLoader = DexClassLoader(
            archive.absolutePath,
            File(context.codeCacheDir, "module_dex").apply { mkdirs() }.absolutePath,
            null,
            ModulePluginLoader::class.java.classLoader,
        )

        val module = (classLoader.loadClass(entryPoint).getDeclaredConstructor().newInstance() as? BrowserModule)
            ?: error("$entryPoint does not implement BrowserModule")

        validate(module)

        val resources = createResources(context, archive.absolutePath)

        Log.i(TAG, "Loaded module ${module.id} v${module.version} from ${archive.name}")
        LoadedModule(installId, module, resources, packageInfo.packageName)
    }

    /**
     * Builds a [Resources] view over the archive, so a module can ship its own strings and drawables.
     *
     * Both the [AssetManager] constructor and [AssetManager.addAssetPath] are hidden from the Android SDK but public in
     * the framework itself, which is why they are reached through reflection. This is the only way to read resources
     * out of an archive that is not installed as a package; on Android 9+ both calls are on the grey list, which logs
     * warning instead of failing.
     */
    private fun createResources(context: Context, archivePath: String): Resources {
        val assetManagerClass = AssetManager::class.java
        val assetManager = assetManagerClass.getConstructor().newInstance()

        val errorCode = assetManagerClass
            .getMethod("addAssetPath", String::class.java)
            .invoke(assetManager, archivePath) as Int
        check(errorCode != 0) { "Archive has no readable resources (code $errorCode)" }

        return Resources(
            assetManager,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    /**
     * Rejects modules that would gain more capabilities than their manifest declares, and modules with an id that is
     * unsafe to use as a preference key.
     */
    private fun validate(module: BrowserModule) {
        require(module.id.isNotBlank()) { "Module id must not be blank" }
        require(VALID_ID.matches(module.id)) { "Module id '${module.id}' has unsupported characters" }
        require(module.name.isNotBlank()) { "Module name must not be blank" }
    }

    private fun verifySignature(context: Context, archive: File, preferences: ModulePreferences) {
        val archiveSigners = signerFingerprints(context, archive)
        require(archiveSigners.isNotEmpty()) { "Archive is not signed" }

        if (preferences.allowUntrusted) {
            return
        }

        val trusted = preferences.trustedSigners.ifEmpty { browserFingerprints(context) }
        require(archiveSigners.any { it in trusted }) {
            "Signature ${archiveSigners.first()} is not trusted. Enable developer mode in Settings > Modules."
        }
    }

    /** Signing certificates of the browser itself, which seed the trusted signer set. */
    private fun browserFingerprints(context: Context): Set<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        return fingerprintsOf(signaturesOf(info))
    }

    private fun signerFingerprints(context: Context, archive: File): Set<String> {
        val info = context.packageManager.getPackageArchiveInfo(
            archive.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return emptySet()

        return fingerprintsOf(signaturesOf(info))
    }

    /**
     * Reading [PackageInfo.signingInfo] on Android 9+ and falling back to the deprecated [PackageInfo.signatures] on
     * older releases, where a single signer is all the platform reports.
     */
    private fun signaturesOf(info: PackageInfo): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

    private fun fingerprintsOf(signatures: Array<Signature>?): Set<String> =
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02X".format(byte) }
        }.toSet()

    private val VALID_ID = Regex("[A-Za-z0-9_.-]{1,64}")
}
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context

/**
 * Persistent state of the module manager.
 *
 * Kept in its own [android.content.SharedPreferences] file instead of Fenix' `Settings` so that neither upstream merges
 * nor the module system itself can corrupt regular browser preferences.
 */
internal class ModulePreferences(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether archives that are not signed by a trusted certificate may be loaded.
     *
     * Off by default: a module runs with the full privileges of the browser, so loading an unsigned archive is the same
     * decision as installing an app from an unknown source.
     */
    var allowUntrusted: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_UNTRUSTED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ALLOW_UNTRUSTED, value).apply()
        }

    /**
     * SHA-256 fingerprints of the certificates whose signatures are accepted.
     *
     * Seeded with the browser's own signing certificates, so a module signed with the same key works out of the box.
     */
    var trustedSigners: Set<String>
        get() = preferences.getStringSet(KEY_TRUSTED_SIGNERS, emptySet()).orEmpty()
        set(value) {
            preferences.edit().putStringSet(KEY_TRUSTED_SIGNERS, HashSet(value)).apply()
        }

    /** Modules are enabled as soon as they are installed, unless the user switched them off. */
    fun isEnabled(installId: String): Boolean =
        preferences.getBoolean(enabledKey(installId), true)

    fun setEnabled(installId: String, enabled: Boolean) {
        preferences.edit().putBoolean(enabledKey(installId), enabled).apply()
    }

    fun forget(installId: String) {
        preferences.edit().remove(enabledKey(installId)).apply()
    }

    private fun enabledKey(installId: String) = "$KEY_MODULE_ENABLED_PREFIX$installId"

    companion object {
        private const val FILE_NAME = "iceraven_modules"
        private const val KEY_ALLOW_UNTRUSTED = "allow_untrusted_archives"
        private const val KEY_TRUSTED_SIGNERS = "trusted_signer_fingerprints"
        private const val KEY_MODULE_ENABLED_PREFIX = "module_enabled_"
    }
}
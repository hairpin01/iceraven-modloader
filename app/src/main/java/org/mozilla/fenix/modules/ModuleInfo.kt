/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

/**
 * Metadata a module declares in its archive manifest, shown in the module manager.
 *
 * Everything except [id], [name] and [version] is optional. [updateUrl] must be an https or http URL; anything else is
 * ignored so that the browser never offers to open an arbitrary scheme on the user's behalf.
 */
data class ModuleInfo(
    /** Stable module id, as returned by the entry point. */
    val id: String,
    /** Human readable name. */
    val name: String,
    /** Longer description, shown under the name. */
    val description: String? = null,
    /** Numeric version, used to tell whether an update is newer. */
    val version: Int,
    /** Version string of the archive itself, shown next to [version]. */
    val versionName: String? = null,
    /** Author of the module. */
    val author: String? = null,
    /** Page to open when the user asks to check for updates. */
    val updateUrl: String? = null,
    /** Application id of the archive, used for support and update checks. */
    val packageName: String,
) {
    /** Label to show in the update flow, `null` when there is nothing to update. */
    val updateLabel: String?
        get() = updateUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}
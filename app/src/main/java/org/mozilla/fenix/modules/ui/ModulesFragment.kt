/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.fenix.R
import org.mozilla.fenix.modules.ModuleManager
import org.mozilla.fenix.modules.ModuleManager.ModuleRecord
import org.mozilla.fenix.modules.ModulePreferences

/**
 * Settings screen that installs, enables, disables and removes runtime modules.
 *
 * The screen is a plain [PreferenceFragmentCompat] because the list is built from whatever is on disk: modules come and
 * go at runtime, so there is no static xml for them. State is observed from [ModuleManager.state] instead of being
 * written by the preferences themselves, which keeps the switch state in sync with what the browser actually loaded.
 */
class ModulesFragment : PreferenceFragmentCompat() {

    private val moduleFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { install(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.modules_preferences, rootKey)

        findPreference<Preference>(getString(R.string.pref_key_modules_install))?.apply {
            setOnPreferenceClickListener {
                moduleFilePicker.launch(ARCHIVE_MIME_TYPES)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_modules_allow_untrusted))?.apply {
            val preferences = ModulePreferences(requireContext())
            isChecked = preferences.allowUntrusted
            setOnPreferenceChangeListener { _, newValue ->
                preferences.allowUntrusted = newValue as Boolean
                // Existing hosts are already instantiated, so the signature policy applies from the next reload.
                ModuleManager.reload()
                showMessage(getString(R.string.modules_allow_untrusted_summary))
                true
            }
        }

        findPreference<Preference>(getString(R.string.pref_key_modules_docs))?.apply {
            setOnPreferenceClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODULE_DOCS_URL)))
                }
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ModuleManager.ensureStarted(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModuleManager.state.collect { rebuild() }
            }
        }
    }

    private fun rebuild() {
        val category = findPreference<PreferenceCategory>(getString(R.string.pref_key_modules_installed_category))
            ?: return
        val records = ModuleManager.state.value

        category.removeAll()
        if (records.isEmpty()) {
            category.add(
                Preference(requireContext()).apply {
                    isPersistent = false
                    isSelectable = false
                    setTitle(R.string.modules_none_title)
                },
            )
            return
        }

        records.forEach { record -> addRecord(category, record) }
    }

    private fun addRecord(category: PreferenceCategory, record: ModuleRecord) {
        val context = requireContext()

        category.add(
            SwitchPreferenceCompat(context).apply {
                key = getString(R.string.pref_key_modules_switch) + record.installId
                isPersistent = false
                isIconSpaceReserved = false
                title = record.module?.name ?: record.fileName
                summary = summaryFor(record)
                isChecked = record.enabled && record.error == null
                isEnabled = record.error == null
                setOnPreferenceChangeListener { _, newValue ->
                    ModuleManager.setEnabled(requireContext(), record.installId, newValue as Boolean)
                    true
                }
            },
        )

        category.add(
            Preference(context).apply {
                key = getString(R.string.pref_key_modules_info) + record.installId
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.modules_manage_title)
                summary = record.fileName
                setOnPreferenceClickListener {
                    showDetails(record)
                    true
                }
            },
        )
    }

    private fun summaryFor(record: ModuleRecord): CharSequence {
        val context = requireContext()
        val status = record.error
            ?: record.module?.description?.takeIf { it.isNotBlank() }
            ?: record.module?.let {
                context.getString(R.string.modules_info_format, it.name, it.version, it.id)
            }
            ?: record.fileName

        return if (record.module != null && !record.enabled) {
            getString(R.string.modules_info_disabled) + "\n" + status
        } else {
            status
        }
    }

    private fun showDetails(record: ModuleRecord) {
        val context = requireContext()
        val module = record.module
        val details = buildString {
            if (module != null) {
                appendLine(getString(R.string.modules_info_format, module.name, module.version, module.id))
                if (module.description.isNotBlank()) appendLine(module.description)
                record.packageName?.let {
                    appendLine(getString(R.string.modules_info_package_format, it))
                }
                val permissions = module.permissions
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString { permission -> permission.name }
                    ?: getString(R.string.modules_info_none)
                appendLine(getString(R.string.modules_info_permissions_format, permissions))
                if (!record.enabled) appendLine(getString(R.string.modules_info_disabled))
            }
            record.error?.let { appendLine(it) }
        }.trim()

        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.modules_info_title))
            .setMessage(details)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.modules_uninstall) { _, _ ->
                ModuleManager.uninstall(requireContext(), record.installId)
            }
            .show()
    }

    private fun install(uri: Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ModuleManager.install(context, uri) }
            result
                .onSuccess { showMessage(getString(R.string.modules_install_success)) }
                .onFailure {
                    showMessage(getString(R.string.modules_install_failed, it.message ?: it.javaClass.simpleName))
                }
        }
    }

    private fun showMessage(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    companion object {
        private val ARCHIVE_MIME_TYPES = arrayOf(
            "application/vnd.android.package-archive",
            "application/java-archive",
            "application/zip",
            "application/octet-stream",
        )

        private const val MODULE_DOCS_URL =
            "https://github.com/fork-maintainers/iceraven-browser/tree/iceraven/modules"
    }
}
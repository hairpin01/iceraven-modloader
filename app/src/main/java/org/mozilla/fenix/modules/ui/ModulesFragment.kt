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
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.fenix.R
import org.mozilla.fenix.modules.ModuleManager
import org.mozilla.fenix.modules.ModuleManager.ModuleRecord
import org.mozilla.fenix.modules.ModulePreferences
import org.mozilla.fenix.modules.api.ModuleSetting

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

        findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_modules_safe_mode))?.apply {
            val preferences = ModulePreferences(requireContext())
            isChecked = preferences.safeMode
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                preferences.safeMode = enabled
                if (!enabled) {
                    // Leaving safe mode is the user saying the module is worth another try.
                    preferences.clearAllFailures()
                }
                ModuleManager.reload()
                showMessage(
                    getString(
                        if (enabled) R.string.modules_safe_mode_on else R.string.modules_safe_mode_off,
                    ),
                )
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
                // Both flows matter: a module can change its own settings without the list of modules changing, and
                // StateFlow drops emissions of equal values, so the settings flow has to be observed as well.
                combine(ModuleManager.state, ModuleManager.settings) { _, _ -> }
                    .collect { rebuild() }
            }
        }
    }

    private fun rebuild() {
        val category = findPreference<PreferenceCategory>(getString(R.string.pref_key_modules_installed_category))
            ?: return
        val records = ModuleManager.state.value

        category.removeAll()
        if (records.isEmpty()) {
            category.addPreference(
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

        category.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = getString(R.string.pref_key_modules_switch) + record.installId
                isPersistent = false
                isIconSpaceReserved = false
                title = record.info?.name ?: record.module?.name ?: record.fileName
                summary = summaryFor(record)
                isChecked = record.enabled && record.error == null
                isEnabled = record.error == null
                setOnPreferenceChangeListener { _, newValue ->
                    ModuleManager.setEnabled(requireContext(), record.installId, newValue as Boolean)
                    true
                }
            },
        )

        category.addPreference(
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

        addSettings(category, record)
    }

    /**
     * Renders the settings a module published through `ModuleHost.addSetting`, directly under its own switch.
     *
     * Modules own the values: every change is handed back to the module callback, which is expected to store the new
     * value and publish an updated setting.
     */
    private fun addSettings(category: PreferenceCategory, record: ModuleRecord) {
        if (!record.enabled || record.error != null) return

        ModuleManager.settings.value[record.installId]?.forEach { setting ->
            val context = requireContext()
            val key = getString(R.string.pref_key_modules_setting) + record.installId + setting.key

            category.addPreference(
                when (setting) {
                    is ModuleSetting.Switch -> SwitchPreferenceCompat(context).apply {
                        this.key = key
                        isPersistent = false
                        isIconSpaceReserved = false
                        title = setting.title
                        summary = setting.summary
                        isChecked = setting.checked
                        setOnPreferenceChangeListener { _, newValue ->
                            setting.onCheckedChange(newValue as Boolean)
                            true
                        }
                    }

                    is ModuleSetting.Text -> EditTextPreference(context).apply {
                        this.key = key
                        isPersistent = false
                        isIconSpaceReserved = false
                        title = setting.title
                        summary = setting.summary
                        setText(setting.value)
                        setOnPreferenceChangeListener { _, newValue ->
                            setting.onValueChange(newValue as? String ?: "")
                            true
                        }
                    }

                    is ModuleSetting.Action -> Preference(context).apply {
                        this.key = key
                        isPersistent = false
                        isIconSpaceReserved = false
                        title = setting.title
                        summary = setting.summary
                        setOnPreferenceClickListener {
                            setting.onClick()
                            true
                        }
                    }
                },
            )
        }
    }

    private fun summaryFor(record: ModuleRecord): CharSequence {
        val context = requireContext()
        val status = record.error
            ?: record.info?.description?.takeIf { it.isNotBlank() }
            ?: record.module?.description?.takeIf { it.isNotBlank() }
            ?: record.info?.let {
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
        val info = record.info
        val module = record.module
        val details = buildString {
            if (info != null) {
                appendLine(getString(R.string.modules_info_format, info.name, info.version, info.id))
                info.description?.let { appendLine(it) }
                info.versionName?.let {
                    appendLine(getString(R.string.modules_info_version_name_format, it))
                }
                info.author?.let { appendLine(getString(R.string.modules_info_author_format, it)) }
                appendLine(getString(R.string.modules_info_package_format, info.packageName))
            }
            if (module != null) {
                val permissions = module.permissions
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString { permission -> permission.name }
                    ?: getString(R.string.modules_info_none)
                appendLine(getString(R.string.modules_info_permissions_format, permissions))
            }
            if (record.enabled.not() && module != null) appendLine(getString(R.string.modules_info_disabled))
            record.error?.let { appendLine(it) }
        }.trim()

        val updateLabel = info?.updateLabel
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.modules_info_title))
            .setMessage(details)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (updateLabel != null) {
                    setNeutralButton(R.string.modules_update_open) { _, _ -> openUrl(updateLabel) }
                }
                setPositiveButton(R.string.modules_uninstall) { _, _ ->
                    ModuleManager.uninstall(requireContext(), record.installId)
                }
            }
            .show()
    }

    /** Opens the module's release page. The URL is validated by [ModuleInfo.updateLabel] before it gets here. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { showMessage(getString(R.string.modules_update_failed)) }
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
            "https://github.com/hairpin01/iceraven-modloader/blob/runtime-modules/geckomod/README.md"
    }
}
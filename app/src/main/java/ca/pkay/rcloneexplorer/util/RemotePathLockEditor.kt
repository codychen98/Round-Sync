package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

object RemotePathLockEditor {

    @JvmStatic
    fun show(fragment: Fragment, remote: RemoteItem) {
        val context = fragment.context ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val name = remote.name
        val scroll = ScrollView(context)
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (48 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, pad / 3)
            }
        val enableSwitch =
            SwitchMaterial(context).apply {
                text = context.getString(R.string.path_lock_enable)
                isChecked =
                    prefs.getBoolean(RemotePathLock.enabledPrefsKey(context, name), false)
            }
        val hint =
            TextView(context).apply {
                text = context.getString(R.string.path_lock_prefixes_hint)
                textSize = 13f
            }
        val edit =
            EditText(context).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 5
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                val existing =
                    prefs.getStringSet(RemotePathLock.prefixesPrefsKey(context, name), emptySet())
                        .orEmpty()
                setText(
                    existing.sorted().joinToString("\n") { row ->
                        val n = MediaFolderPolicy.normalizeFolder(row)
                        if (n == "/" || n.isEmpty()) {
                            ""
                        } else {
                            n
                        }
                    }.trimEnd(),
                )
            }
        layout.addView(enableSwitch)
        layout.addView(hint)
        layout.addView(edit)
        scroll.addView(layout)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.path_lock_dialog_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.path_lock_save) { _, _ ->
                applyEdits(context, prefs, name, enableSwitch.isChecked, edit.text?.toString() ?: "")
                if (enableSwitch.isChecked && !PathLockScreenLockGuard.isScreenLockAvailable(context)) {
                    fragment.activity?.let {
                        PathLockScreenLockGuard.showScreenLockRequiredDialog(
                            it,
                            name,
                            PathLockScreenLockGuard.Reason.ENABLED_WITHOUT_LOCK,
                        )
                    }
                }
            }
            .show()
    }

    /** Opens the editor; when the lock is already enabled, authenticate first so it cannot simply be switched off. */
    @JvmStatic
    fun showGated(fragment: Fragment, remote: RemoteItem) {
        val context = fragment.context ?: return
        val activity = fragment.activity ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val locked = prefs.getBoolean(RemotePathLock.enabledPrefsKey(context, remote.name), false)
        if (!locked || RemotePathUnlockSession.isUnlocked(remote.name)) {
            show(fragment, remote)
            return
        }
        PathLockBiometricHelper.requestUnlock(activity, remote.name) {
            if (!fragment.isAdded) {
                return@requestUnlock
            }
            RemotePathUnlockSession.markUnlocked(remote.name)
            show(fragment, remote)
        }
    }

    private fun applyEdits(
        context: Context,
        prefs: android.content.SharedPreferences,
        remoteName: String,
        enabled: Boolean,
        prefixText: String,
    ) {
        val lines =
            prefixText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                MediaFolderPolicy.normalizeFolder(line)
            }.filter { it != "/" && it.isNotEmpty() }.toSet()
        val ed = prefs.edit()
        ed.putBoolean(RemotePathLock.enabledPrefsKey(context, remoteName), enabled)
        ed.putStringSet(RemotePathLock.prefixesPrefsKey(context, remoteName), lines)
        ed.apply()
    }
}

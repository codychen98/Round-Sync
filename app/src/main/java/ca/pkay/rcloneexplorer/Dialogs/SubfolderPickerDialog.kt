package ca.pkay.rcloneexplorer.Dialogs

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dmoral.toasty.Toasty

/**
 * Lists the direct child folders of [remotePath] on [remote] and lets the user tick several.
 * Listing runs on a background thread; all UI work returns to the main thread.
 */
class SubfolderPickerDialog(
    private val activity: AppCompatActivity,
    private val rclone: Rclone,
    private val remote: RemoteItem,
    private val remotePath: String,
    private val preselected: Set<String>,
    private val onPicked: (List<String>) -> Unit,
) {

    fun show() {
        val loading = LoadingDialog().setTitle(R.string.loading).setCanCancel(false)
        loading.show(activity.supportFragmentManager, "subfolder_picker_loading")
        Thread {
            val items = try {
                rclone.getDirectoryContent(remote, remotePath, false)
            } catch (t: Throwable) {
                null
            }
            val error = if (items == null) rclone.consumeLastDirectoryListError() else ""
            val names = items.orEmpty()
                .filter { it.isDir }
                .map { it.name }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            activity.runOnUiThread {
                Dialogs.dismissSilently(loading)
                if (activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                when {
                    items == null -> Toasty.error(
                        activity,
                        error.ifBlank { activity.getString(R.string.task_subfolders_list_failed) },
                        Toast.LENGTH_LONG,
                        true,
                    ).show()
                    names.isEmpty() -> Toasty.info(
                        activity,
                        activity.getString(R.string.task_subfolders_none_found),
                        Toast.LENGTH_SHORT,
                        true,
                    ).show()
                    else -> showChoices(names)
                }
            }
        }.start()
    }

    private fun showChoices(names: List<String>) {
        val checked = BooleanArray(names.size) { names[it] in preselected }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.task_subfolders_dialog_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                onPicked(names.filterIndexed { index, _ -> checked[index] })
            }
            .show()
    }
}

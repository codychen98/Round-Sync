package ca.pkay.rcloneexplorer.Dialogs

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.SubfolderFilterRules
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dmoral.toasty.Toasty

/**
 * Expandable multi-select of folders under [remotePath]. Children load lazily on expand.
 * Selected values are relative paths (may contain `/`); recursive sync is encoded by the filter rules.
 */
class SubfolderPickerDialog(
    private val activity: AppCompatActivity,
    private val rclone: Rclone,
    private val remote: RemoteItem,
    private val remotePath: String,
    private val preselected: Set<String>,
    private val onPicked: (List<String>) -> Unit,
) {

    private val selectedPaths = LinkedHashSet(
        preselected.map { SubfolderFilterRules.normalizeRelativePath(it) }.filter { it.isNotEmpty() },
    )
    private val childrenCache = HashMap<String, List<SubfolderTreeAdapter.Node>>()
    private lateinit var roots: List<SubfolderTreeAdapter.Node>
    private lateinit var adapter: SubfolderTreeAdapter

    fun show() {
        val loading = LoadingDialog().setTitle(R.string.loading).setCanCancel(false)
        loading.show(activity.supportFragmentManager, "subfolder_picker_loading")
        Thread {
            val rootListing = listChildFolders("")
            if (rootListing != null && rootListing.isNotEmpty()) {
                roots = rootListing
                childrenCache[""] = roots
                prefetchAncestorsForPreselection()
            }
            activity.runOnUiThread {
                Dialogs.dismissSilently(loading)
                if (activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                when {
                    rootListing == null -> Toasty.error(
                        activity,
                        activity.getString(R.string.task_subfolders_list_failed),
                        Toast.LENGTH_LONG,
                        true,
                    ).show()
                    rootListing.isEmpty() -> Toasty.info(
                        activity,
                        activity.getString(R.string.task_subfolders_none_found),
                        Toast.LENGTH_SHORT,
                        true,
                    ).show()
                    else -> showTreeDialog()
                }
            }
        }.start()
    }

    /** Loads and expands ancestors of preselected nested paths (call on a worker thread). */
    private fun prefetchAncestorsForPreselection() {
        val ancestors = selectedPaths
            .flatMap { SubfolderFilterRules.ancestorPaths(it) }
            .distinct()
            .sortedBy { it.length }
        for (ancestor in ancestors) {
            val node = findNode(roots, ancestor) ?: continue
            if (!node.childrenLoaded) {
                val kids = listChildFolders(ancestor) ?: continue
                node.children = kids
                node.childrenLoaded = true
                childrenCache[ancestor] = kids
            }
            node.expanded = true
        }
    }

    private fun showTreeDialog() {
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_subfolder_picker, null)
        val list = content.findViewById<RecyclerView>(R.id.subfolder_tree_list)
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        list.layoutParams = list.layoutParams.apply { height = maxHeight }
        adapter = SubfolderTreeAdapter(selectedPaths) { node -> toggleExpand(node) }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        adapter.rebuildVisibleFromRoots(roots)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.task_subfolders_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                onPicked(selectedPaths.toList())
            }
            .show()
    }

    private fun toggleExpand(node: SubfolderTreeAdapter.Node) {
        if (node.loading) {
            return
        }
        if (node.expanded) {
            node.expanded = false
            adapter.rebuildVisibleFromRoots(roots)
            return
        }
        if (node.childrenLoaded) {
            node.expanded = true
            adapter.rebuildVisibleFromRoots(roots)
            return
        }
        node.loading = true
        adapter.rebuildVisibleFromRoots(roots)
        Thread {
            val kids = listChildFolders(node.relativePath)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                node.loading = false
                if (kids == null) {
                    Toasty.error(
                        activity,
                        activity.getString(R.string.task_subfolders_list_failed),
                        Toast.LENGTH_SHORT,
                        true,
                    ).show()
                    adapter.rebuildVisibleFromRoots(roots)
                    return@runOnUiThread
                }
                node.children = kids
                node.childrenLoaded = true
                childrenCache[node.relativePath] = kids
                node.expanded = true
                if (kids.isEmpty()) {
                    Toasty.info(
                        activity,
                        activity.getString(R.string.task_subfolders_none_found),
                        Toast.LENGTH_SHORT,
                        true,
                    ).show()
                }
                adapter.rebuildVisibleFromRoots(roots)
            }
        }.start()
    }

    /**
     * Lists direct child folders of [relativeParent] under the task remote path.
     * Empty [relativeParent] means the sync root. Returns null on rclone failure.
     */
    private fun listChildFolders(relativeParent: String): List<SubfolderTreeAdapter.Node>? {
        childrenCache[relativeParent]?.let { return it }
        val absolute = joinRemotePath(remotePath, relativeParent)
        val items = try {
            rclone.getDirectoryContent(remote, absolute, false)
        } catch (t: Throwable) {
            null
        }
        if (items == null) {
            rclone.consumeLastDirectoryListError()
            return null
        }
        val depth = if (relativeParent.isEmpty()) {
            0
        } else {
            relativeParent.count { it == '/' } + 1
        }
        val nodes = items
            .filter { it.isDir }
            .map { item ->
                val childRelative = if (relativeParent.isEmpty()) {
                    item.name
                } else {
                    "$relativeParent/${item.name}"
                }
                SubfolderTreeAdapter.Node(
                    relativePath = childRelative,
                    name = item.name,
                    depth = depth,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        childrenCache[relativeParent] = nodes
        return nodes
    }

    private fun findNode(nodes: List<SubfolderTreeAdapter.Node>, relativePath: String): SubfolderTreeAdapter.Node? {
        for (node in nodes) {
            if (node.relativePath == relativePath) {
                return node
            }
            val nested = findNode(node.children, relativePath)
            if (nested != null) {
                return nested
            }
        }
        return null
    }

    companion object {
        fun joinRemotePath(root: String, relative: String): String {
            val trimmedRoot = root.trimEnd('/')
            val rel = SubfolderFilterRules.normalizeRelativePath(relative)
            return if (rel.isEmpty()) {
                if (trimmedRoot.isEmpty()) "/" else trimmedRoot
            } else {
                "$trimmedRoot/$rel"
            }
        }
    }
}

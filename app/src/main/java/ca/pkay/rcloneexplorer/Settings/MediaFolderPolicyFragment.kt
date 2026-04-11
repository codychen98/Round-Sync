package ca.pkay.rcloneexplorer.Settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.Activities.MediaFolderPolicyActivity
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.MediaFolderPolicy
import ca.pkay.rcloneexplorer.util.MediaFolderPolicyRow
import ca.pkay.rcloneexplorer.util.SyncLog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import es.dmoral.toasty.Toasty
import io.github.x0b.safdav.file.SafConstants

class MediaFolderPolicyFragment : Fragment(), MediaFolderPolicyAdapter.Listener {

    private lateinit var rclone: Rclone
    private var remotes: List<RemoteItem> = emptyList()
    private lateinit var spinner: Spinner
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var safwNotice: TextView
    private lateinit var adapter: MediaFolderPolicyAdapter
    private lateinit var addFolderFab: ExtendedFloatingActionButton
    private var selectedRemoteIndex: Int = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)
        rclone = Rclone(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_media_folder_policy, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spinner = view.findViewById(R.id.media_policy_remote_spinner)
        recycler = view.findViewById(R.id.media_policy_recycler)
        emptyView = view.findViewById(R.id.media_policy_empty)
        safwNotice = view.findViewById(R.id.media_policy_safw_notice)
        addFolderFab = view.findViewById(R.id.media_policy_add_folder)
        adapter = MediaFolderPolicyAdapter(emptyList(), this)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        addFolderFab.setOnClickListener { openFolderPicker() }
        bindRemoteSpinner()
        if (savedInstanceState != null) {
            selectedRemoteIndex = savedInstanceState.getInt(STATE_REMOTE_INDEX, 0)
                .coerceIn(0, (remotes.size - 1).coerceAtLeast(0))
            if (remotes.isNotEmpty()) {
                spinner.setSelection(selectedRemoteIndex, false)
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedRemoteIndex = position
                refreshRowsFromPreferences()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_REMOTE_INDEX, selectedRemoteIndex)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.title_activity_media_folder_policy)
        refreshRowsFromPreferences()
    }

    private fun bindRemoteSpinner() {
        val list = ArrayList(rclone.remotes)
        RemoteItem.prepareDisplay(requireContext(), list)
        list.sortWith { a, b -> a.displayName.compareTo(b.displayName) }
        remotes = list
        if (list.isEmpty()) {
            addFolderFab.isEnabled = false
            spinner.isEnabled = false
            emptyView.visibility = View.VISIBLE
            return
        }
        val labels = list.map { it.displayName }
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        selectedRemoteIndex = selectedRemoteIndex.coerceIn(0, list.size - 1)
        spinner.setSelection(selectedRemoteIndex, false)
        addFolderFab.isEnabled = true
        spinner.isEnabled = true
    }

    private fun selectedRemote(): RemoteItem? {
        val idx = selectedRemoteIndex.coerceIn(0, (remotes.size - 1).coerceAtLeast(0))
        if (remotes.isEmpty()) {
            return null
        }
        return remotes[idx]
    }

    private fun refreshRowsFromPreferences() {
        val remote = selectedRemote()
        if (remote == null) {
            if (view != null) {
                adapter.submitRows(emptyList())
                emptyView.visibility = View.VISIBLE
            }
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val rows = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
        if (view == null) {
            return
        }
        adapter.submitRows(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val isSafw = remote.typeReadable == SafConstants.SAF_REMOTE_NAME
        safwNotice.visibility = if (isSafw) View.VISIBLE else View.GONE
    }

    private fun openFolderPicker() {
        val remote = selectedRemote()
        if (remote == null) {
            return
        }
        val act = requireActivity()
        if (act is MediaFolderPolicyActivity) {
            SyncLog.info(requireContext(), "PolicyCrashDbg", "event=openFolderPicker remote=${remote.name}")
            act.launchRemoteFolderPicker(remote)
        }
    }

    fun handleFoldersSelectedFromPicker(pickerPaths: List<String>) {
        val remote = selectedRemote() ?: run {
            context?.let {
                SyncLog.info(it, "PolicyCrashDbg", "event=handleFoldersPicker remote=null skipped")
            }
            return
        }
        val ctx = context ?: return
        SyncLog.info(
            ctx,
            "PolicyCrashDbg",
            "event=handleFoldersPicker count=${pickerPaths.size} remote=${remote.name} phase=batch",
        )
        if (pickerPaths.isEmpty()) {
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        var current = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
        val newRows = mutableListOf<MediaFolderPolicyRow>()
        for (pickerPath in pickerPaths) {
            val relative = pickerPathToRelative(remote.name, pickerPath)
            val normalized = MediaFolderPolicy.normalizeFolder(relative)
            if (current.any { MediaFolderPolicy.normalizeFolder(it.path) == normalized }) {
                continue
            }
            if (newRows.any { MediaFolderPolicy.normalizeFolder(it.path) == normalized }) {
                continue
            }
            newRows.add(MediaFolderPolicyRow(path = normalized, thumbnail = true, cache = true))
        }
        if (newRows.isEmpty()) {
            Toasty.info(
                ctx,
                getString(R.string.media_folder_policy_duplicate_folder),
                Toast.LENGTH_SHORT,
                true,
            ).show()
            return
        }
        val updated = current + newRows
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remote.name, updated)
        editor.apply()
        SyncLog.info(
            ctx,
            "PolicyCrashDbg",
            "event=handleFoldersPicker action=added count=${newRows.size} remote=${remote.name}",
        )
        refreshRowsFromPreferences()
    }

    fun handleFolderSelectedFromPicker(pickerPath: String) {
        val remote = selectedRemote()
        if (remote == null) {
            context?.let { SyncLog.info(it, "PolicyCrashDbg", "event=handleFolderPicker path=$pickerPath remote=null action=skipped") }
            return
        }
        val ctx = context ?: return
        SyncLog.info(
            ctx,
            "PolicyCrashDbg",
            "event=handleFolderPicker path=$pickerPath remote=${remote.name} phase=received",
        )
        val relative = pickerPathToRelative(remote.name, pickerPath)
        val normalized = MediaFolderPolicy.normalizeFolder(relative)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
        if (current.any { MediaFolderPolicy.normalizeFolder(it.path) == normalized }) {
            SyncLog.info(ctx, "PolicyCrashDbg", "event=handleFolderPicker action=duplicate normalized=$normalized remote=${remote.name}")
            Toasty.info(
                ctx,
                getString(R.string.media_folder_policy_duplicate_folder),
                Toast.LENGTH_SHORT,
                true,
            ).show()
            return
        }
        val newRow = MediaFolderPolicyRow(path = normalized, thumbnail = true, cache = true)
        val updated = current + newRow
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remote.name, updated)
        editor.apply()
        SyncLog.info(ctx, "PolicyCrashDbg", "event=handleFolderPicker action=added normalized=$normalized remote=${remote.name}")
        refreshRowsFromPreferences()
    }

    override fun onThumbnailToggled(normalizedPath: String, enabled: Boolean) {
        val remote = selectedRemote() ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val current = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
        val updated = current.map { row ->
            if (MediaFolderPolicy.normalizeFolder(row.path) == normalizedPath) {
                MediaFolderPolicyRow(path = row.path, thumbnail = enabled, cache = row.cache)
            } else {
                row
            }
        }.filter { it.thumbnail || it.cache }
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remote.name, updated)
        editor.apply()
        refreshRowsFromPreferences()
    }

    override fun onCacheToggled(normalizedPath: String, enabled: Boolean) {
        val remote = selectedRemote() ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val current = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
        val updated = current.map { row ->
            if (MediaFolderPolicy.normalizeFolder(row.path) == normalizedPath) {
                MediaFolderPolicyRow(path = row.path, thumbnail = row.thumbnail, cache = enabled)
            } else {
                row
            }
        }.filter { it.thumbnail || it.cache }
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remote.name, updated)
        editor.apply()
        refreshRowsFromPreferences()
    }

    override fun onRemoveRow(normalizedPath: String) {
        val remote = selectedRemote() ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val updated = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
            .filter { MediaFolderPolicy.normalizeFolder(it.path) != normalizedPath }
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remote.name, updated)
        editor.apply()
        refreshRowsFromPreferences()
    }

    companion object {
        const val TAG = "MediaFolderPolicyFragment"
        private const val STATE_REMOTE_INDEX = "media_policy_remote_index"

        fun pickerPathToRelative(remoteName: String, selectedPath: String): String {
            val p = selectedPath.trim()
            val rootDouble = "//$remoteName"
            if (p == rootDouble || p == "$rootDouble/") {
                return "/"
            }
            if (p.startsWith("$rootDouble/")) {
                return MediaFolderPolicy.toRemoteRelativePath(remoteName, p)
            }
            val slashRemote = "/$remoteName"
            if (p == slashRemote || p == "$slashRemote/") {
                return "/"
            }
            if (p.startsWith("$slashRemote/")) {
                return MediaFolderPolicy.normalizeFolder(p.substring(slashRemote.length))
            }
            return MediaFolderPolicy.toRemoteRelativePath(remoteName, p)
        }
    }
}

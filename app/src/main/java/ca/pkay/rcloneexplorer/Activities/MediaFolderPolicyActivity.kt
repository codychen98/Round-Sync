package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import ca.pkay.rcloneexplorer.Fragments.FolderSelectorCallback
import ca.pkay.rcloneexplorer.Fragments.RemoteFolderPickerFragment
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Settings.MediaFolderPolicyFragment
import ca.pkay.rcloneexplorer.util.ActivityHelper
import ca.pkay.rcloneexplorer.util.SyncLog

class MediaFolderPolicyActivity : AppCompatActivity(), FolderSelectorCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        SyncLog.info(
            this,
            "PolicyCrashDbg",
            "event=onCreate savedState=${if (savedInstanceState != null) "restored" else "fresh"}",
        )
        setContentView(R.layout.activity_media_folder_policy)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.media_folder_policy_container,
                    MediaFolderPolicyFragment(),
                    MediaFolderPolicyFragment.TAG,
                )
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    fun launchRemoteFolderPicker(remote: RemoteItem) {
        SyncLog.info(this, "PolicyCrashDbg", "event=launchPicker remote=${remote.name}")
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.media_folder_policy_container,
                RemoteFolderPickerFragment.newInstance(remote, this, "", true),
            )
            .addToBackStack(null)
            .commit()
    }

    override fun selectFolder(path: String) {
        val policy = supportFragmentManager.findFragmentByTag(MediaFolderPolicyFragment.TAG)
            as? MediaFolderPolicyFragment
        val policyState = if (policy != null) "present" else "absent"
        SyncLog.info(this, "PolicyCrashDbg", "event=selectFolder path=$path policyFragment=$policyState")
        policy?.handleFolderSelectedFromPicker(path)
    }

    override fun selectFolders(paths: List<String>) {
        val clean = paths.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        val policy = supportFragmentManager.findFragmentByTag(MediaFolderPolicyFragment.TAG)
            as? MediaFolderPolicyFragment
        val policyState = if (policy != null) "present" else "absent"
        SyncLog.info(this, "PolicyCrashDbg", "event=selectFolders count=${clean.size} policyFragment=$policyState")
        if (policy != null) {
            policy.handleFoldersSelectedFromPicker(clean)
        } else {
            selectFolder(clean[0])
        }
    }
}

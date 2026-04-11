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

class MediaFolderPolicyActivity : AppCompatActivity(), FolderSelectorCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
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
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.media_folder_policy_container,
                RemoteFolderPickerFragment.newInstance(remote, this, ""),
            )
            .addToBackStack(null)
            .commit()
    }

    override fun selectFolder(path: String) {
        val policy = supportFragmentManager.findFragmentByTag(MediaFolderPolicyFragment.TAG)
            as? MediaFolderPolicyFragment
        policy?.handleFolderSelectedFromPicker(path)
    }
}

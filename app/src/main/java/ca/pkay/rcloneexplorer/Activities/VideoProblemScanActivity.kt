package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import ca.pkay.rcloneexplorer.Fragments.FolderSelectorCallback
import ca.pkay.rcloneexplorer.Fragments.RemoteFolderPickerFragment
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Settings.VideoProblemScanFragment
import ca.pkay.rcloneexplorer.util.ActivityHelper

class VideoProblemScanActivity : AppCompatActivity(), FolderSelectorCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_problem_scan)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.video_problem_scan_container,
                    VideoProblemScanFragment(),
                    VideoProblemScanFragment.TAG,
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
                R.id.video_problem_scan_container,
                RemoteFolderPickerFragment.newInstance(remote, this, "", false),
            )
            .addToBackStack(null)
            .commit()
    }

    override fun selectFolder(path: String) {
        val fragment = supportFragmentManager.findFragmentByTag(VideoProblemScanFragment.TAG)
            as? VideoProblemScanFragment
        fragment?.handleFolderSelectedFromPicker(path)
    }

    override fun selectFolders(paths: List<String>) {
        val first = paths.map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        selectFolder(first)
    }
}

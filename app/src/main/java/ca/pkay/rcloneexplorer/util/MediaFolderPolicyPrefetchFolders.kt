package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences
import ca.pkay.rcloneexplorer.Items.RemoteItem

/**
 * One thumbnail-enabled media policy folder to prefetch (non-recursive listing).
 */
data class PolicyPrefetchFolder(
    val remoteName: String,
    val explorerDirectoryPath: String,
    val policyRelativePath: String,
)

/**
 * Enumerates all thumbnail-enabled policy folders across remotes for bulk prefetch.
 */
object MediaFolderPolicyPrefetchFolders {

    @JvmStatic
    fun enumerate(
        remotes: List<RemoteItem>,
        prefs: SharedPreferences,
    ): List<PolicyPrefetchFolder> {
        val out = ArrayList<PolicyPrefetchFolder>()
        for (remote in remotes) {
            if (remote.isRemoteType(RemoteItem.SAFW)) {
                continue
            }
            val rows = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
                .filter { it.thumbnail }
            for (row in rows) {
                val relative = MediaFolderPolicy.normalizeFolder(row.path)
                out.add(
                    PolicyPrefetchFolder(
                        remoteName = remote.name,
                        explorerDirectoryPath = policyFolderToExplorerPath(remote.name, relative),
                        policyRelativePath = relative,
                    ),
                )
            }
        }
        out.sortWith(compareBy({ it.remoteName }, { it.policyRelativePath }))
        return out
    }

    @JvmStatic
    fun policyFolderToExplorerPath(remoteName: String, policyRelativePath: String): String {
        val normalized = MediaFolderPolicy.normalizeFolder(policyRelativePath)
        return if (normalized == "/" || normalized.isEmpty()) {
            "//$remoteName"
        } else {
            "//$remoteName$normalized"
        }
    }
}

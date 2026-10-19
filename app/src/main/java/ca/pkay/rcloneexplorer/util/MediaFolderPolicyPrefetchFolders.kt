package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences
import ca.pkay.rcloneexplorer.Items.RemoteItem

/**
 * One media-policy folder to prefetch (non-recursive listing).
 */
data class PolicyPrefetchFolder(
    val remoteName: String,
    val explorerDirectoryPath: String,
    val policyRelativePath: String,
)

/**
 * Enumerates media policy folders across remotes for bulk prefetch (thumbnail or cache).
 */
object MediaFolderPolicyPrefetchFolders {

    @JvmStatic
    @JvmOverloads
    fun enumerate(
        remotes: List<RemoteItem>,
        prefs: SharedPreferences,
        type: PolicyType = PolicyType.THUMBNAIL,
    ): List<PolicyPrefetchFolder> {
        val out = ArrayList<PolicyPrefetchFolder>()
        for (remote in remotes) {
            if (remote.isRemoteType(RemoteItem.SAFW)) {
                continue
            }
            val rows = MediaFolderPolicy.readPolicyRows(prefs, remote.name)
                .filter { if (type == PolicyType.CACHE) it.cache else it.thumbnail }
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

    /**
     * Path passed to [ca.pkay.rcloneexplorer.Rclone.getDirectoryContent]: root uses
     * {@code //remoteName}; nested folders use plain relative paths (e.g. {@code Video Archive/Anime}),
     * matching [ca.pkay.rcloneexplorer.Fragments.FileExplorerFragment] navigation.
     */
    @JvmStatic
    fun policyFolderToExplorerPath(remoteName: String, policyRelativePath: String): String {
        val normalized = MediaFolderPolicy.normalizeFolder(policyRelativePath)
        return if (normalized == "/" || normalized.isEmpty()) {
            "//$remoteName"
        } else {
            normalized.removePrefix("/")
        }
    }
}

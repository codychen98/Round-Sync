package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R

/**
 * One row from a non-recursive directory listing, sufficient for thumbnail prefetch selection
 * without constructing [FileItem] (which pulls Android APIs unsuitable for plain JVM unit tests).
 */
data class PrefetchListingEntry(
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val isDir: Boolean,
)

/**
 * Selects files in a single directory listing that should receive HTTP thumbnail prefetch
 * (same rules as [ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter] counts).
 */
object ThumbnailPrefetchTargets {

    @JvmStatic
    fun readShowThumbnails(prefs: SharedPreferences, context: Context): Boolean {
        return prefs.getBoolean(context.getString(R.string.pref_key_show_thumbnails), true)
    }

    @JvmStatic
    fun readThumbnailSizeLimitBytes(prefs: SharedPreferences, context: Context): Long {
        return prefs.getLong(
            context.getString(R.string.pref_key_thumbnail_size_limit),
            context.resources.getInteger(R.integer.default_thumbnail_size_limit).toLong(),
        )
    }

    @JvmStatic
    fun filterPrefetchListingEntries(
        entries: List<PrefetchListingEntry>,
        remote: RemoteItem,
        prefs: SharedPreferences,
        sizeLimitBytes: Long,
        showThumbnails: Boolean,
    ): List<PrefetchListingEntry> {
        if (!showThumbnails || remote.isRemoteType(RemoteItem.SAFW)) {
            return emptyList()
        }
        val allowed = MediaFolderPolicy.readAllowedFolders(prefs, remote.name, PolicyType.THUMBNAIL)
        val out = ArrayList<PrefetchListingEntry>()
        for (e in entries) {
            if (e.isDir) {
                continue
            }
            val mime = e.mimeType
            val eligible = (mime.startsWith("image/") && e.size <= sizeLimitBytes) ||
                mime.startsWith("video/")
            if (!eligible) {
                continue
            }
            if (!isHttpThumbnailPolicyAllowedForPath(remote, remote.name, e.path, allowed)) {
                continue
            }
            out.add(e)
        }
        return out
    }

    @JvmStatic
    fun filterForHttpThumbnailPrefetch(
        items: List<FileItem>,
        remote: RemoteItem,
        prefs: SharedPreferences,
        sizeLimitBytes: Long,
        showThumbnails: Boolean,
    ): List<FileItem> {
        val entries = items.map { item ->
            PrefetchListingEntry(
                path = item.path,
                name = item.name,
                size = item.size,
                mimeType = item.mimeType ?: "",
                isDir = item.isDir,
            )
        }
        val kept = filterPrefetchListingEntries(entries, remote, prefs, sizeLimitBytes, showThumbnails)
        val keys = kept.map { rowKey(it.path, it.name) }.toHashSet()
        return items.filter { rowKey(it.path, it.name) in keys }
    }

    private fun rowKey(path: String, name: String): String {
        return path + '\u0000' + name
    }

    private fun isHttpThumbnailPolicyAllowedForPath(
        listingRemote: RemoteItem,
        remoteName: String,
        filePath: String,
        allowed: Set<String>,
    ): Boolean {
        if (listingRemote.isRemoteType(RemoteItem.SAFW)) {
            return true
        }
        if (!MediaFolderPolicy.shouldApplyAllowListGating(listingRemote)) {
            return true
        }
        val pathForPolicy = MediaFolderPolicy.explorerPathForPolicyCheck(remoteName, filePath)
        return MediaFolderPolicy.isPathAllowed(listingRemote, remoteName, pathForPolicy, allowed)
    }

    /**
     * Removes entries whose [BlacklistKey] is present in the failure blacklist.
     *
     * Loads the blacklist StringSet once per call for efficiency. The core filtering is delegated
     * to [filterByBlacklistSet] so it can be exercised in plain-JVM unit tests without
     * constructing [FileItem] (which carries Android API dependencies).
     *
     * @param items      candidates after policy filtering
     * @param prefs      SharedPreferences that holds [ThumbnailFailureBlacklist.PREF_KEY]
     * @param remoteName rclone remote name; used as [BlacklistKey.remoteName]
     */
    @JvmStatic
    fun filterOutBlacklisted(
        items: List<FileItem>,
        prefs: SharedPreferences,
        remoteName: String,
    ): List<FileItem> {
        val blacklistSet = prefs.getStringSet(ThumbnailFailureBlacklist.PREF_KEY, null)
            ?: return items
        return filterByBlacklistSet(items, blacklistSet) { item ->
            BlacklistKey(
                remoteName = remoteName,
                remoteRelativePath = item.path,
                sizeBytes = item.size,
                mtimeEpochMs = item.modTime,
            )
        }
    }

    /**
     * Generic filter that removes items whose [BlacklistKey] encoding appears in [blacklistSet].
     * Exposed internally so tests can drive it with simple synthetic keys instead of [FileItem].
     */
    internal fun <T> filterByBlacklistSet(
        items: List<T>,
        blacklistSet: Set<String>,
        keyOf: (T) -> BlacklistKey,
    ): List<T> {
        if (blacklistSet.isEmpty()) return items
        return items.filter { item -> !blacklistSet.contains(keyOf(item).encode()) }
    }

    @JvmStatic
    fun buildThumbnailHttpUrl(hiddenPath: String, serverPort: Int, item: FileItem): String {
        return buildThumbnailHttpUrl(hiddenPath, serverPort, item.path)
    }

    @JvmStatic
    fun buildThumbnailHttpUrl(hiddenPath: String, serverPort: Int, remoteFilePath: String): String {
        val builder = android.net.Uri.parse("http://127.0.0.1:$serverPort")
            .buildUpon()
            .appendEncodedPath(hiddenPath)
        for (seg in remoteFilePath.split("/")) {
            if (seg.isNotEmpty()) {
                builder.appendPath(seg)
            }
        }
        return builder.build().toString()
    }

    @JvmStatic
    fun hiddenServePath(authToken: String, remoteName: String): String {
        return "$authToken/$remoteName"
    }

    @JvmStatic
    fun readStartAtRoot(prefs: SharedPreferences, context: Context): Boolean {
        val goToDefault = prefs.getBoolean(context.getString(R.string.pref_key_go_to_default_set), false)
        if (!goToDefault) {
            return false
        }
        return prefs.getBoolean(context.getString(R.string.pref_key_start_at_root), false)
    }
}

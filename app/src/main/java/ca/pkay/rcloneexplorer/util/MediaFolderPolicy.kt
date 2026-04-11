package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences
import ca.pkay.rcloneexplorer.Items.RemoteItem
import io.github.x0b.safdav.file.SafConstants
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json

/**
 * Per-remote media folder allow-list for thumbnails and cache (see Roadmap/feature.md).
 * SAFW remotes skip allow-list gating. Empty stored set means unrestricted (legacy behavior).
 */
enum class PolicyType {
    THUMBNAIL,
    CACHE,
}

object MediaFolderPolicy {

    private const val KEY_PREFIX_THUMB = "media_policy_thumb_v1_"
    private const val KEY_PREFIX_CACHE = "media_policy_cache_v1_"
    private const val KEY_PREFIX_BUNDLE = "media_policy_bundle_v1_"

    private val policyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun preferenceKey(remoteName: String, type: PolicyType): String {
        val segment = encodeRemoteSegment(remoteName)
        return when (type) {
            PolicyType.THUMBNAIL -> KEY_PREFIX_THUMB + segment
            PolicyType.CACHE -> KEY_PREFIX_CACHE + segment
        }
    }

    fun bundlePreferenceKey(remoteName: String): String {
        return KEY_PREFIX_BUNDLE + encodeRemoteSegment(remoteName)
    }

    /**
     * Structured rows (path + independent flags). Reads JSON [bundlePreferenceKey] first;
     * if absent, merges legacy per-type [StringSet] keys from A.1.
     */
    fun readPolicyRows(prefs: SharedPreferences, remoteName: String): List<MediaFolderPolicyRow> {
        val bundleKey = bundlePreferenceKey(remoteName)
        val json = prefs.getString(bundleKey, null)
        if (!json.isNullOrBlank()) {
            val decoded = decodeBundleOrNull(json)
            if (decoded != null) {
                return normalizeAndSortRows(decoded)
            }
        }
        return rowsFromLegacyStringSets(prefs, remoteName)
    }

    /**
     * Persists rows as JSON and removes legacy StringSet keys so a single source of truth remains.
     */
    fun writePolicyRows(
        editor: SharedPreferences.Editor,
        remoteName: String,
        rows: List<MediaFolderPolicyRow>,
    ) {
        val normalized = normalizeAndSortRows(rows)
        val payload = policyJson.encodeToString(
            MediaFolderPolicyBundle.serializer(),
            MediaFolderPolicyBundle(v = 1, rows = normalized),
        )
        editor.putString(bundlePreferenceKey(remoteName), payload)
        editor.remove(preferenceKey(remoteName, PolicyType.THUMBNAIL))
        editor.remove(preferenceKey(remoteName, PolicyType.CACHE))
    }

    fun readAllowedFolders(
        prefs: SharedPreferences,
        remoteName: String,
        type: PolicyType,
    ): Set<String> {
        return readPolicyRows(prefs, remoteName)
            .filter { row -> if (type == PolicyType.THUMBNAIL) row.thumbnail else row.cache }
            .map { normalizeFolder(it.path) }
            .toSet()
    }

    /**
     * Updates one policy column while preserving the other flags (requires current prefs for merge).
     */
    fun writeAllowedFolders(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        remoteName: String,
        type: PolicyType,
        folders: Set<String>,
    ) {
        val byPath = readPolicyRows(prefs, remoteName)
            .associateBy { normalizeFolder(it.path) }
            .toMutableMap()
        val newSet = folders.map { normalizeFolder(it) }.toSet()
        val touched = byPath.keys.toMutableSet()
        touched.addAll(newSet)
        for (p in touched) {
            val prev = byPath[p]
            val thumb = if (type == PolicyType.THUMBNAIL) {
                newSet.contains(p)
            } else {
                prev?.thumbnail == true
            }
            val cache = if (type == PolicyType.CACHE) {
                newSet.contains(p)
            } else {
                prev?.cache == true
            }
            if (!thumb && !cache) {
                byPath.remove(p)
            } else {
                byPath[p] = MediaFolderPolicyRow(path = p, thumbnail = thumb, cache = cache)
            }
        }
        writePolicyRows(editor, remoteName, byPath.values.toList())
    }

    /**
     * When false, callers should treat paths as allowed without checking the folder set.
     */
    fun shouldApplyAllowListGating(remote: RemoteItem): Boolean {
        return remote.typeReadable != SafConstants.SAF_REMOTE_NAME
    }

    /**
     * True when gating applies and the user has selected at least one folder (restricted mode).
     */
    fun isRestrictedMode(remote: RemoteItem, allowedFolders: Set<String>): Boolean {
        return shouldApplyAllowListGating(remote) && allowedFolders.isNotEmpty()
    }

    fun isPathAllowed(
        remote: RemoteItem,
        remoteName: String,
        explorerOrRelativePath: String,
        allowedFolders: Set<String>,
    ): Boolean {
        if (!shouldApplyAllowListGating(remote)) {
            return true
        }
        if (allowedFolders.isEmpty()) {
            return true
        }
        val relative = toRemoteRelativePath(remoteName, explorerOrRelativePath)
        return matchesFolder(allowedFolders, relative)
    }

    /**
     * Normalizes explorer / lsjson paths (same strings as [ca.pkay.rcloneexplorer.Items.FileItem.getPath])
     * before [isPathAllowed]. Listings at the remote root often omit the `//remoteName` prefix; this prepends
     * it when missing so allow-list rows match. Paths that already start with `//` are left unchanged.
     * Crypt remotes use the same shape as other remotes. Grid and list modes share one bind path, so both
     * use the same [FileItem] paths.
     */
    fun explorerPathForPolicyCheck(remoteName: String, fileItemPath: String): String {
        val t = fileItemPath.trim()
        if (t.isEmpty()) {
            return "//$remoteName"
        }
        val root = "//$remoteName"
        if (t == root || t == "$root/" || t.startsWith("$root/")) {
            return t
        }
        if (t.startsWith("//")) {
            return t
        }
        val suffix = t.trimStart('/')
        return "$root/$suffix"
    }

    fun normalizeFolder(path: String): String {
        var s = path.trim().replace('\\', '/')
        if (s.isEmpty()) {
            return "/"
        }
        while (s.startsWith("//")) {
            s = s.substring(1)
        }
        if (!s.startsWith("/")) {
            s = "/$s"
        }
        s = s.replace(Regex("/+"), "/")
        if (s.length > 1 && s.endsWith("/")) {
            s = s.dropLastWhile { it == '/' }
        }
        return s
    }

    /**
     * Converts explorer paths (`//remote`, `//remote/foo/bar`) to a remote-relative path (`/`, `/foo/bar`).
     * If [explorerPath] does not start with `//[remoteName]`, it is treated as already relative and normalized.
     */
    fun toRemoteRelativePath(remoteName: String, explorerPath: String): String {
        val root = "//$remoteName"
        val p = explorerPath.trim()
        if (p == root || p == "$root/") {
            return "/"
        }
        if (p.startsWith("$root/")) {
            return normalizeFolder(p.substring(root.length))
        }
        return normalizeFolder(p)
    }

    /**
     * [allowedNormalizedFolders] must already be normalized; empty means unrestricted (caller should short-circuit).
     * A path matches if it equals a folder or lies under it (`/Photos` matches `/Photos` and `/Photos/kid.jpg`'s dir).
     */
    fun matchesFolder(allowedNormalizedFolders: Set<String>, candidateRelativePath: String): Boolean {
        if (allowedNormalizedFolders.isEmpty()) {
            return true
        }
        val candidate = normalizeFolder(candidateRelativePath)
        for (folder in allowedNormalizedFolders) {
            val prefix = normalizeFolder(folder)
            if (prefix == "/" || prefix.isEmpty()) {
                return true
            }
            if (candidate == prefix || candidate.startsWith("$prefix/")) {
                return true
            }
        }
        return false
    }

    /** URL- and SharedPreferences-safe; hex avoids Android/JVM Base64 differences in unit tests. */
    private fun encodeRemoteSegment(remoteName: String): String {
        val bytes = remoteName.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0f])
            }
        }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun decodeBundleOrNull(json: String): List<MediaFolderPolicyRow>? {
        return try {
            val bundle = policyJson.decodeFromString(MediaFolderPolicyBundle.serializer(), json)
            bundle.rows
        } catch (_: Exception) {
            null
        }
    }

    private fun rowsFromLegacyStringSets(
        prefs: SharedPreferences,
        remoteName: String,
    ): List<MediaFolderPolicyRow> {
        val thumb = prefs.getStringSet(preferenceKey(remoteName, PolicyType.THUMBNAIL), null)
            ?: emptySet()
        val cache = prefs.getStringSet(preferenceKey(remoteName, PolicyType.CACHE), null)
            ?: emptySet()
        val paths = LinkedHashSet<String>()
        thumb.mapTo(paths) { normalizeFolder(it) }
        cache.mapTo(paths) { normalizeFolder(it) }
        return paths.map { p ->
            MediaFolderPolicyRow(
                path = p,
                thumbnail = thumb.map { normalizeFolder(it) }.contains(p),
                cache = cache.map { normalizeFolder(it) }.contains(p),
            )
        }.sortedBy { it.path }
    }

    private fun normalizeAndSortRows(rows: List<MediaFolderPolicyRow>): List<MediaFolderPolicyRow> {
        val merged = LinkedHashMap<String, MediaFolderPolicyRow>()
        for (row in rows) {
            val p = normalizeFolder(row.path)
            val prev = merged[p]
            merged[p] = MediaFolderPolicyRow(
                path = p,
                thumbnail = (prev?.thumbnail == true) || row.thumbnail,
                cache = (prev?.cache == true) || row.cache,
            )
        }
        return merged.values.filter { it.thumbnail || it.cache }.sortedBy { it.path }
    }
}

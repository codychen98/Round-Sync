package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.R
import java.nio.charset.StandardCharsets

/**
 * Per-remote "path lock": when enabled, browsing or opening files under configured folder prefixes
 * (remote-relative, see [MediaFolderPolicy.normalizeFolder]) requires authentication.
 * Empty prefix set with enable=true locks the entire remote.
 */
object RemotePathLock {

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun hexEncodeRemote(remoteName: String): String {
        val bytes = remoteName.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0f])
            }
        }
    }

    @JvmStatic
    fun enabledPrefsKey(context: Context, remoteName: String): String {
        return context.getString(R.string.pref_key_remote_path_lock_enabled_prefix) +
            hexEncodeRemote(remoteName)
    }

    @JvmStatic
    fun prefixesPrefsKey(context: Context, remoteName: String): String {
        return context.getString(R.string.pref_key_remote_path_lock_prefixes_prefix) +
            hexEncodeRemote(remoteName)
    }

    /** True when any remote has path lock switched on (scans the enabled-key prefix; works before remotes are decrypted). */
    @JvmStatic
    fun anyRemoteLocked(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefix = context.getString(R.string.pref_key_remote_path_lock_enabled_prefix)
        return prefs.all.any { (key, value) -> key.startsWith(prefix) && value == true }
    }

    /**
     * When true, the user must authenticate (once per keyguard cycle) before listing or opening
     * under a protected path on this remote.
     */
    @JvmStatic
    fun isAuthenticationRequired(
        prefs: SharedPreferences,
        context: Context,
        remoteName: String,
        explorerPath: String,
    ): Boolean {
        if (!prefs.getBoolean(enabledPrefsKey(context, remoteName), false)) {
            return false
        }
        val raw = prefs.getStringSet(prefixesPrefsKey(context, remoteName), emptySet()) ?: emptySet()
        val normalizedPrefixes = raw.map { MediaFolderPolicy.normalizeFolder(it) }.toSet()
        val effectivePrefixes =
            normalizedPrefixes.filter { it != "/" && it.isNotEmpty() }.toSet()
        val explorerNorm =
            MediaFolderPolicy.explorerPathForPolicyCheck(remoteName, explorerPath)
        val relative = MediaFolderPolicy.toRemoteRelativePath(remoteName, explorerNorm)
        if (effectivePrefixes.isEmpty()) {
            return true
        }
        return MediaFolderPolicy.matchesFolder(effectivePrefixes, relative)
    }
}

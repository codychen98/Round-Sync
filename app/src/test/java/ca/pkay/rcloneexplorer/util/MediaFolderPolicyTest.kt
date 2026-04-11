package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.RemoteItem
import io.github.x0b.safdav.file.SafConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderPolicyTest {

    @Test
    fun normalizeFolder_trimsAndUsesLeadingSlash() {
        assertEquals("/", MediaFolderPolicy.normalizeFolder(""))
        assertEquals("/", MediaFolderPolicy.normalizeFolder("   "))
        assertEquals("/Photos", MediaFolderPolicy.normalizeFolder("Photos"))
        assertEquals("/Photos", MediaFolderPolicy.normalizeFolder("/Photos"))
        assertEquals("/Photos", MediaFolderPolicy.normalizeFolder("/Photos/"))
        assertEquals("/Photos/Family", MediaFolderPolicy.normalizeFolder("//Photos//Family//"))
    }

    @Test
    fun normalizeFolder_backslashBecomesSlash() {
        assertEquals("/a/b", MediaFolderPolicy.normalizeFolder("""\a\b"""))
    }

    @Test
    fun toRemoteRelativePath_rootAndNested() {
        assertEquals("/", MediaFolderPolicy.toRemoteRelativePath("myremote", "//myremote"))
        assertEquals("/", MediaFolderPolicy.toRemoteRelativePath("myremote", "//myremote/"))
        assertEquals(
            "/Photos",
            MediaFolderPolicy.toRemoteRelativePath("myremote", "//myremote/Photos"),
        )
        assertEquals(
            "/Photos/Family",
            MediaFolderPolicy.toRemoteRelativePath("myremote", "//myremote/Photos/Family"),
        )
    }

    @Test
    fun toRemoteRelativePath_alreadyRelative() {
        assertEquals("/DCIM", MediaFolderPolicy.toRemoteRelativePath("r", "DCIM"))
        assertEquals("/DCIM", MediaFolderPolicy.toRemoteRelativePath("r", "/DCIM"))
    }

    @Test
    fun matchesFolder_emptySet_unrestricted() {
        assertTrue(MediaFolderPolicy.matchesFolder(emptySet(), "/any/path"))
    }

    @Test
    fun matchesFolder_exactAndSubtree() {
        val allowed = setOf(MediaFolderPolicy.normalizeFolder("/Photos"))
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/Photos"))
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/Photos/child"))
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/Photos/child/x.jpg"))
        assertFalse(MediaFolderPolicy.matchesFolder(allowed, "/PhotosBackup"))
        assertFalse(MediaFolderPolicy.matchesFolder(allowed, "/PhotosBackup/x"))
    }

    @Test
    fun matchesFolder_multiplePrefixes() {
        val allowed = setOf(
            MediaFolderPolicy.normalizeFolder("/a"),
            MediaFolderPolicy.normalizeFolder("/b"),
        )
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/a/x"))
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/b"))
        assertFalse(MediaFolderPolicy.matchesFolder(allowed, "/c"))
    }

    @Test
    fun matchesFolder_rootPrefix_allowsAll() {
        val allowed = setOf("/")
        assertTrue(MediaFolderPolicy.matchesFolder(allowed, "/anything"))
    }

    @Test
    fun isPathAllowed_safwIgnoresAllowList() {
        val safw = RemoteItem("safw", SafConstants.SAF_REMOTE_NAME)
        val denied = setOf(MediaFolderPolicy.normalizeFolder("/only"))
        assertTrue(
            MediaFolderPolicy.isPathAllowed(
                safw,
                "safw",
                "//safw/other",
                denied,
            ),
        )
    }

    @Test
    fun isPathAllowed_nonSafwRestricted() {
        val drive = RemoteItem("gd", "drive")
        val allowed = setOf(MediaFolderPolicy.normalizeFolder("/Photos"))
        assertTrue(
            MediaFolderPolicy.isPathAllowed(drive, "gd", "//gd/Photos/a", allowed),
        )
        assertFalse(
            MediaFolderPolicy.isPathAllowed(drive, "gd", "//gd/Downloads", allowed),
        )
    }

    @Test
    fun isPathAllowed_emptyAllowList_unrestricted() {
        val drive = RemoteItem("gd", "drive")
        assertTrue(
            MediaFolderPolicy.isPathAllowed(drive, "gd", "//gd/Any", emptySet()),
        )
    }

    @Test
    fun explorerPathForPolicyCheck_rootPrefixAndRelative() {
        assertEquals("//vault", MediaFolderPolicy.explorerPathForPolicyCheck("vault", ""))
        assertEquals("//vault", MediaFolderPolicy.explorerPathForPolicyCheck("vault", "  "))
        assertEquals("//vault/x.bin", MediaFolderPolicy.explorerPathForPolicyCheck("vault", "x.bin"))
        assertEquals("//vault/Photos/a", MediaFolderPolicy.explorerPathForPolicyCheck("vault", "/Photos/a"))
        assertEquals("//vault/Photos/a", MediaFolderPolicy.explorerPathForPolicyCheck("vault", "//vault/Photos/a"))
    }

    @Test
    fun isPathAllowed_withExplorerPathForPolicyCheck_rootRelative() {
        val crypt = RemoteItem("vault", "crypt")
        val allowed = setOf(MediaFolderPolicy.normalizeFolder("/Secret"))
        assertTrue(
            MediaFolderPolicy.isPathAllowed(
                crypt,
                "vault",
                MediaFolderPolicy.explorerPathForPolicyCheck("vault", "Secret/photo.jpg"),
                allowed,
            ),
        )
        assertFalse(
            MediaFolderPolicy.isPathAllowed(
                crypt,
                "vault",
                MediaFolderPolicy.explorerPathForPolicyCheck("vault", "Public/a.jpg"),
                allowed,
            ),
        )
    }

    @Test
    fun preferenceKey_differsByTypeAndRemote() {
        val k1 = MediaFolderPolicy.preferenceKey("r1", PolicyType.THUMBNAIL)
        val k2 = MediaFolderPolicy.preferenceKey("r1", PolicyType.CACHE)
        val k3 = MediaFolderPolicy.preferenceKey("r2", PolicyType.THUMBNAIL)
        assertTrue(k1.startsWith("media_policy_thumb_v1_"))
        assertTrue(k2.startsWith("media_policy_cache_v1_"))
        assertEquals(k1, MediaFolderPolicy.preferenceKey("r1", PolicyType.THUMBNAIL))
        assertTrue(k1 != k2)
        assertTrue(k1 != k3)
    }

    @Test
    fun policyRows_roundTrip_viaJsonBundle() {
        val prefs = MemorySharedPreferences()
        val rows = listOf(
            MediaFolderPolicyRow("/Photos", thumbnail = true, cache = true),
            MediaFolderPolicyRow("/DCIM", thumbnail = false, cache = true),
        )
        val ed = prefs.edit()
        MediaFolderPolicy.writePolicyRows(ed, "remote1", rows)
        ed.commit()
        val read = MediaFolderPolicy.readPolicyRows(prefs, "remote1")
        assertEquals(2, read.size)
        assertEquals("/DCIM", read[0].path)
        assertEquals("/Photos", read[1].path)
        assertTrue(read[1].thumbnail && read[1].cache)
        assertFalse(read[0].thumbnail)
        assertTrue(read[0].cache)
    }

    @Test
    fun policyRows_legacyEmptyPrefs_noCrash() {
        val prefs = MemorySharedPreferences()
        assertTrue(MediaFolderPolicy.readPolicyRows(prefs, "any").isEmpty())
        assertTrue(MediaFolderPolicy.readAllowedFolders(prefs, "any", PolicyType.THUMBNAIL).isEmpty())
    }

    @Test
    fun policyRows_migratesLegacyStringSets() {
        val prefs = MemorySharedPreferences()
        prefs.edit()
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.THUMBNAIL),
                hashSetOf("/Photos", "/Screenshots"),
            )
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.CACHE),
                hashSetOf("/Photos"),
            )
            .commit()
        val rows = MediaFolderPolicy.readPolicyRows(prefs, "r")
        assertEquals(2, rows.size)
        val photos = rows.find { it.path == "/Photos" }!!
        assertTrue(photos.thumbnail && photos.cache)
        val shots = rows.find { it.path == "/Screenshots" }!!
        assertTrue(shots.thumbnail)
        assertFalse(shots.cache)
    }

    @Test
    fun policyRows_malformedBundleFallsBackToLegacy() {
        val prefs = MemorySharedPreferences()
        prefs.edit()
            .putString(MediaFolderPolicy.bundlePreferenceKey("r"), "{ not json")
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.THUMBNAIL),
                hashSetOf("/a"),
            )
            .commit()
        val rows = MediaFolderPolicy.readPolicyRows(prefs, "r")
        assertEquals(1, rows.size)
        assertEquals("/a", rows[0].path)
        assertTrue(rows[0].thumbnail)
        assertFalse(rows[0].cache)
    }

    @Test
    fun writeAllowedFolders_updatesOneColumnPreservesOther() {
        val prefs = MemorySharedPreferences()
        prefs.edit()
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.THUMBNAIL),
                hashSetOf("/Photos"),
            )
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.CACHE),
                hashSetOf("/Photos", "/CacheOnly"),
            )
            .commit()
        val ed = prefs.edit()
        MediaFolderPolicy.writeAllowedFolders(
            prefs,
            ed,
            "r",
            PolicyType.THUMBNAIL,
            setOf("/Screenshots"),
        )
        ed.commit()
        assertTrue(
            MediaFolderPolicy.readAllowedFolders(prefs, "r", PolicyType.THUMBNAIL)
                .contains("/Screenshots"),
        )
        assertFalse(
            MediaFolderPolicy.readAllowedFolders(prefs, "r", PolicyType.THUMBNAIL)
                .contains("/Photos"),
        )
        val cache = MediaFolderPolicy.readAllowedFolders(prefs, "r", PolicyType.CACHE)
        assertTrue(cache.contains("/Photos"))
        assertTrue(cache.contains("/CacheOnly"))
        assertFalse(cache.contains("/Screenshots"))
    }

    @Test
    fun writePolicyRows_removesLegacyKeys() {
        val prefs = MemorySharedPreferences()
        prefs.edit()
            .putStringSet(
                MediaFolderPolicy.preferenceKey("r", PolicyType.THUMBNAIL),
                hashSetOf("/x"),
            )
            .commit()
        val ed = prefs.edit()
        MediaFolderPolicy.writePolicyRows(
            ed,
            "r",
            listOf(MediaFolderPolicyRow("/x", thumbnail = true, cache = false)),
        )
        ed.commit()
        assertFalse(
            prefs.contains(MediaFolderPolicy.preferenceKey("r", PolicyType.THUMBNAIL)),
        )
        assertTrue(prefs.contains(MediaFolderPolicy.bundlePreferenceKey("r")))
    }
}

package ca.pkay.rcloneexplorer.Glide

import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.disklrucache.DiskLruCache
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator
import com.bumptech.glide.signature.ObjectKey
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The one [DiskLruCache] over the thumbnails directory, exposed both as Glide's [DiskCache]
 * and to the app's own probe / evict / store helpers.
 *
 * Glide's stock wrapper hides its [DiskLruCache]; the app previously opened a second instance
 * on the same directory, and either side rebuilding the journal silently dropped the other's
 * entries. Keeping a single instance removes that failure mode. Keys are hashed with Glide's
 * [SafeKeyGenerator], so disk keys are unchanged.
 */
class ThumbnailDiskCache(
    val directory: File,
    private val maxSizeBytes: Long,
) : DiskCache {

    private val safeKeys = SafeKeyGenerator()
    private var diskLruCache: DiskLruCache? = null

    @Synchronized
    @Throws(IOException::class)
    private fun open(): DiskLruCache =
        diskLruCache ?: DiskLruCache.open(directory, APP_VERSION, VALUE_COUNT, maxSizeBytes)
            .also { diskLruCache = it }

    fun safeKeyFor(key: Key): String = safeKeys.getSafeKey(key)

    override fun get(key: Key): File? = fileForSafeKey(safeKeyFor(key))

    fun fileForSafeKey(safeKey: String): File? = try {
        open().get(safeKey)?.getFile(0)
    } catch (e: IOException) {
        FLog.w(TAG, "get failed for %s", e, safeKey)
        null
    }

    /** Fast existence probe by readable label (what [ObjectKey] wraps for app-owned entries). */
    fun containsLabel(label: String): Boolean = fileForSafeKey(safeKeyFor(ObjectKey(label))) != null

    /** Glide semantics: a key that already has an entry is left untouched. */
    override fun put(key: Key, writer: DiskCache.Writer) {
        val safeKey = safeKeyFor(key)
        try {
            val cache = open()
            if (cache.get(safeKey) != null) {
                return
            }
            // A concurrent edit on the same key yields null; the other writer wins.
            val editor = cache.edit(safeKey) ?: return
            try {
                if (writer.write(editor.getFile(0))) {
                    editor.commit()
                }
            } finally {
                editor.abortUnlessCommitted()
            }
        } catch (e: IOException) {
            FLog.w(TAG, "put failed for %s", e, safeKey)
        }
    }

    /** Replaces (not just adds) the entry for [key]; Glide's [put] never overwrites. */
    fun store(key: Key, bytes: ByteArray) {
        delete(key)
        put(key) { file ->
            FileOutputStream(file).use { it.write(bytes) }
            true
        }
    }

    override fun delete(key: Key) {
        removeSafeKey(safeKeyFor(key))
    }

    fun removeSafeKey(safeKey: String): Boolean = try {
        open().remove(safeKey)
    } catch (e: IOException) {
        FLog.w(TAG, "remove failed for %s", e, safeKey)
        false
    }

    @Synchronized
    override fun clear() {
        try {
            open().delete()
        } catch (e: IOException) {
            FLog.w(TAG, "clear failed", e)
        } finally {
            diskLruCache = null
        }
    }

    /** Closes the journal; for tests and process teardown only, the cache reopens lazily. */
    @Synchronized
    fun close() {
        try {
            diskLruCache?.close()
        } catch (e: IOException) {
            FLog.w(TAG, "close failed", e)
        } finally {
            diskLruCache = null
        }
    }

    private companion object {
        const val TAG = "ThumbDiskCache"
        const val APP_VERSION = 1
        const val VALUE_COUNT = 1
    }
}

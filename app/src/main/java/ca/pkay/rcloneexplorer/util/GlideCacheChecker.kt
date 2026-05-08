package ca.pkay.rcloneexplorer.util

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Probes Glide's DATA disk cache to determine whether a model's data is already stored locally,
 * without triggering any network request if the data is absent.
 *
 * All methods must be called off the main thread; Glide enforces this for submit().get().
 */
object GlideCacheChecker {

    private const val DEFAULT_PROBE_TIMEOUT_MS = 250L

    /**
     * Returns true if [model]'s raw data bytes are present in Glide's DATA disk cache.
     * Returns false on any failure, timeout, or cache miss.
     *
     * [model] must be a type registered in the Glide registry (e.g. [ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl]
     * or [ca.pkay.rcloneexplorer.Glide.HttpServeThumbnailGlideUrl]).
     *
     * Must be called from a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun isInDataDiskCache(
        context: Context,
        model: Any,
        timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    ): Boolean {
        val target = Glide.with(context)
            .downloadOnly()
            .load(model)
            .onlyRetrieveFromCache(true)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .submit()
        return try {
            target.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: ExecutionException) {
            false
        } catch (_: TimeoutException) {
            target.cancel(true)
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/**
 * Splits [items] into a (toFetch, alreadyCached) pair by calling [isCached] for each item.
 * Items for which [isCached] returns true go into alreadyCached; all others go into toFetch.
 *
 * Extracted as a top-level function so the partition logic can be unit-tested independently
 * of Glide or Android framework classes.
 */
internal fun <T> partitionCachedItems(
    items: List<T>,
    isCached: (T) -> Boolean,
): Pair<List<T>, List<T>> {
    val toFetch = mutableListOf<T>()
    val alreadyCached = mutableListOf<T>()
    for (item in items) {
        if (isCached(item)) alreadyCached.add(item) else toFetch.add(item)
    }
    return toFetch to alreadyCached
}

package ca.pkay.rcloneexplorer.util

import androidx.annotation.VisibleForTesting
import java.util.Locale

/**
 * Selects media files from a folder listing for folder-tile collage cells.
 *
 * Does not generate thumbnails — callers must use already-cached file thumbs only.
 *
 * Priority: well-known cover filenames → images within [sizeLimitBytes] → videos.
 */
object FolderCoverResolver {

    enum class CoverKind {
        IMAGE,
        VIDEO,
    }

    data class Cover(
        val kind: CoverKind,
        val path: String,
        val name: String,
        val mimeType: String,
    )

    private val COVER_FILE_NAMES: Set<String> = setOf(
        "cover.jpg",
        "cover.jpeg",
        "cover.png",
        "cover.webp",
        "folder.jpg",
        "folder.jpeg",
        "folder.png",
        "folder.webp",
        "albumart.jpg",
        "album.jpg",
        "thumb.jpg",
        "thumbnail.jpg",
    )

    private const val DEFAULT_MAX_CANDIDATES = 24

    /**
     * Ordered collage candidates (not yet filtered by disk-cache presence).
     */
    @JvmStatic
    @JvmOverloads
    @VisibleForTesting
    fun pickCollageCandidates(
        entries: List<PrefetchListingEntry>,
        sizeLimitBytes: Long,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<Cover> {
        if (entries.isEmpty() || maxCandidates <= 0) {
            return emptyList()
        }
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) {
            return emptyList()
        }
        val out = ArrayList<Cover>(maxCandidates)
        val seenPaths = HashSet<String>()

        fun add(entry: PrefetchListingEntry, kind: CoverKind): Boolean {
            if (out.size >= maxCandidates) {
                return false
            }
            if (!seenPaths.add(entry.path)) {
                return true
            }
            out.add(Cover(kind, entry.path, entry.name, entry.mimeType))
            return out.size < maxCandidates
        }

        for (entry in files) {
            val lower = entry.name.lowercase(Locale.US)
            if (lower !in COVER_FILE_NAMES) {
                continue
            }
            val mime = entry.mimeType
            when {
                mime.startsWith("image/") && entry.size <= sizeLimitBytes -> {
                    if (!add(entry, CoverKind.IMAGE)) return out
                }
                mime.startsWith("video/") -> {
                    if (!add(entry, CoverKind.VIDEO)) return out
                }
            }
        }

        for (entry in files) {
            if (entry.mimeType.startsWith("image/") && entry.size <= sizeLimitBytes) {
                if (!add(entry, CoverKind.IMAGE)) return out
            }
        }

        for (entry in files) {
            if (entry.mimeType.startsWith("video/")) {
                if (!add(entry, CoverKind.VIDEO)) return out
            }
        }
        return out
    }

    /** Single-cover helper kept for tests / simple call sites. */
    @JvmStatic
    @VisibleForTesting
    fun pickCover(entries: List<PrefetchListingEntry>, sizeLimitBytes: Long): Cover? {
        return pickCollageCandidates(entries, sizeLimitBytes, maxCandidates = 1).firstOrNull()
    }
}

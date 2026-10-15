package ca.pkay.rcloneexplorer.util

import androidx.annotation.VisibleForTesting
import java.util.Locale

/**
 * Picks a cover file from a single non-recursive folder listing for folder-tile thumbnails.
 *
 * Priority: well-known cover filenames (case-insensitive) → first image within [sizeLimitBytes]
 * → first video (any size). Directories are ignored.
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

    @JvmStatic
    @VisibleForTesting
    fun pickCover(entries: List<PrefetchListingEntry>, sizeLimitBytes: Long): Cover? {
        if (entries.isEmpty()) {
            return null
        }
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) {
            return null
        }

        for (entry in files) {
            val lower = entry.name.lowercase(Locale.US)
            if (lower !in COVER_FILE_NAMES) {
                continue
            }
            val mime = entry.mimeType
            if (mime.startsWith("image/") && entry.size <= sizeLimitBytes) {
                return Cover(CoverKind.IMAGE, entry.path, entry.name, mime)
            }
            if (mime.startsWith("video/")) {
                return Cover(CoverKind.VIDEO, entry.path, entry.name, mime)
            }
        }

        val firstImage = files.firstOrNull {
            it.mimeType.startsWith("image/") && it.size <= sizeLimitBytes
        }
        if (firstImage != null) {
            return Cover(
                CoverKind.IMAGE,
                firstImage.path,
                firstImage.name,
                firstImage.mimeType,
            )
        }

        val firstVideo = files.firstOrNull { it.mimeType.startsWith("video/") }
        if (firstVideo != null) {
            return Cover(
                CoverKind.VIDEO,
                firstVideo.path,
                firstVideo.name,
                firstVideo.mimeType,
            )
        }
        return null
    }
}

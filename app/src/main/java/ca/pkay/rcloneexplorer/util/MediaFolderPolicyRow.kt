package ca.pkay.rcloneexplorer.util

import kotlinx.serialization.Serializable

@Serializable
data class MediaFolderPolicyRow(
    val path: String,
    val thumbnail: Boolean,
    val cache: Boolean,
)

@Serializable
data class MediaFolderPolicyBundle(
    val v: Int = 1,
    val rows: List<MediaFolderPolicyRow> = emptyList(),
)

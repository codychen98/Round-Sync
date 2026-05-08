package ca.pkay.rcloneexplorer.util

/**
 * Represents a single policy-flag change for one (remote, folder, type) triple.
 * [turningOn] = true means the flag went false→true; false means true→false.
 */
data class TransitionEvent(
    val remoteName: String,
    val folderPath: String,
    val type: PolicyType,
    val turningOn: Boolean,
)

/**
 * Pure-Kotlin diff helper for Media Folder Policy toggle events.
 * Kept separate so it is unit-testable without any Android framework dependencies.
 */
object MediaFolderPolicyTransitions {

    /**
     * Compares [prevRows] and [newRows] for [remoteName] and returns one [TransitionEvent]
     * per (folder, type) pair where the flag value actually changed.
     *
     * Rows not present in a list are treated as having both flags = false.
     */
    fun computeTransitions(
        remoteName: String,
        prevRows: List<MediaFolderPolicyRow>,
        newRows: List<MediaFolderPolicyRow>,
    ): List<TransitionEvent> {
        val prevByPath = prevRows.associateBy { MediaFolderPolicy.normalizeFolder(it.path) }
        val newByPath = newRows.associateBy { MediaFolderPolicy.normalizeFolder(it.path) }
        val allPaths = prevByPath.keys + newByPath.keys

        val events = mutableListOf<TransitionEvent>()
        for (path in allPaths) {
            val prev = prevByPath[path]
            val next = newByPath[path]

            val prevThumb = prev?.thumbnail ?: false
            val nextThumb = next?.thumbnail ?: false
            if (prevThumb != nextThumb) {
                events.add(TransitionEvent(remoteName, path, PolicyType.THUMBNAIL, nextThumb))
            }

            val prevCache = prev?.cache ?: false
            val nextCache = next?.cache ?: false
            if (prevCache != nextCache) {
                events.add(TransitionEvent(remoteName, path, PolicyType.CACHE, nextCache))
            }
        }
        return events
    }
}

package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.FilterEntry

/**
 * Builds and recognizes rclone filter rules from the task editor's folder picker.
 *
 * Shape: optional `- /path/**` excludes (deselected under a selected ancestor), then
 * `+ /path/**` includes, then a final `- **`. Excludes are listed first so rclone matches them
 * before a broader parent include.
 */
object SubfolderFilterRules {

    const val TITLE_PREFIX = "Subfolders: "
    private const val INCLUDE_SUFFIX = "/**"
    private const val EXCLUDE_ALL = "**"
    private const val GLOB_SPECIALS = "\\*?[]{}"

    data class ParsedFolderSelection(
        val selectedRoots: Set<String>,
        val excluded: Set<String>,
    )

    fun titleFor(remotePath: String): String = TITLE_PREFIX + remotePath

    fun escapeGlob(name: String): String = buildString {
        for (c in name) {
            if (c in GLOB_SPECIALS) {
                append('\\')
            }
            append(c)
        }
    }

    fun unescapeGlob(pattern: String): String = buildString {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < pattern.length) {
                append(pattern[i + 1])
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Escapes each path segment; [relativePath] uses `/` separators and no leading slash. */
    fun escapeRelativePath(relativePath: String): String {
        return relativePath.split('/').joinToString("/") { escapeGlob(it) }
    }

    fun unescapeRelativePath(pattern: String): String {
        return pattern.split('/').joinToString("/") { unescapeGlob(it) }
    }

    /**
     * Drops blank/invalid paths, then removes any path that is under another kept path
     * (e.g. keeps `(Hobbies)` and drops `(Hobbies)/Cameras`).
     */
    fun pruneRedundant(folderPaths: Collection<String>): List<String> {
        val normalized = folderPaths
            .map { normalizeRelativePath(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val byLength = normalized.sortedBy { it.length }
        val kept = ArrayList<String>()
        for (path in byLength) {
            val covered = kept.any { path == it || path.startsWith("$it/") }
            if (!covered) {
                kept.add(path)
            }
        }
        return kept.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /**
     * Builds filter rules from the checkbox selection.
     *
     * [knownPaths] should include every folder node the picker has listed. Known paths that sit
     * under a selected include root but are not themselves selected become exclude rules
     * (so unchecking subfolder C under checked A yields `- /A/C/**` then `+ /A/**`).
     */
    fun buildRules(
        selectedPaths: Collection<String>,
        knownPaths: Collection<String> = emptyList(),
    ): List<FilterEntry> {
        val selected = selectedPaths
            .map { normalizeRelativePath(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (selected.isEmpty()) {
            return emptyList()
        }
        val includeRoots = pruneRedundant(selected)
        val known = knownPaths
            .map { normalizeRelativePath(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val excludes = pruneRedundant(
            known.filter { path ->
                path !in selected && includeRoots.any { path.startsWith("$it/") }
            },
        )
        val rules = ArrayList<FilterEntry>(excludes.size + includeRoots.size + 1)
        for (path in excludes) {
            rules.add(
                FilterEntry(FilterEntry.FILTER_EXCLUDE, "/" + escapeRelativePath(path) + INCLUDE_SUFFIX),
            )
        }
        for (path in includeRoots) {
            rules.add(
                FilterEntry(FilterEntry.FILTER_INCLUDE, "/" + escapeRelativePath(path) + INCLUDE_SUFFIX),
            )
        }
        rules.add(FilterEntry(FilterEntry.FILTER_EXCLUDE, EXCLUDE_ALL))
        return rules
    }

    /**
     * Parses a generated picker filter into include roots and explicit mid-rule excludes.
     * Returns null for hand-made or invalid shapes so callers never overwrite user rules.
     */
    fun parseFolderSelection(filter: Filter?): ParsedFolderSelection? {
        val entries = filter?.getFilters() ?: return null
        if (entries.size < 2) {
            return null
        }
        val last = entries.last()
        if (last.filterType != FilterEntry.FILTER_EXCLUDE || last.filter != EXCLUDE_ALL) {
            return null
        }
        val excluded = LinkedHashSet<String>()
        val included = LinkedHashSet<String>()
        var seenInclude = false
        for (entry in entries.dropLast(1)) {
            val pattern = entry.filter ?: return null
            val relative = parseAnchoredRecursive(pattern) ?: return null
            when (entry.filterType) {
                FilterEntry.FILTER_EXCLUDE -> {
                    if (seenInclude) {
                        return null
                    }
                    excluded.add(relative)
                }
                FilterEntry.FILTER_INCLUDE -> {
                    seenInclude = true
                    included.add(relative)
                }
                else -> return null
            }
        }
        if (included.isEmpty()) {
            return null
        }
        return ParsedFolderSelection(
            selectedRoots = included,
            excluded = excluded,
        )
    }

    /** Selected include roots only (backward-compatible helper). */
    fun parseSelectedFolders(filter: Filter?): Set<String>? {
        return parseFolderSelection(filter)?.selectedRoots
    }

    fun isGenerated(filter: Filter?): Boolean = parseFolderSelection(filter) != null

    /** Ancestor relative paths of [relativePath], shortest first (excludes [relativePath] itself). */
    fun ancestorPaths(relativePath: String): List<String> {
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isEmpty() || !normalized.contains('/')) {
            return emptyList()
        }
        val segments = normalized.split('/')
        val out = ArrayList<String>(segments.size - 1)
        val soFar = StringBuilder()
        for (i in 0 until segments.lastIndex) {
            if (i > 0) {
                soFar.append('/')
            }
            soFar.append(segments[i])
            out.add(soFar.toString())
        }
        return out
    }

    fun normalizeRelativePath(raw: String): String {
        return raw.trim().trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    fun isUnderOrEqual(path: String, ancestor: String): Boolean {
        val p = normalizeRelativePath(path)
        val a = normalizeRelativePath(ancestor)
        return p == a || p.startsWith("$a/")
    }

    private fun parseAnchoredRecursive(pattern: String): String? {
        if (!pattern.startsWith("/") || !pattern.endsWith(INCLUDE_SUFFIX)) {
            return null
        }
        val middle = pattern.substring(1, pattern.length - INCLUDE_SUFFIX.length)
        val relative = normalizeRelativePath(unescapeRelativePath(middle))
        if (relative.isEmpty() || relative.split('/').any { it.isEmpty() }) {
            return null
        }
        return relative
    }
}

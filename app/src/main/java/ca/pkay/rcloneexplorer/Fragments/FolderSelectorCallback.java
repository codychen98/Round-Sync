package ca.pkay.rcloneexplorer.Fragments;

import java.util.List;

/**
 * Callback when the user confirms folder selection from {@link RemoteFolderPickerFragment}.
 */
public interface FolderSelectorCallback {

    void selectFolder(String path);

    /**
     * Confirms multiple folder paths in one action. Default implementation keeps legacy
     * single-path behaviour for hosts that only override {@link #selectFolder} (e.g. task flows):
     * forwards only the first non-empty path.
     */
    default void selectFolders(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) {
                selectFolder(path.trim());
                return;
            }
        }
    }
}

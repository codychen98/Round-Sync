package ca.pkay.rcloneexplorer.Items;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

public class PinnedItem {

    private static final String KEY_REMOTE = "remote";
    private static final String KEY_PATH   = "path";
    private static final String KEY_LABEL  = "label";

    private final String remoteName;
    private final String path;
    private final String displayLabel;

    public PinnedItem(@NonNull String remoteName, @NonNull String path, @Nullable String displayLabel) {
        this.remoteName   = remoteName;
        this.path         = path;
        this.displayLabel = displayLabel;
    }

    @NonNull
    public String getRemoteName() {
        return remoteName;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    @Nullable
    public String getDisplayLabel() {
        return displayLabel;
    }

    /**
     * Returns the display label if set, otherwise the last path segment,
     * otherwise the remote name.
     */
    @NonNull
    public String getEffectiveLabel() {
        if (displayLabel != null && !displayLabel.isEmpty()) {
            return displayLabel;
        }
        if (path != null && !path.isEmpty()) {
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int lastSlash = trimmed.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < trimmed.length() - 1) {
                return trimmed.substring(lastSlash + 1);
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return remoteName;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_REMOTE, remoteName);
        obj.put(KEY_PATH, path);
        if (displayLabel != null) {
            obj.put(KEY_LABEL, displayLabel);
        }
        return obj;
    }

    @NonNull
    public static PinnedItem fromJson(@NonNull JSONObject obj) throws JSONException {
        String remote = obj.getString(KEY_REMOTE);
        String path   = obj.optString(KEY_PATH, "");
        String label  = obj.has(KEY_LABEL) ? obj.getString(KEY_LABEL) : null;
        return new PinnedItem(remote, path, label);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PinnedItem)) return false;
        PinnedItem other = (PinnedItem) o;
        return Objects.equals(remoteName, other.remoteName)
                && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remoteName, path);
    }

    @NonNull
    @Override
    public String toString() {
        return "PinnedItem{remote='" + remoteName + "', path='" + path + "', label='" + displayLabel + "'}";
    }
}

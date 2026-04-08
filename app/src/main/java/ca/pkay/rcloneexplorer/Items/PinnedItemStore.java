package ca.pkay.rcloneexplorer.Items;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import ca.pkay.rcloneexplorer.util.FLog;

public class PinnedItemStore {

    private static final String TAG = "PinnedItemStore";
    private static final String PREF_KEY_V2  = "drawer_pinned_items_v2";
    private static final String PREF_KEY_V1  = "shared_preferences_drawer_pinned_remotes";

    private PinnedItemStore() {}

    /** Returns the ordered list of pinned items, running migration first if needed. */
    @NonNull
    public static List<PinnedItem> load(@NonNull Context context) {
        migrateIfNeeded(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String json = prefs.getString(PREF_KEY_V2, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<PinnedItem> items = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                items.add(PinnedItem.fromJson(array.getJSONObject(i)));
            }
            return items;
        } catch (JSONException e) {
            FLog.e(TAG, "load: failed to parse pinned items JSON", e);
            return new ArrayList<>();
        }
    }

    /** Persists the ordered list. */
    public static void save(@NonNull Context context, @NonNull List<PinnedItem> items) {
        JSONArray array = new JSONArray();
        for (PinnedItem item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException e) {
                FLog.e(TAG, "save: failed to serialize item " + item, e);
            }
        }
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY_V2, array.toString())
                .apply();
    }

    /**
     * Adds a new pin at the end of the list. No-op if already pinned
     * (same remoteName + path).
     */
    public static void addPin(@NonNull Context context,
                              @NonNull String remoteName,
                              @NonNull String path,
                              String displayLabel) {
        List<PinnedItem> items = load(context);
        PinnedItem candidate = new PinnedItem(remoteName, path, displayLabel);
        if (!items.contains(candidate)) {
            items.add(candidate);
            save(context, items);
        }
    }

    /** Removes the pin matching remoteName + path. No-op if not found. */
    public static void removePin(@NonNull Context context,
                                 @NonNull String remoteName,
                                 @NonNull String path) {
        List<PinnedItem> items = load(context);
        PinnedItem target = new PinnedItem(remoteName, path, null);
        if (items.remove(target)) {
            save(context, items);
        }
    }

    /** Returns true if a pin with the given remoteName + path exists. */
    public static boolean isPinned(@NonNull Context context,
                                   @NonNull String remoteName,
                                   @NonNull String path) {
        List<PinnedItem> items = load(context);
        return items.contains(new PinnedItem(remoteName, path, null));
    }

    /**
     * Moves the item at {@code fromPos} to {@code toPos} in the ordered list
     * and persists the result.
     */
    public static void reorder(@NonNull Context context, int fromPos, int toPos) {
        List<PinnedItem> items = load(context);
        if (fromPos < 0 || fromPos >= items.size()
                || toPos < 0 || toPos >= items.size()
                || fromPos == toPos) {
            return;
        }
        PinnedItem moved = items.remove(fromPos);
        items.add(toPos, moved);
        save(context, items);
    }

    /**
     * One-time migration from the old {@code Set<String>} format.
     * Reads pinned remote names, converts each to a root-level {@link PinnedItem},
     * writes the new format, and removes the old key.
     */
    public static void migrateIfNeeded(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains(PREF_KEY_V2)) {
            return;
        }
        Set<String> oldSet = prefs.getStringSet(PREF_KEY_V1, Collections.emptySet());
        if (oldSet == null || oldSet.isEmpty()) {
            return;
        }
        List<PinnedItem> migrated = new ArrayList<>(oldSet.size());
        for (String remoteName : oldSet) {
            String displayName = prefs.getString("remote_name_" + remoteName, null);
            migrated.add(new PinnedItem(remoteName, "", displayName));
        }
        save(context, migrated);
        prefs.edit().remove(PREF_KEY_V1).apply();
        FLog.d(TAG, "migrateIfNeeded: migrated " + migrated.size() + " pinned remotes to v2 format");
    }
}

package ca.pkay.rcloneexplorer.Database.json;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.DARK;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.FOLLOW_SYSTEM;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.LIGHT;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ca.pkay.rcloneexplorer.Items.PinnedItemStore;
import ca.pkay.rcloneexplorer.R;

public class SharedPreferencesBackup {

    private static final String MEDIA_FOLDER_POLICY_PREF_PREFIX = "media_policy_";
    private static final String JSON_MEDIA_FOLDER_POLICY_ENTRIES = "mediaFolderPolicyEntries";
    /** Field on each exported entry */
    private static final String ENTRY_KEY_FIELD = "key";
    /** "string" or "stringSet" */
    private static final String ENTRY_KIND_FIELD = "kind";
    private static final String KIND_STRING = "string";
    private static final String KIND_STRING_SET = "stringSet";

    public static String export(Context context) throws JSONException {

        //General Settings
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean showThumbnails = sharedPreferences.getBoolean(context.getString(R.string.pref_key_show_thumbnails), true);
        boolean isWifiOnly = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wifi_only_transfers), false);
        boolean allowWhileIdle = sharedPreferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);
        boolean useProxy = sharedPreferences.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        String proxyProtocol = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
        String proxyHost = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
        int proxyPort = sharedPreferences.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
        String proxyUser = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_username), "");

        // File Access
        boolean safEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_saf), false);
        boolean refreshLaEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_refresh_local_aliases), true);
        boolean vcpEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false);
        boolean vcpDeclareLocal = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_declare_local), true);
        boolean vcpGrantAll = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_grant_all), false);

        // Look and Feel
        int oldTheme = sharedPreferences.getInt(context.getString(R.string.pref_key_theme_old), FOLLOW_SYSTEM);
        String newTheme = sharedPreferences.getString(context.getString(R.string.pref_key_theme), String.valueOf(oldTheme));
        boolean isWrapFilenames = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wrap_filenames), true);

        // Notifications
        boolean appUpdates = sharedPreferences.getBoolean(context.getString(R.string.pref_key_app_updates), false);

        // Logging
        boolean useLogs = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);


        JSONObject main = new JSONObject();

        main.put("showThumbnails", showThumbnails);
        main.put("isWifiOnly", isWifiOnly);
        main.put("allowWhileIdle", allowWhileIdle);
        main.put("useProxy", useProxy);
        main.put("proxyProtocol", proxyProtocol);
        main.put("proxyHost", proxyHost);
        main.put("proxyPort", proxyPort);
        main.put("proxyUser", proxyUser);
        main.put("safEnabled", safEnabled);
        main.put("refreshLaEnabled", refreshLaEnabled);
        main.put("vcpEnabled", vcpEnabled);
        main.put("vcpDeclareLocal", vcpDeclareLocal);
        main.put("vcpGrantAll", vcpGrantAll);
        main.put("isDarkTheme", newTheme);
        main.put("isWrapFilenames", isWrapFilenames);
        main.put("appUpdates", appUpdates);
        main.put("useLogs", useLogs);

        String pinnedItemsV2 = PinnedItemStore.serializedPinnedItemsForBackup(context);
        if (pinnedItemsV2 != null) {
            main.put("pinnedItemsV2", pinnedItemsV2);
        }

        JSONArray mediaPolicyEntries = new JSONArray();
        for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(MEDIA_FOLDER_POLICY_PREF_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            JSONObject row = new JSONObject();
            row.put(ENTRY_KEY_FIELD, key);
            if (value instanceof String) {
                row.put(ENTRY_KIND_FIELD, KIND_STRING);
                row.put("value", value);
                mediaPolicyEntries.put(row);
            } else if (value instanceof Set) {
                JSONArray valuesArr = new JSONArray();
                for (Object o : (Set<?>) value) {
                    valuesArr.put(o != null ? String.valueOf(o) : "");
                }
                row.put(ENTRY_KIND_FIELD, KIND_STRING_SET);
                row.put("values", valuesArr);
                mediaPolicyEntries.put(row);
            }
        }
        main.put(JSON_MEDIA_FOLDER_POLICY_ENTRIES, mediaPolicyEntries);

        return main.toString();
    }

    public static void importJson(String json, Context context) throws JSONException {
        JSONObject jsonObject = new JSONObject(json);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        //General Settings
        editor.putBoolean(context.getString(R.string.pref_key_show_thumbnails), jsonObject.getBoolean("showThumbnails"));
        editor.putBoolean(context.getString(R.string.pref_key_wifi_only_transfers), jsonObject.getBoolean("isWifiOnly"));
        editor.putBoolean(context.getString(R.string.pref_key_use_proxy), jsonObject.getBoolean("useProxy"));
        editor.putBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), jsonObject.optBoolean("allowWhileIdle", false));
        editor.putString(context.getString(R.string.pref_key_proxy_protocol), jsonObject.getString("proxyProtocol"));
        editor.putString(context.getString(R.string.pref_key_proxy_host), jsonObject.getString("proxyHost"));
        editor.putInt(context.getString(R.string.pref_key_proxy_port), jsonObject.getInt("proxyPort"));
        editor.putString(context.getString(R.string.pref_key_proxy_username), jsonObject.getString("proxyUser"));

        // File Access
        editor.putBoolean(context.getString(R.string.pref_key_enable_saf), jsonObject.getBoolean("safEnabled"));
        editor.putBoolean(context.getString(R.string.pref_key_refresh_local_aliases), jsonObject.getBoolean("refreshLaEnabled"));
        editor.putBoolean(context.getString(R.string.pref_key_enable_vcp), jsonObject.getBoolean("vcpEnabled"));
        editor.putBoolean(context.getString(R.string.pref_key_vcp_declare_local), jsonObject.getBoolean("vcpDeclareLocal"));
        editor.putBoolean(context.getString(R.string.pref_key_vcp_grant_all), jsonObject.getBoolean("vcpGrantAll"));

        // Look and Feel
        // The type changed. So we try to use boolean first, and if it fails we use the proper int
        Object darkTheme = jsonObject.get("isDarkTheme");
        int valueForTheme;
        if (darkTheme instanceof String) {
            valueForTheme = Integer.parseInt((String) darkTheme);
        } else {
           if((boolean) darkTheme) {
               valueForTheme = DARK;
           } else {
               valueForTheme = LIGHT;
           }
            editor.putString(context.getString(R.string.pref_key_theme), String.valueOf(valueForTheme));
        }

        editor.putString(context.getString(R.string.pref_key_theme), String.valueOf(valueForTheme));

        editor.putBoolean(context.getString(R.string.pref_key_wrap_filenames), jsonObject.getBoolean("isWrapFilenames"));

        // Notifications
        editor.putBoolean(context.getString(R.string.pref_key_app_updates), jsonObject.getBoolean("appUpdates"));

        // Logging
        editor.putBoolean(context.getString(R.string.pref_key_logs), jsonObject.getBoolean("useLogs"));

        importMediaFolderPolicyIfPresent(sharedPreferences, editor, jsonObject);

        if (jsonObject.has("pinnedItemsV2") && !jsonObject.isNull("pinnedItemsV2")) {
            PinnedItemStore.restorePinnedItemsFromBackup(context, jsonObject.optString("pinnedItemsV2"));
        }

        editor.apply();
    }

    private static void importMediaFolderPolicyIfPresent(
            SharedPreferences prefs,
            SharedPreferences.Editor editor,
            JSONObject jsonObject) throws JSONException {
        if (!jsonObject.has(JSON_MEDIA_FOLDER_POLICY_ENTRIES)) {
            return;
        }
        JSONArray arr = jsonObject.getJSONArray(JSON_MEDIA_FOLDER_POLICY_ENTRIES);
        Set<String> allKeys = prefs.getAll().keySet();
        for (String key : allKeys) {
            if (key != null && key.startsWith(MEDIA_FOLDER_POLICY_PREF_PREFIX)) {
                editor.remove(key);
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            String key = row.getString(ENTRY_KEY_FIELD);
            if (key.isEmpty() || !key.startsWith(MEDIA_FOLDER_POLICY_PREF_PREFIX)) {
                continue;
            }
            String kind = row.optString(ENTRY_KIND_FIELD, KIND_STRING);
            if (KIND_STRING_SET.equals(kind)) {
                JSONArray values = row.optJSONArray("values");
                if (values == null) {
                    continue;
                }
                HashSet<String> set = new HashSet<>();
                for (int j = 0; j < values.length(); j++) {
                    Object v = values.get(j);
                    if (v != null) {
                        set.add(String.valueOf(v));
                    }
                }
                editor.putStringSet(key, set);
            } else {
                String value = row.optString("value", null);
                if (value != null) {
                    editor.putString(key, value);
                }
            }
        }
    }

}

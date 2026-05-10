package ca.pkay.rcloneexplorer.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import ca.pkay.rcloneexplorer.R;

/**
 * Copyright (C) 2021  Felix Nüsse
 * Created on 20.06.21 - 15:30
 *
 * Edited by: Felix Nüsse felix.nuesse(at)t-online.de
 *
 */

public class SyncLog {

    private static int loglength = 4;

    /** One session log file per process when {@link #isFileLoggingEnabled} is true; lazily created on first write. */
    private static volatile File sessionLogFile;

    /**
     * Whether JSON session logs are written to disk (same preference as Logging settings and verbose rclone).
     * Off by default.
     */
    public static boolean isFileLoggingEnabled(Context context) {
        Context app = context.getApplicationContext();
        return PreferenceManager.getDefaultSharedPreferences(app)
                .getBoolean(app.getString(R.string.pref_key_logs), false);
    }

    @SuppressLint("SimpleDateFormat")
    private static String buildReadableLogFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM_dd_yyyy_h_mm_a", Locale.US);
        String stamp = sdf.format(new Date());
        stamp = stamp.replace(':', '_').replace(' ', '_');
        return "log_" + stamp + ".log";
    }

    private static File createUniqueSessionLogFile(File directory) {
        String name = buildReadableLogFileName();
        File candidate = new File(directory, name);
        int suffix = 0;
        while (candidate.exists()) {
            suffix++;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            candidate = new File(directory, base + "_" + suffix + ext);
        }
        return candidate;
    }

    /**
     * Resolves directory {@code Context#getExternalFilesDir(null)}'s parent (e.g.
     * {@code .../Android/data/de.felixnuesse.extract}), or falls back to internal storage when external is unavailable.
     * No file is created until the first {@link #log} when file logging is enabled.
     */
    public static synchronized void startSession(Context context) {
        if (sessionLogFile != null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!isFileLoggingEnabled(app)) {
            return;
        }
        sessionLogFile = createUniqueSessionLogFile(resolveSessionLogDirectory(app));
    }

    private static File resolveSessionLogDirectory(Context context) {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null) {
            File parent = externalFiles.getParentFile();
            if (parent != null) {
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    Log.w(SyncLog.class.getSimpleName(), "Could not mkdir session log dir: " + parent);
                }
                return parent;
            }
        }
        return context.getFilesDir();
    }

    private static File getLogFile(Context c) {
        Context app = c.getApplicationContext();
        if (!isFileLoggingEnabled(app)) {
            return null;
        }
        if (sessionLogFile == null) {
            synchronized (SyncLog.class) {
                if (sessionLogFile == null) {
                    startSession(c);
                }
            }
        }
        return sessionLogFile;
    }

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static ArrayList<JSONObject> getLog(Context c){
        File log = getLogFile(c);
        if (log == null || !log.exists()) {
            return new ArrayList<>();
        }
        StringBuilder file = new StringBuilder();
        try {
            char[] buffer = new char[4096];
            InputStream inputStream = new FileInputStream(log);
            Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
                file.append(buffer, 0, numRead);
            }
        } catch (IOException e) {
            Log.e(SyncLog.class.getSimpleName(), "getLog read failed", e);
        }

        String lines[] = file.toString().split("\\r?\\n");

        ArrayList<JSONObject> jsons = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            try {
                jsons.add(new JSONObject(lines[i]));
            } catch (JSONException e) {
            }
        }
        Collections.reverse(jsons);
        return jsons;
    }

    private static void appendLog(Context c, String entry){
        if (!isFileLoggingEnabled(c.getApplicationContext())) {
            return;
        }
        File log = getLogFile(c);
        if (log == null) {
            return;
        }
        try {
            FileWriter writer = new FileWriter(log, true);
            writer.append(System.lineSeparator());
            writer.append(entry);
            writer.flush();
            writer.close();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, content);
            json.put(TITLE, title);
            json.put(TYPE, type);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        appendLog(c, json.toString());
        return now;
    }

    public static long error(Context c, String title, String content){
        return log(c, title, content, TYPE_ERROR);
    }

    public static long info(Context c, String title, String content){
        return log(c, title, content, TYPE_INFO);
    }

    public static void delete(Context c){
        File log = sessionLogFile;
        if (log == null) {
            return;
        }
        if (log.exists() && !log.delete()) {
            Log.w(SyncLog.class.getSimpleName(), "Could not delete log file: " + log.getAbsolutePath());
        }
        sessionLogFile = null;
    }

}

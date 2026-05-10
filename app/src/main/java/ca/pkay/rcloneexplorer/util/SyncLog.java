package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.util.Log;

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
import java.util.ArrayList;
import java.util.Collections;

/**
 * Copyright (C) 2021  Felix Nüsse
 * Created on 20.06.21 - 15:30
 *
 * Edited by: Felix Nüsse felix.nuesse(at)t-online.de
 *
 */

public class SyncLog {

    private static int loglength = 4;

    /** One log file per app process (Millis-based filename prefix log_) under Android/data Package dir. Lazily assigned if {@link #startSession} never ran. */
    private static volatile File sessionLogFile;

    /**
     * Resolves directory {@code Context#getExternalFilesDir(null)}'s parent (e.g.
     * {@code .../Android/data/de.felixnuesse.extract}), or falls back to internal storage when external is unavailable.
     */
    public static synchronized void startSession(Context context) {
        if (sessionLogFile != null) {
            return;
        }
        Context app = context.getApplicationContext();
        sessionLogFile = new File(resolveSessionLogDirectory(app),
                "log_" + System.currentTimeMillis() + ".log");
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

        File log = getLogFile(c);
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
        File log = getLogFile(c);
        if (log.exists() && !log.delete()) {
            Log.w(SyncLog.class.getSimpleName(), "Could not delete log file: " + log.getAbsolutePath());
        }
        sessionLogFile = null;
    }

}

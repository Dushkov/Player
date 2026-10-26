package com.brouken.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Subtitles saved per film/episode so they load by themselves the next time it is played.
 * One .srt per key plus index.json {key: {file, name, at}}; the least recently used entries beyond the limit are dropped.
 */
final class BgSubsLibrary {

    static final class Entry {
        final String key;
        final String name;
        final File file;

        Entry(String key, String name, File file) {
            this.key = key;
            this.name = name;
            this.file = file;
        }
    }

    private final File dir;
    private final File indexFile;
    private final int maxEntries;

    BgSubsLibrary(File dir, int maxEntries) {
        this.dir = dir;
        this.indexFile = new File(dir, "index.json");
        this.maxEntries = maxEntries;
    }

    synchronized void save(String key, String name, byte[] data, long now) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        JSONObject index = readIndex();
        remove(index, key);
        String fileName = sha1(key) + ".srt";
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            out.write(data);
        }
        try {
            index.put(key, new JSONObject().put("file", fileName).put("name", name).put("at", now));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        while (index.length() > maxEntries) {
            remove(index, oldestKey(index));
        }
        writeIndex(index);
    }

    /** The saved subtitle for this key, or null when there is none (or its file is gone). */
    synchronized Entry find(String key) {
        JSONObject entry = readIndex().optJSONObject(key);
        if (entry == null) {
            return null;
        }
        File file = new File(dir, entry.optString("file"));
        return file.isFile() ? new Entry(key, entry.optString("name"), file) : null;
    }

    /** Marks an entry as just used, so the size limit drops others first. */
    synchronized void touch(String key, long now) {
        JSONObject index = readIndex();
        JSONObject entry = index.optJSONObject(key);
        if (entry == null) {
            return;
        }
        try {
            entry.put("at", now);
            writeIndex(index);
        } catch (JSONException | IOException ignored) {
            // only affects which entry the limit drops first
        }
    }

    synchronized void forget(String key) {
        JSONObject index = readIndex();
        remove(index, key);
        try {
            writeIndex(index);
        } catch (IOException ignored) {
            // the file itself is already deleted
        }
    }

    private void remove(JSONObject index, String key) {
        JSONObject entry = index.optJSONObject(key);
        if (entry != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, entry.optString("file")).delete();
            index.remove(key);
        }
    }

    private static String oldestKey(JSONObject index) {
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Iterator<String> it = index.keys(); it.hasNext(); ) {
            String k = it.next();
            long at = index.optJSONObject(k) == null ? 0 : index.optJSONObject(k).optLong("at");
            if (at < oldestAt) {
                oldestAt = at;
                oldest = k;
            }
        }
        return oldest;
    }

    private JSONObject readIndex() {
        try {
            return indexFile.isFile() ? new JSONObject(readText(indexFile)) : new JSONObject();
        } catch (IOException | JSONException e) {
            return new JSONObject();  // a broken index only forgets saved subtitles
        }
    }

    // plain streams, not the NIO Files API: that needs API 26 and minSdk is 24
    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeIndex(JSONObject index) throws IOException {
        File tmp = new File(dir, "index.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(indexFile)) {
            throw new IOException("cannot write " + indexFile);
        }
    }

    private static String sha1(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

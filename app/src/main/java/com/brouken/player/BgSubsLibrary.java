package com.brouken.player;

import java.io.File;
import java.io.IOException;

/** Subtitles saved per film/episode so they load by themselves the next time it is played. */
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

    BgSubsLibrary(File dir, int maxEntries) {
    }

    synchronized void save(String key, String name, byte[] data, long now) throws IOException {
        throw new UnsupportedOperationException();
    }

    synchronized Entry find(String key) {
        throw new UnsupportedOperationException();
    }

    synchronized void touch(String key, long now) {
        throw new UnsupportedOperationException();
    }

    synchronized void forget(String key) {
        throw new UnsupportedOperationException();
    }
}

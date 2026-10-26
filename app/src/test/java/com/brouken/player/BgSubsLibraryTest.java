package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BgSubsLibraryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BgSubsLibrary library(int max) {
        return new BgSubsLibrary(new File(tmp.getRoot(), "lib"), max);
    }

    private static byte[] bytes(String s) throws Exception {
        return s.getBytes("UTF-8");
    }

    @Test
    public void saveThenFind() throws Exception {
        BgSubsLibrary lib = library(10);
        lib.save("black doves s01e02", "Black.Doves.S01E02.srt", bytes("1\n00:00:01,000 --> 00:00:02,000\nЗдравей\n"), 1);
        BgSubsLibrary.Entry e = lib.find("black doves s01e02");
        assertNotNull(e);
        assertEquals("Black.Doves.S01E02.srt", e.name);
        assertArrayEquals(bytes("1\n00:00:01,000 --> 00:00:02,000\nЗдравей\n"), Files.readAllBytes(e.file.toPath()));
    }

    @Test
    public void unknownKeyIsNull() {
        assertNull(library(10).find("nothing"));
    }

    @Test
    public void saveReplacesPreviousForSameKey() throws Exception {
        BgSubsLibrary lib = library(10);
        lib.save("k", "old.srt", bytes("old"), 1);
        lib.save("k", "new.srt", bytes("new"), 2);
        BgSubsLibrary.Entry e = lib.find("k");
        assertEquals("new.srt", e.name);
        assertArrayEquals(bytes("new"), Files.readAllBytes(e.file.toPath()));
        assertEquals(1, srtFiles());
    }

    @Test
    public void forgetRemovesEntryAndFile() throws Exception {
        BgSubsLibrary lib = library(10);
        lib.save("k", "a.srt", bytes("a"), 1);
        lib.forget("k");
        assertNull(lib.find("k"));
        assertEquals(0, srtFiles());
    }

    @Test
    public void deletedFileMeansNotFound() throws Exception {
        BgSubsLibrary lib = library(10);
        lib.save("k", "a.srt", bytes("a"), 1);
        lib.find("k").file.delete();
        assertNull(lib.find("k"));
    }

    @Test
    public void oldestIsDroppedOverTheLimit() throws Exception {
        BgSubsLibrary lib = library(2);
        lib.save("a", "a.srt", bytes("a"), 1);
        lib.save("b", "b.srt", bytes("b"), 2);
        lib.save("c", "c.srt", bytes("c"), 3);
        assertNull(lib.find("a"));
        assertNotNull(lib.find("b"));
        assertNotNull(lib.find("c"));
        assertEquals(2, srtFiles());
    }

    @Test
    public void touchKeepsAnEntryRecent() throws Exception {
        BgSubsLibrary lib = library(2);
        lib.save("a", "a.srt", bytes("a"), 1);
        lib.save("b", "b.srt", bytes("b"), 2);
        lib.touch("a", 3);
        lib.save("c", "c.srt", bytes("c"), 4);
        assertNotNull(lib.find("a"));
        assertNull(lib.find("b"));
    }

    @Test
    public void survivesNewInstance() throws Exception {
        library(10).save("k", "a.srt", bytes("a"), 1);
        assertEquals("a.srt", library(10).find("k").name);
    }

    private int srtFiles() {
        File[] files = new File(tmp.getRoot(), "lib").listFiles((dir, name) -> name.endsWith(".srt"));
        return files == null ? 0 : files.length;
    }
}

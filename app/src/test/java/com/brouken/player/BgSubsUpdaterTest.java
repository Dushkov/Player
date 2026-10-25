package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import org.junit.Test;

public class BgSubsUpdaterTest {

    // Trimmed GET https://api.github.com/repos/Dushkov/Player/releases/latest
    static final String LATEST_JSON = "{\"tag_name\":\"bg-v0.219.18\",\"name\":\"Just Player BG 0.219-bg.18\","
            + "\"assets\":["
            + "{\"name\":\"other.txt\",\"browser_download_url\":\"https://github.com/x/other.txt\",\"size\":3},"
            + "{\"name\":\"JustPlayerBG.apk\",\"size\":45145566,"
            + "\"browser_download_url\":\"https://github.com/Dushkov/Player/releases/download/bg-v0.219.18/JustPlayerBG.apk\"}"
            + "]}";

    @Test
    public void versionCodeFromTag() {
        assertEquals(Long.valueOf(219018), BgSubsUpdater.versionCodeFromTag("bg-v0.219.18"));
        assertEquals(Long.valueOf(220001), BgSubsUpdater.versionCodeFromTag("bg-v0.220.1"));
    }

    @Test
    public void unknownTagHasNoVersion() {
        assertNull(BgSubsUpdater.versionCodeFromTag("v0.219"));
        assertNull(BgSubsUpdater.versionCodeFromTag(null));
    }

    @Test
    public void parseLatestPicksTheApkAsset() throws Exception {
        BgSubsUpdater.Release r = BgSubsUpdater.parseLatest(LATEST_JSON);
        assertEquals(219018L, r.versionCode);
        assertEquals("0.219-bg.18", r.versionName);
        assertEquals("https://github.com/Dushkov/Player/releases/download/bg-v0.219.18/JustPlayerBG.apk", r.apkUrl);
        assertEquals(45145566L, r.size);
    }

    @Test
    public void parseLatestWithoutApkFails() {
        assertThrows(Exception.class, () -> BgSubsUpdater.parseLatest(
                "{\"tag_name\":\"bg-v0.219.18\",\"name\":\"x\",\"assets\":[]}"));
    }

    @Test
    public void newerOnlyWhenVersionCodeIsHigher() throws Exception {
        BgSubsUpdater.Release r = BgSubsUpdater.parseLatest(LATEST_JSON);
        assertTrue(r.isNewerThan(219016));
        assertFalse(r.isNewerThan(219018));
        assertFalse(r.isNewerThan(219020));
    }

    // same guard as BgSubsQueryTest: Android's ICU regex rejects (?U)/(?u)
    @Test
    public void patternsAvoidFlagsUnsupportedOnAndroid() throws Exception {
        for (Field f : BgSubsUpdater.class.getDeclaredFields()) {
            if (f.getType() == Pattern.class && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Pattern p = (Pattern) f.get(null);
                assertFalse(f.getName(), p.pattern().matches("(?s)^\\(\\?[a-zA-Z]*[Uu].*"));
                assertFalse(f.getName(), (p.flags() & (Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE)) != 0);
            }
        }
    }
}

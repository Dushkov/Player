package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import org.junit.Test;

public class BgSubsQueryTest {

    private static void assertQuery(String input, String title, Integer year, Integer season, Integer episode) {
        BgSubsQuery q = BgSubsQuery.parse(input);
        assertEquals("title of " + input, title, q.title);
        assertEquals("year of " + input, year, q.year);
        assertEquals("season of " + input, season, q.season);
        assertEquals("episode of " + input, episode, q.episode);
    }

    @Test
    public void yearAsLastWord() {
        assertQuery("Inception 2010", "Inception", 2010, null, null);
    }

    @Test
    public void yearInParentheses() {
        assertQuery("Inception (2010)", "Inception", 2010, null, null);
    }

    @Test
    public void releaseNameWithEpisode() {
        assertQuery("Breaking.Bad.S01E02.720p.HDTV.x264-CTU", "Breaking Bad", null, 1, 2);
    }

    @Test
    public void episodeAsNxNN() {
        assertQuery("Breaking Bad 1x02", "Breaking Bad", null, 1, 2);
    }

    @Test
    public void lowercaseShortEpisode() {
        assertQuery("breaking bad s1e2", "breaking bad", null, 1, 2);
    }

    @Test
    public void seasonWord() {
        assertQuery("Breaking Bad Season 3", "Breaking Bad", null, 3, null);
    }

    @Test
    public void bulgarianSeasonWord() {
        assertQuery("Игра на тронове Сезон 2", "Игра на тронове", null, 2, null);
    }

    @Test
    public void releaseNameWithYear() {
        assertQuery("Titanic.1997.1080p.BluRay", "Titanic", 1997, null, null);
    }

    @Test
    public void numberTitleReadAsYear() {
        assertQuery("Blade Runner 2049", "Blade Runner", 2049, null, null);
    }

    @Test
    public void numberOnlyTitleIsNotYear() {
        assertQuery("2012", "2012", null, null, null);
    }

    @Test
    public void withoutYearKeepsTheNumber() {
        BgSubsQuery fallback = BgSubsQuery.parse("Blade Runner 2049").withoutYear("Blade Runner 2049");
        assertEquals("Blade Runner 2049", fallback.title);
        assertEquals(null, fallback.year);
    }

    @Test
    public void withoutYearDropsReleaseWords() {
        BgSubsQuery fallback = BgSubsQuery.parse("Titanic.1997.1080p.BluRay").withoutYear("Titanic.1997.1080p.BluRay");
        assertEquals("Titanic 1997", fallback.title);
    }

    @Test
    public void blankInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> BgSubsQuery.parse("   "));
    }

    @Test
    public void onlyReleaseWordsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BgSubsQuery.parse("720p.x264"));
    }

    // Android's regex engine (ICU) rejects the (?U) / (?u) inline flags that the JVM accepts,
    // so a pattern using them crashes the app on the TV while every JVM unit test passes.
    @Test
    public void patternsAvoidFlagsUnsupportedOnAndroid() throws Exception {
        int checked = 0;
        for (Field f : BgSubsQuery.class.getDeclaredFields()) {
            if (f.getType() == Pattern.class && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Pattern p = (Pattern) f.get(null);
                assertFalse(f.getName() + " uses an inline U/u flag", p.pattern().matches("(?s)^\\(\\?[a-zA-Z]*[Uu].*"));
                assertFalse(f.getName() + " uses UNICODE_* flags",
                        (p.flags() & (Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE)) != 0);
                checked++;
            }
        }
        assertFalse("no patterns found", checked == 0);
    }

    @Test
    public void capitalizedBulgarianSeasonWord() {
        assertQuery("Игра на тронове СЕЗОН 3", "Игра на тронове", null, 3, null);
    }

    @Test
    public void tivimateEpisodeTitleIsSplitIntoFields() {
        assertQuery("Black Doves - S01E02 - A Little Black Dove", "Black Doves", null, 1, 2);
    }

    @Test
    public void fromFieldsTrimsAndTreatsEmptyAsUnset() {
        BgSubsQuery q = BgSubsQuery.fromFields("  Black Doves ", " 1", "2 ", "");
        assertEquals("Black Doves", q.title);
        assertEquals(Integer.valueOf(1), q.season);
        assertEquals(Integer.valueOf(2), q.episode);
        assertEquals(null, q.year);
    }

    @Test
    public void fromFieldsMovie() {
        BgSubsQuery q = BgSubsQuery.fromFields("Inception", "", "", "2010");
        assertEquals(Integer.valueOf(2010), q.year);
        assertEquals(null, q.season);
    }

    @Test
    public void fromFieldsRejectsBlankTitleAndBadNumbers() {
        assertThrows(IllegalArgumentException.class, () -> BgSubsQuery.fromFields(" ", "1", "2", ""));
        assertThrows(IllegalArgumentException.class, () -> BgSubsQuery.fromFields("X", "a", "", ""));
        assertThrows(IllegalArgumentException.class, () -> BgSubsQuery.fromFields("X", "", "-1", ""));
    }

    @Test
    public void aliasKeyIgnoresCaseAndPunctuation() {
        assertEquals("black doves", BgSubsQuery.aliasKey("Black Doves"));
        assertEquals("black doves", BgSubsQuery.aliasKey("  black-doves! "));
        assertEquals("игра на тронове", BgSubsQuery.aliasKey("Игра на Тронове"));
    }

    @Test
    public void toFieldTextShowsEmptyForUnset() {
        BgSubsQuery q = BgSubsQuery.parse("Inception 2010");
        assertEquals("", BgSubsQuery.fieldText(q.season));
        assertEquals("2010", BgSubsQuery.fieldText(q.year));
    }

    @Test
    public void libraryKeyIdentifiesTheVideo() {
        assertEquals("black doves s01e02", BgSubsQuery.libraryKey(BgSubsQuery.parse("Black Doves - S01E02 - A Little Black Dove")));
        assertEquals("inception 2010", BgSubsQuery.libraryKey(BgSubsQuery.parse("Inception (2010)")));
        assertEquals("inception", BgSubsQuery.libraryKey(BgSubsQuery.parse("Inception")));
        assertEquals("breaking bad s03", BgSubsQuery.libraryKey(BgSubsQuery.parse("Breaking Bad Season 3")));
    }
}

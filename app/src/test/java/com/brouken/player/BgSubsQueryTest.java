package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
}

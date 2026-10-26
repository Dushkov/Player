package com.brouken.player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a free-text title such as "Breaking Bad S01E02" or "Inception 2010" into search fields. */
final class BgSubsQuery {

    // No (?U)/(?u) flags and no \b: Android's ICU regex rejects those flags (crash), and without them \b is
    // ASCII-only on the JVM. Explicit letter/digit lookarounds behave the same on both, Cyrillic included.
    private static final String START = "(?<![\\p{L}\\p{N}])";
    private static final String END = "(?![\\p{L}\\p{N}])";
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)" + START + "(?:s(\\d{1,2})\\s?e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))" + END);
    private static final Pattern SEASON = Pattern.compile(
            "(?i)" + START + "(?:s(\\d{1,2})|season\\s*(\\d{1,2})|[Сс][Ее][Зз][Оо][Нн]\\s*(\\d{1,2}))" + END);
    private static final Pattern RELEASE_WORD = Pattern.compile(
            "(?i)" + START + "(?:480p|720p|1080p|2160p|web-?dl|webrip|hdtv|bluray|bdrip|dvdrip|x264|x265|hevc)" + END);
    private static final Pattern YEAR = Pattern.compile("^\\(?((?:19|20)\\d\\d)\\)?$");

    final String title;
    final Integer year;
    final Integer season;
    final Integer episode;

    BgSubsQuery(String title, Integer year, Integer season, Integer episode) {
        this.title = title;
        this.year = year;
        this.season = season;
        this.episode = episode;
    }

    static BgSubsQuery parse(String input) {
        String text = cutAtReleaseWord(separatorsToSpaces(input));
        Integer season = null;
        Integer episode = null;

        Matcher m = EPISODE.matcher(text);
        if (m.find()) {
            season = Integer.valueOf(m.group(1) != null ? m.group(1) : m.group(3));
            episode = Integer.valueOf(m.group(2) != null ? m.group(2) : m.group(4));
            text = text.substring(0, m.start());
        } else {
            m = SEASON.matcher(text);
            if (m.find()) {
                String s = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                season = Integer.valueOf(s);
                text = text.substring(0, m.start());
            }
        }

        Integer year = null;
        String title = clean(text);
        if (season == null) {
            int lastSpace = title.lastIndexOf(' ');
            if (lastSpace > 0) {
                Matcher y = YEAR.matcher(title.substring(lastSpace + 1));
                if (y.matches()) {
                    year = Integer.valueOf(y.group(1));
                    title = clean(title.substring(0, lastSpace));
                }
            }
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("empty title");
        }
        return new BgSubsQuery(title, year, season, episode);
    }

    /** Query from the search form fields; empty fields are "not set". */
    static BgSubsQuery fromFields(String title, String season, String episode, String year) {
        String t = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty title");
        }
        return new BgSubsQuery(t, number(year), number(season), number(episode));
    }

    /** Key for remembering a corrected series/film name: case and punctuation do not matter. */
    static String aliasKey(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String fieldText(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static Integer number(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            int n = Integer.parseInt(t);
            if (n < 0) {
                throw new IllegalArgumentException("negative number " + t);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + t);
        }
    }

    /** The same search with the whole typed title (a number like "2049" stays in it) and no year. */
    BgSubsQuery withoutYear(String originalInput) {
        String full = clean(cutAtReleaseWord(separatorsToSpaces(originalInput)));
        return new BgSubsQuery(full.isEmpty() ? title : full, null, season, episode);
    }

    private static String separatorsToSpaces(String input) {
        return input == null ? "" : input.replace('.', ' ').replace('_', ' ');
    }

    private static String cutAtReleaseWord(String text) {
        Matcher m = RELEASE_WORD.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    private static String clean(String text) {
        return text.replaceAll("\\s+", " ").replaceAll("^[\\s\\-:(]+|[\\s\\-:(]+$", "").trim();
    }
}

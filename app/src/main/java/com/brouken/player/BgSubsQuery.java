package com.brouken.player;

/** Splits a free-text title such as "Breaking Bad S01E02" or "Inception 2010" into search fields. */
final class BgSubsQuery {

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
        throw new UnsupportedOperationException("not implemented");
    }

    BgSubsQuery withoutYear(String originalInput) {
        throw new UnsupportedOperationException("not implemented");
    }
}

package com.brouken.player;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;

/** Info screen data from bgsubs GET /info (stub). */
final class BgSubsInfo {
    String kind, title, titleBg, year, imdbRating, awards, plot, plotLang, genres, runtime, director, poster, imdbUrl;
    Integer tmdbId, imdbVotes;
    final List<Person> cast = new ArrayList<>();
    final List<Person> guestCast = new ArrayList<>();
    final List<Similar> similar = new ArrayList<>();
    Episode episode;

    static BgSubsInfo parse(String json) throws JSONException { return new BgSubsInfo(); }
    String heading() { return null; }
    String originalTitle() { return null; }
    String metaLine() { return null; }
    String ratingLine() { return null; }
    String awardsLine() { return null; }
    String directorLine() { return null; }
    boolean plotInEnglish() { return false; }
    String episodeLine() { return null; }
    String episodeDetail() { return null; }
    static String votes(int n) { return null; }

    static final class Person { Integer id; String name, photo; }

    static final class Similar {
        String kind, title, year, poster;
        int id;
        String caption() { return null; }
    }

    static final class Episode { int season, episode; String title, titleBg, imdbRating, runtime; }

    static final class Bio {
        int id;
        String name, photo, birthday, deathday, placeOfBirth, bio, bioLang;
        static Bio parse(String json) throws JSONException { return new Bio(); }
        String lifeLine(int year, int month, int day) { return null; }
        String placeLine() { return null; }
        boolean bioInEnglish() { return false; }
    }
}

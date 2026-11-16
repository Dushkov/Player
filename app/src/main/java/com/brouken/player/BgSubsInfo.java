package com.brouken.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Info screen data from bgsubs GET /info (rating and awards from IMDb via OMDb; the rest from TMDB). */
final class BgSubsInfo {
    String kind, title, titleBg, year, imdbRating, awards, plot, plotLang, genres, runtime, director, poster, imdbUrl;
    Integer tmdbId, imdbVotes;
    final List<Person> cast = new ArrayList<>();
    final List<Person> guestCast = new ArrayList<>();
    final List<Similar> similar = new ArrayList<>();
    Episode episode;

    static BgSubsInfo parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        BgSubsInfo i = new BgSubsInfo();
        i.kind = str(o, "kind");
        i.tmdbId = integer(o, "tmdb_id");
        i.title = str(o, "title");
        i.titleBg = str(o, "title_bg");
        i.year = str(o, "year");
        i.imdbRating = str(o, "imdb_rating");
        i.imdbVotes = integer(o, "imdb_votes");
        i.awards = str(o, "awards");
        i.plot = str(o, "plot");
        i.plotLang = str(o, "plot_lang");
        i.genres = str(o, "genres");
        i.runtime = str(o, "runtime");
        i.director = str(o, "director");
        i.poster = str(o, "poster");
        i.imdbUrl = str(o, "imdb_url");
        people(o.optJSONArray("cast"), i.cast);
        people(o.optJSONArray("guest_cast"), i.guestCast);
        JSONArray sim = o.optJSONArray("similar");
        for (int n = 0; sim != null && n < sim.length(); n++) {
            JSONObject s = sim.getJSONObject(n);
            Integer id = integer(s, "id");
            if (id == null) continue;
            Similar item = new Similar();
            item.kind = "tv".equals(str(s, "kind")) ? "tv" : "movie";
            item.id = id;
            item.title = str(s, "title");
            item.year = str(s, "year");
            item.poster = str(s, "poster");
            i.similar.add(item);
        }
        JSONObject ep = o.optJSONObject("episode");
        if (ep != null) {
            Episode e = new Episode();
            e.season = ep.optInt("season");
            e.episode = ep.optInt("episode");
            e.title = str(ep, "title");
            e.titleBg = str(ep, "title_bg");
            e.imdbRating = str(ep, "imdb_rating");
            e.runtime = str(ep, "runtime");
            i.episode = e;
        }
        return i;
    }

    /** The big title: Bulgarian when known. */
    String heading() {
        if (titleBg != null) return titleBg;
        return title != null ? title : "";
    }

    /** The original title under the Bulgarian one; null when the heading already is the original. */
    String originalTitle() {
        return titleBg != null ? title : null;
    }

    /** "2010 · 2 ч 28 мин · Екшън, Приключения". */
    String metaLine() {
        return joinParts(year, runtime, genres);
    }

    /** "★ IMDb 8,8 (2,6 млн. гласа)". */
    String ratingLine() {
        if (imdbRating == null) return null;
        String line = "★ IMDb " + rating(imdbRating);
        return imdbVotes == null ? line : line + " (" + votes(imdbVotes) + ")";
    }

    String awardsLine() {
        return awards == null ? null : "🏆 " + awards;
    }

    String directorLine() {
        return director == null ? null : "Режисьор: " + director;
    }

    boolean plotInEnglish() {
        return "en".equals(plotLang);
    }

    /** "Сезон 1, серия 2: Малката черна гълъбица (A Little Black Dove)". */
    String episodeLine() {
        if (episode == null) return null;
        String line = "Сезон " + episode.season + ", серия " + episode.episode;
        String name;
        if (episode.titleBg != null) {
            name = episode.title != null ? episode.titleBg + " (" + episode.title + ")" : episode.titleBg;
        } else {
            name = episode.title;
        }
        return name == null ? line : line + ": " + name;
    }

    /** "★ IMDb 7,8 · 52 мин". */
    String episodeDetail() {
        if (episode == null) return null;
        return joinParts(episode.imdbRating == null ? null : "★ IMDb " + rating(episode.imdbRating), episode.runtime);
    }

    /** "845 гласа", "612 хил. гласа", "2,6 млн. гласа". */
    static String votes(int n) {
        if (n < 1000) return n + (n == 1 ? " глас" : " гласа");
        if (n < 1_000_000) return Math.round(n / 1000.0) + " хил. гласа";
        double millions = Math.round(n / 100_000.0) / 10.0;
        String s = millions == Math.floor(millions)
                ? String.valueOf((long) millions)
                : String.format(Locale.ROOT, "%.1f", millions).replace('.', ',');
        return s + " млн. гласа";
    }

    private static String rating(String value) {
        return value.replace('.', ',');
    }

    private static String joinParts(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) kept.add(p);
        }
        return kept.isEmpty() ? null : String.join(" · ", kept);
    }

    private static void people(JSONArray array, List<Person> into) throws JSONException {
        for (int n = 0; array != null && n < array.length(); n++) {
            JSONObject p = array.getJSONObject(n);
            Person person = new Person();
            person.id = integer(p, "id");
            person.name = str(p, "name");
            person.photo = str(p, "photo");
            if (person.name != null) into.add(person);
        }
    }

    static String str(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String s = o.optString(key, "").trim();
        return s.isEmpty() ? null : s;
    }

    static Integer integer(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    static final class Person {
        Integer id;
        String name, photo;
    }

    static final class Similar {
        String kind, title, year, poster;
        int id;

        /** "Черният рицар (2008)". */
        String caption() {
            String t = title == null ? "" : title;
            return year == null ? t : t + " (" + year + ")";
        }
    }

    static final class Episode {
        int season, episode;
        String title, titleBg, imdbRating, runtime;
    }

    /** An actor, from GET /info/person/{id}. */
    static final class Bio {
        int id;
        String name, photo, birthday, deathday, placeOfBirth, bio, bioLang;

        static Bio parse(String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            Bio b = new Bio();
            Integer id = integer(o, "id");
            b.id = id == null ? 0 : id;
            b.name = str(o, "name");
            b.photo = str(o, "photo");
            b.birthday = str(o, "birthday");
            b.deathday = str(o, "deathday");
            b.placeOfBirth = str(o, "place_of_birth");
            b.bio = str(o, "bio");
            b.bioLang = str(o, "bio_lang");
            return b;
        }

        /** "Рождена дата: 11.11.1974 (52 г.)" or "11.11.1940 – 05.03.2020 (79 г.)"; today is passed in for tests. */
        String lifeLine(int year, int month, int day) {
            int[] born = date(birthday);
            if (born == null) return null;
            int[] died = date(deathday);
            if (died != null) {
                return format(born) + " – " + format(died) + " (" + age(born, died) + " г.)";
            }
            return "Рождена дата: " + format(born) + " (" + age(born, new int[]{year, month, day}) + " г.)";
        }

        String placeLine() {
            return placeOfBirth == null ? null : "Място на раждане: " + placeOfBirth;
        }

        boolean bioInEnglish() {
            return bio != null && "en".equals(bioLang);
        }

        private static int[] date(String iso) {
            if (iso == null) return null;
            String[] p = iso.split("-");
            if (p.length != 3) return null;
            try {
                return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String format(int[] d) {
            return String.format(Locale.ROOT, "%02d.%02d.%04d", d[2], d[1], d[0]);
        }

        private static int age(int[] from, int[] to) {
            int years = to[0] - from[0];
            if (to[1] < from[1] || (to[1] == from[1] && to[2] < from[2])) years--;
            return years;
        }
    }
}

package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BgSubsInfoTest {

    // shape of bgsubs GET /info (bgsubs/info.py)
    static final String MOVIE_JSON = "{\"kind\":\"movie\",\"tmdb_id\":27205,\"title\":\"Inception\",\"title_bg\":\"Генезис\","
            + "\"year\":\"2010\",\"imdb_rating\":\"8.8\",\"imdb_votes\":2612345,"
            + "\"awards\":\"Спечели 4 Оскара · общо 159 награди и 220 номинации\",\"plot\":\"Дом Коб е крадец...\","
            + "\"plot_lang\":\"bg\",\"genres\":\"Екшън, Приключения, Фантастика\",\"runtime\":\"2 ч 28 мин\","
            + "\"director\":\"Christopher Nolan\",\"poster\":\"https://m.media-amazon.com/x.jpg\","
            + "\"imdb_url\":\"https://www.imdb.com/title/tt1375666/\","
            + "\"cast\":[{\"id\":6193,\"name\":\"Leonardo DiCaprio\",\"photo\":\"https://image.tmdb.org/t/p/w185/l.jpg\"},"
            + "{\"id\":null,\"name\":\"Elliot Page\",\"photo\":null}],"
            + "\"guest_cast\":[],"
            + "\"similar\":[{\"kind\":\"movie\",\"id\":155,\"title\":\"Черният рицар\",\"year\":\"2008\",\"poster\":null},"
            + "{\"kind\":\"tv\",\"id\":200,\"title\":\"Slow Horses\",\"year\":null,\"poster\":\"https://image.tmdb.org/t/p/w342/s.jpg\"}],"
            + "\"episode\":null}";

    static final String EPISODE_JSON = "{\"kind\":\"tv\",\"tmdb_id\":100,\"title\":\"Black Doves\",\"title_bg\":null,"
            + "\"year\":\"2024–\",\"imdb_rating\":null,\"imdb_votes\":null,\"awards\":null,\"plot\":\"Helen...\","
            + "\"plot_lang\":\"en\",\"genres\":null,\"runtime\":null,\"director\":null,\"poster\":null,\"imdb_url\":null,"
            + "\"cast\":[],\"guest_cast\":[{\"id\":9,\"name\":\"Guest Star\",\"photo\":null}],\"similar\":[],"
            + "\"episode\":{\"season\":1,\"episode\":2,\"title\":\"A Little Black Dove\",\"title_bg\":\"Малката черна гълъбица\","
            + "\"imdb_rating\":\"7.8\",\"runtime\":\"52 мин\"}}";

    static final String PERSON_JSON = "{\"id\":6193,\"name\":\"Leonardo DiCaprio\",\"photo\":\"https://image.tmdb.org/t/p/w342/l.jpg\","
            + "\"birthday\":\"1974-11-11\",\"deathday\":null,\"place_of_birth\":\"Los Angeles, California, USA\","
            + "\"bio\":\"Leonardo Wilhelm DiCaprio is...\",\"bio_lang\":\"en\"}";

    @Test
    public void parsesMovie() throws Exception {
        BgSubsInfo i = BgSubsInfo.parse(MOVIE_JSON);
        assertEquals("movie", i.kind);
        assertEquals("Генезис", i.heading());
        assertEquals("Inception", i.originalTitle());
        assertEquals("2010 · 2 ч 28 мин · Екшън, Приключения, Фантастика", i.metaLine());
        assertEquals("★ IMDb 8,8 (2,6 млн. гласа)", i.ratingLine());
        assertEquals("🏆 Спечели 4 Оскара · общо 159 награди и 220 номинации", i.awardsLine());
        assertEquals("Режисьор: Christopher Nolan", i.directorLine());
        assertEquals("Дом Коб е крадец...", i.plot);
        assertFalse(i.plotInEnglish());
        assertEquals(2, i.cast.size());
        assertEquals(Integer.valueOf(6193), i.cast.get(0).id);
        assertEquals("https://image.tmdb.org/t/p/w185/l.jpg", i.cast.get(0).photo);
        assertNull(i.cast.get(1).id);
        assertNull(i.cast.get(1).photo);
        assertTrue(i.guestCast.isEmpty());
        assertEquals(2, i.similar.size());
        assertEquals("Черният рицар (2008)", i.similar.get(0).caption());
        assertEquals("Slow Horses", i.similar.get(1).caption());
        assertEquals("tv", i.similar.get(1).kind);
        assertEquals(200, i.similar.get(1).id);
        assertNull(i.episode);
        assertNull(i.episodeLine());
    }

    @Test
    public void parsesEpisodeAndSkipsMissingParts() throws Exception {
        BgSubsInfo i = BgSubsInfo.parse(EPISODE_JSON);
        assertEquals("Black Doves", i.heading());
        assertNull(i.originalTitle());
        assertEquals("2024–", i.metaLine());
        assertNull(i.ratingLine());
        assertNull(i.awardsLine());
        assertNull(i.directorLine());
        assertTrue(i.plotInEnglish());
        assertEquals("Сезон 1, серия 2: Малката черна гълъбица (A Little Black Dove)", i.episodeLine());
        assertEquals("★ IMDb 7,8 · 52 мин", i.episodeDetail());
        assertEquals("Guest Star", i.guestCast.get(0).name);
    }

    @Test
    public void episodeWithoutBulgarianTitle() throws Exception {
        BgSubsInfo i = BgSubsInfo.parse(EPISODE_JSON.replace("\"Малката черна гълъбица\"", "null")
                .replace("\"7.8\"", "null"));
        assertEquals("Сезон 1, серия 2: A Little Black Dove", i.episodeLine());
        assertEquals("52 мин", i.episodeDetail());
    }

    @Test
    public void votesInBulgarian() throws Exception {
        assertEquals("1 глас", BgSubsInfo.votes(1));
        assertEquals("845 гласа", BgSubsInfo.votes(845));
        assertEquals("13 хил. гласа", BgSubsInfo.votes(12_600));
        assertEquals("612 хил. гласа", BgSubsInfo.votes(612_345));
        assertEquals("2,6 млн. гласа", BgSubsInfo.votes(2_612_345));
        assertEquals("1 млн. гласа", BgSubsInfo.votes(1_000_100));
        assertEquals("★ IMDb 7,1", BgSubsInfo.parse("{\"imdb_rating\":\"7.1\",\"imdb_votes\":null}").ratingLine());
    }

    @Test
    public void personLifeLines() throws Exception {
        BgSubsInfo.Bio p = BgSubsInfo.Bio.parse(PERSON_JSON);
        assertEquals("Leonardo DiCaprio", p.name);
        assertEquals("Рождена дата: 11.11.1974 (52 г.)", p.lifeLine(2026, 11, 16));
        assertEquals("Рождена дата: 11.11.1974 (51 г.)", p.lifeLine(2026, 11, 10));
        assertEquals("Място на раждане: Los Angeles, California, USA", p.placeLine());
        assertTrue(p.bioInEnglish());
        assertEquals("Leonardo Wilhelm DiCaprio is...", p.bio);

        BgSubsInfo.Bio dead = BgSubsInfo.Bio.parse(PERSON_JSON.replace("\"deathday\":null", "\"deathday\":\"2020-03-05\""));
        assertEquals("11.11.1974 – 05.03.2020 (45 г.)", dead.lifeLine(2026, 11, 16));

        BgSubsInfo.Bio bare = BgSubsInfo.Bio.parse("{\"id\":1,\"name\":\"X\",\"photo\":null,\"birthday\":null,"
                + "\"deathday\":null,\"place_of_birth\":null,\"bio\":null,\"bio_lang\":null}");
        assertNull(bare.lifeLine(2026, 1, 1));
        assertNull(bare.placeLine());
        assertNull(bare.bio);
        assertFalse(bare.bioInEnglish());
    }
}

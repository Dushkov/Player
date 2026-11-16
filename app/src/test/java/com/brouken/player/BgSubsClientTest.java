package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BgSubsClientTest {

    // Shapes as returned by bgsubs Stage 1 (bgsubs/models.py SearchResult / FileEntry)
    static final String SEARCH_JSON = "{\"results\":["
            + "{\"id\":\"k3J9xQab\",\"source\":\"unacs\",\"title\":\"Inception\",\"year\":2010,"
            + "\"release\":\"Inception.RERiP.DVDRiP.XviD-ARROW\",\"uploader\":\"ArcMage\",\"downloads\":12474,"
            + "\"date\":\"14.11.2010\",\"fps\":\"23.976\",\"files_hint\":2},"
            + "{\"id\":\"Zz12Yy34\",\"source\":\"subdl\",\"title\":\"Inception\",\"year\":null,"
            + "\"release\":null,\"uploader\":null,\"downloads\":null,\"date\":null,\"fps\":null,\"files_hint\":null}"
            + "],\"errors\":[{\"source\":\"sabbz\",\"message\":\"timeout\"}]}";
    static final String EMPTY_SEARCH_JSON = "{\"results\":[],\"errors\":[]}";
    static final String FILES_JSON = "{\"files\":[{\"n\":0,\"name\":\"a.CD1.srt\"},{\"n\":1,\"name\":\"a.CD2.srt\"}]}";

    private MockWebServer server;
    private final OkHttpClient http = new OkHttpClient();

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private String base() {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    private static MockResponse response(int code, String body) {
        return new MockResponse.Builder().code(code).body(body).build();
    }

    @Test
    public void parseSearchMapsFieldsAndNulls() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch(SEARCH_JSON);
        assertEquals(2, r.results.size());
        BgSubsClient.Result first = r.results.get(0);
        assertEquals("k3J9xQab", first.id);
        assertEquals("unacs", first.source);
        assertEquals("Inception.RERiP.DVDRiP.XviD-ARROW", first.release);
        assertEquals("23.976", first.fps);
        assertEquals(Integer.valueOf(12474), first.downloads);
        assertEquals(Integer.valueOf(2), first.filesHint);
        BgSubsClient.Result second = r.results.get(1);
        assertNull(second.release);
        assertNull(second.fps);
        assertNull(second.downloads);
        assertNull(second.year);
        assertEquals(List.of("sabbz (timeout)"), r.errors);
    }

    @Test
    public void primaryLineIsReleaseOrTitle() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch(SEARCH_JSON);
        assertEquals("Inception.RERiP.DVDRiP.XviD-ARROW", r.results.get(0).primaryLine());
        assertEquals("Inception", r.results.get(1).primaryLine());
    }

    @Test
    public void metaLineHasReadableSourceAndSkipsMissingParts() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch(SEARCH_JSON);
        assertEquals("subsunacs · 23.976 fps · 12\u00A0474 изтегляния · 2 файла · 14.11.2010", r.results.get(0).metaLine());
        assertEquals("SubDL", r.results.get(1).metaLine());
    }

    @Test
    public void sourceNamesAreReadable() {
        assertEquals("sab.bz", BgSubsClient.sourceName("sabbz"));
        assertEquals("subsunacs", BgSubsClient.sourceName("unacs"));
        assertEquals("SubDL", BgSubsClient.sourceName("subdl"));
        assertEquals("OpenSubtitles", BgSubsClient.sourceName("opensubtitles"));
        assertEquals("SubSource", BgSubsClient.sourceName("subsource"));
        assertEquals("newsite", BgSubsClient.sourceName("newsite"));
    }

    @Test
    public void singleFileAndOneDownloadWording() throws Exception {
        BgSubsClient.Result r = BgSubsClient.parseSearch("{\"results\":[{\"id\":\"a\",\"source\":\"sabbz\",\"title\":\"X\","
                + "\"downloads\":1,\"files_hint\":1}],\"errors\":[]}").results.get(0);
        assertEquals("sab.bz · 1 изтегляне", r.metaLine());
    }

    @Test
    public void parseFiles() throws Exception {
        List<BgSubsClient.FileEntry> files = BgSubsClient.parseFiles(FILES_JSON);
        assertEquals(2, files.size());
        assertEquals(1, files.get(1).n);
        assertEquals("a.CD2.srt", files.get(1).name);
    }

    @Test
    public void searchUrlEncodesCyrillicAndSpaces() {
        String url = BgSubsClient.searchUrl("https://subs.dushkov.eu", BgSubsQuery.parse("Игра на тронове S01E01")).toString();
        assertTrue(url, url.startsWith("https://subs.dushkov.eu/search?q=%D0%98%D0%B3%D1%80%D0%B0%20%D0%BD%D0%B0"));
        assertTrue(url, url.endsWith("&season=1&episode=1"));
    }

    @Test
    public void searchUrlOmitsNullParams() {
        assertEquals("https://subs.dushkov.eu/search?q=Inception",
                BgSubsClient.searchUrl("https://subs.dushkov.eu", BgSubsQuery.parse("Inception")).toString());
        assertEquals("https://subs.dushkov.eu/search?q=Inception&year=2010",
                BgSubsClient.searchUrl("https://subs.dushkov.eu/", BgSubsQuery.parse("Inception 2010")).toString());
    }

    @Test
    public void noTokenThrowsWithoutRequest() throws Exception {
        try {
            new BgSubsClient(base(), "", http).search(BgSubsQuery.parse("Inception"));
            fail("expected NO_TOKEN");
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(BgSubsClient.BgSubsException.Kind.NO_TOKEN, e.kind);
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void sendsApiKeyHeaderAndParsesSearch() throws Exception {
        server.enqueue(response(200, SEARCH_JSON));
        BgSubsClient.SearchResponse r = new BgSubsClient(base(), "tok", http).search(BgSubsQuery.parse("Inception"));
        assertEquals(2, r.results.size());
        RecordedRequest request = server.takeRequest();
        assertEquals("tok", request.getHeaders().get("X-Api-Key"));
        assertEquals("/search?q=Inception", request.getUrl().encodedPath() + "?" + request.getUrl().encodedQuery());
    }

    private void assertKind(int code, String body, BgSubsClient.BgSubsException.Kind kind, String message) throws Exception {
        server.enqueue(response(code, body));
        try {
            new BgSubsClient(base(), "tok", http).files("abc");
            fail("expected " + kind);
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(kind, e.kind);
            if (message != null) {
                assertEquals(message, e.getMessage());
            }
        }
    }

    @Test
    public void statusMapping() throws Exception {
        assertKind(401, "{\"detail\":\"invalid or missing X-Api-Key\"}", BgSubsClient.BgSubsException.Kind.UNAUTHORIZED, null);
        assertKind(422, "{\"detail\":\"the file is a Word/Office document, not a subtitle\"}",
                BgSubsClient.BgSubsException.Kind.UNPROCESSABLE, "the file is a Word/Office document, not a subtitle");
        assertKind(502, "{\"detail\":\"source unreachable\"}", BgSubsClient.BgSubsException.Kind.SERVER, null);
        assertKind(404, "{\"detail\":\"unknown or expired id abc\"}", BgSubsClient.BgSubsException.Kind.NOT_FOUND, null);
        assertKind(503, "{\"detail\":\"OMDB_API_KEY is not set\"}", BgSubsClient.BgSubsException.Kind.UNAVAILABLE, null);
    }

    @Test
    public void unreachableServerIsNetworkError() throws Exception {
        String dead = base();
        server.close();
        try {
            new BgSubsClient(dead, "tok", http).files("abc");
            fail("expected NETWORK");
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(BgSubsClient.BgSubsException.Kind.NETWORK, e.kind);
        }
    }

    @Test
    public void fallbackSearchWithoutYear() throws Exception {
        server.enqueue(response(200, EMPTY_SEARCH_JSON));
        server.enqueue(response(200, SEARCH_JSON));
        BgSubsClient.SearchResponse r = new BgSubsClient(base(), "tok", http).search(BgSubsQuery.parse("Blade Runner 2049"));
        assertEquals(2, r.results.size());
        assertEquals("q=Blade%20Runner&year=2049", server.takeRequest().getUrl().encodedQuery());
        assertEquals("q=Blade%20Runner%202049", server.takeRequest().getUrl().encodedQuery());
    }

    @Test
    public void noFallbackWhenThereAreErrors() throws Exception {
        server.enqueue(response(200, "{\"results\":[],\"errors\":[{\"source\":\"sabbz\",\"message\":\"timeout\"}]}"));
        BgSubsClient.SearchResponse r = new BgSubsClient(base(), "tok", http).search(BgSubsQuery.parse("Blade Runner 2049"));
        assertTrue(r.results.isEmpty());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void filesAndDownload() throws Exception {
        server.enqueue(response(200, FILES_JSON));
        server.enqueue(response(200, "1\n00:00:01,000 --> 00:00:02,000\nЗдравей\n"));
        BgSubsClient client = new BgSubsClient(base(), "tok", http);
        assertEquals(2, client.files("k3J9xQab").size());
        byte[] srt = client.download("k3J9xQab", 1);
        assertArrayEquals("1\n00:00:01,000 --> 00:00:02,000\nЗдравей\n".getBytes("UTF-8"), srt);
        assertEquals("/files/k3J9xQab", server.takeRequest().getUrl().encodedPath());
        assertEquals("/download/k3J9xQab/1", server.takeRequest().getUrl().encodedPath());
    }

    @Test
    public void emptyResultsAreNotAnError() throws Exception {
        server.enqueue(response(200, EMPTY_SEARCH_JSON));
        BgSubsClient.SearchResponse r = new BgSubsClient(base(), "tok", http).search(BgSubsQuery.parse("zzz"));
        assertTrue(r.results.isEmpty());
        assertFalse(r.errors.iterator().hasNext());
    }

    @Test
    public void serverWithoutSchemeDefaultsToHttps() {
        assertEquals("https://subs.dushkov.eu/search?q=Inception",
                BgSubsClient.searchUrl("subs.dushkov.eu", BgSubsQuery.parse("Inception")).toString());
    }

    @Test
    public void invalidServerIsAnErrorNotACrash() throws Exception {
        for (String server : new String[]{"http://", "https://exa mple.com", "::"}) {
            try {
                new BgSubsClient(server, "tok", http).search(BgSubsQuery.parse("Inception"));
                fail("expected BAD_SERVER for " + server);
            } catch (BgSubsClient.BgSubsException e) {
                assertEquals(server, BgSubsClient.BgSubsException.Kind.BAD_SERVER, e.kind);
            }
        }
    }

    private static BgSubsClient.Result result(String source, String release) {
        BgSubsClient.Result r = new BgSubsClient.Result();
        r.source = source;
        r.title = "Black Doves";
        r.release = release;
        return r;
    }

    @Test
    public void sitePrefixesAreStrippedFromRelease() {
        assertEquals("Black.Doves.S01E02.1080p.NF.WEB-DL", result("unacs", "За рилийза Black.Doves.S01E02.1080p.NF.WEB-DL").primaryLine());
        assertEquals("Avatar.2009.720p.BDRip", result("unacs", "Рилийз: Avatar.2009.720p.BDRip").primaryLine());
        assertEquals("breaking.bad.s01e02.dsr", result("unacs", "Субтитри за версията: breaking.bad.s01e02.dsr").primaryLine());
        assertEquals("Тези субтитри са рипнати от Blu-Ray", result("unacs", "Тези субтитри са рипнати от Blu-Ray").primaryLine());
        assertEquals("Black Doves", result("unacs", "За рилийза").primaryLine());
    }

    @Test
    public void detailLineIsMetaWithoutSource() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch(SEARCH_JSON);
        assertEquals("23.976 fps · 12\u00A0474 изтегляния · 2 файла · 14.11.2010", r.results.get(0).detailLine());
        assertEquals("", r.results.get(1).detailLine());
    }

    private BgSubsClient.BgSubsException.Kind loginKind(int code, String body) throws Exception {
        server.enqueue(response(code, body));
        try {
            BgSubsClient.login(base(), "kino", "Google TV", http);
            fail("expected an error for " + code);
            return null;
        } catch (BgSubsClient.BgSubsException e) {
            return e.kind;
        }
    }

    @Test
    public void loginSendsPasswordAndReturnsToken() throws Exception {
        server.enqueue(response(200, "{\"token\":\"dev-token-123\"}"));
        assertEquals("dev-token-123", BgSubsClient.login(base(), "kino-vecher-42", "Google TV", http));
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/login", request.getUrl().encodedPath());
        org.json.JSONObject body = new org.json.JSONObject(request.getBody().utf8());
        assertEquals("kino-vecher-42", body.getString("password"));
        assertEquals("Google TV", body.getString("device"));
        assertNull(request.getHeaders().get("X-Api-Key"));
    }

    @Test
    public void loginErrorsMapToKinds() throws Exception {
        assertEquals(BgSubsClient.BgSubsException.Kind.WRONG_PASSWORD, loginKind(401, "{\"detail\":\"wrong password\"}"));
        assertEquals(BgSubsClient.BgSubsException.Kind.RATE_LIMITED, loginKind(429, "{\"detail\":\"too many\"}"));
        assertEquals(BgSubsClient.BgSubsException.Kind.LOGIN_DISABLED, loginKind(404, "{\"detail\":\"not enabled\"}"));
        assertEquals(BgSubsClient.BgSubsException.Kind.SERVER, loginKind(500, "oops"));
    }

    @Test
    public void loginWithBlankPasswordIsWrongPasswordWithoutRequest() throws Exception {
        try {
            BgSubsClient.login(base(), "  ", "tv", http);
            fail();
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(BgSubsClient.BgSubsException.Kind.WRONG_PASSWORD, e.kind);
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void loginToUnreachableServerIsNetwork() throws Exception {
        String dead = base();
        server.close();
        try {
            BgSubsClient.login(dead, "kino", "tv", http);
            fail();
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(BgSubsClient.BgSubsException.Kind.NETWORK, e.kind);
        }
    }

    @Test
    public void qualityFlagsAreParsedAndShown() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch("{\"results\":["
                + "{\"id\":\"a\",\"source\":\"opensubtitles\",\"title\":\"X\",\"fps\":\"25\",\"machine_translated\":true,\"production\":null},"
                + "{\"id\":\"b\",\"source\":\"subsource\",\"title\":\"X\",\"machine_translated\":false,\"production\":\"improved\"},"
                + "{\"id\":\"c\",\"source\":\"subsource\",\"title\":\"X\",\"production\":\"retail\"},"
                + "{\"id\":\"d\",\"source\":\"sabbz\",\"title\":\"X\"}"
                + "],\"errors\":[]}");
        assertTrue(r.results.get(0).machineTranslated);
        assertEquals("⚠ машинен превод · 25 fps", r.results.get(0).detailLine());
        assertEquals("подобрени", r.results.get(1).detailLine());
        assertEquals("официални", r.results.get(2).detailLine());
        assertFalse(r.results.get(3).machineTranslated);
        assertEquals("", r.results.get(3).detailLine());
    }

    @Test
    public void subslandVerifiedLabelAndName() throws Exception {
        BgSubsClient.Result r = BgSubsClient.parseSearch("{\"results\":[{\"id\":\"a\",\"source\":\"subsland\","
                + "\"title\":\"Генезис\",\"production\":\"verified\",\"fps\":\"23.976\"}],\"errors\":[]}").results.get(0);
        assertEquals("проверени · 23.976 fps", r.detailLine());
        assertEquals("SubsLand", BgSubsClient.sourceName("subsland"));
    }

    // --- Info screen ---------------------------------------------------------------------------------

    @Test
    public void infoSendsQueryAndToken() throws Exception {
        server.enqueue(response(200, BgSubsInfoTest.MOVIE_JSON));
        BgSubsClient c = new BgSubsClient(base(), "tok", http);
        BgSubsInfo info = c.info(new BgSubsQuery("Black Doves", 2024, 1, 2));
        RecordedRequest req = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("/info", req.getUrl().encodedPath());
        assertEquals("Black Doves", req.getUrl().queryParameter("q"));
        assertEquals("2024", req.getUrl().queryParameter("year"));
        assertEquals("1", req.getUrl().queryParameter("season"));
        assertEquals("2", req.getUrl().queryParameter("episode"));
        assertEquals("tok", req.getHeaders().get("X-Api-Key"));
        assertEquals("Генезис", info.heading());
    }

    @Test
    public void titleInfoAndPersonPaths() throws Exception {
        server.enqueue(response(200, BgSubsInfoTest.MOVIE_JSON));
        server.enqueue(response(200, BgSubsInfoTest.PERSON_JSON));
        BgSubsClient c = new BgSubsClient(base(), "tok", http);
        c.titleInfo("tv", 200);
        assertEquals("/info/title/tv/200", server.takeRequest().getUrl().encodedPath());
        BgSubsInfo.Bio p = c.person(6193);
        assertEquals("/info/person/6193", server.takeRequest().getUrl().encodedPath());
        assertEquals("Leonardo DiCaprio", p.name);
    }

    @Test
    public void infoNotFoundKind() throws Exception {
        server.enqueue(response(404, "{\"detail\":\"no information found\"}"));
        try {
            new BgSubsClient(base(), "tok", http).info(new BgSubsQuery("zzz", null, null, null));
            fail();
        } catch (BgSubsClient.BgSubsException e) {
            assertEquals(BgSubsClient.BgSubsException.Kind.NOT_FOUND, e.kind);
        }
    }

    @Test
    public void imagesAreFetchedWithoutOurToken() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        server.enqueue(new MockResponse.Builder().code(200).body(new okio.Buffer().write(png)).build());
        byte[] got = BgSubsClient.fetchImage(server.url("/t/p/w185/l.jpg").toString(), http);
        assertArrayEquals(png, got);
        assertNull(server.takeRequest().getHeaders().get("X-Api-Key"));
        server.enqueue(response(404, ""));
        assertNull(BgSubsClient.fetchImage(server.url("/missing.jpg").toString(), http));
        assertNull(BgSubsClient.fetchImage("ftp://example.com/x.jpg", http));
        assertNull(BgSubsClient.fetchImage(null, http));
    }
}

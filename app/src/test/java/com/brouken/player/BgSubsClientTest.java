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
    public void labelSkipsMissingParts() throws Exception {
        BgSubsClient.SearchResponse r = BgSubsClient.parseSearch(SEARCH_JSON);
        assertEquals("unacs · Inception.RERiP.DVDRiP.XviD-ARROW · 23.976 · 12474↓", r.results.get(0).label());
        assertEquals("subdl · Inception", r.results.get(1).label());
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
        assertKind(404, "{\"detail\":\"unknown or expired id abc\"}", BgSubsClient.BgSubsException.Kind.SERVER, null);
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
}

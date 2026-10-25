package com.brouken.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** HTTP client for the bgsubs server (/search, /files, /download). Blocking: call it off the UI thread. */
final class BgSubsClient {

    private static final int MAX_SUBTITLE_BYTES = 2_000_000;

    private final String server;
    private final String token;
    private final OkHttpClient http;
    private volatile Call currentCall;

    BgSubsClient(String server, String token, OkHttpClient http) {
        this.server = server == null ? "" : server.trim();  // normalized only in serverUrl(); doing it twice turns "http://" into host "http"
        this.token = token == null ? "" : token.trim();
        this.http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build();
    }

    SearchResponse search(BgSubsQuery query) throws BgSubsException {
        checkConfigured();
        SearchResponse response = parse(get(checkedSearchUrl(query)));
        if (query.year != null && response.results.isEmpty() && response.errors.isEmpty()) {
            // "Blade Runner 2049" was read as year 2049; try the whole title without a year
            BgSubsQuery fallback = query.withoutYear(query.title + " " + query.year);
            response = parse(get(checkedSearchUrl(fallback)));
        }
        return response;
    }

    List<FileEntry> files(String id) throws BgSubsException {
        HttpUrl url = base().newBuilder().addPathSegment("files").addPathSegment(id).build();
        try {
            return parseFiles(new String(get(url), "UTF-8"));
        } catch (JSONException | IOException e) {
            throw new BgSubsException(BgSubsException.Kind.SERVER, "invalid server response");
        }
    }

    byte[] download(String id, int n) throws BgSubsException {
        HttpUrl url = base().newBuilder().addPathSegment("download").addPathSegment(id)
                .addPathSegment(String.valueOf(n)).build();
        return get(url);
    }

    /** Aborts the request in progress, if any; the blocked call then fails with NETWORK. */
    void cancel() {
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
    }

    /** The /search URL, or null when the server address is not a valid http(s) URL. */
    static HttpUrl searchUrl(String server, BgSubsQuery query) {
        HttpUrl base = serverUrl(server);
        if (base == null) {
            return null;
        }
        HttpUrl.Builder b = base.newBuilder().addPathSegment("search").addQueryParameter("q", query.title);
        if (query.year != null) b.addQueryParameter("year", String.valueOf(query.year));
        if (query.season != null) b.addQueryParameter("season", String.valueOf(query.season));
        if (query.episode != null) b.addQueryParameter("episode", String.valueOf(query.episode));
        return b.build();
    }

    static SearchResponse parseSearch(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        SearchResponse response = new SearchResponse();
        JSONArray results = root.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            Result r = new Result();
            r.id = o.getString("id");
            r.source = string(o, "source");
            r.title = string(o, "title");
            r.release = string(o, "release");
            r.fps = string(o, "fps");
            r.date = string(o, "date");
            r.year = integer(o, "year");
            r.downloads = integer(o, "downloads");
            r.filesHint = integer(o, "files_hint");
            response.results.add(r);
        }
        JSONArray errors = root.optJSONArray("errors");
        for (int i = 0; errors != null && i < errors.length(); i++) {
            JSONObject o = errors.getJSONObject(i);
            response.errors.add(string(o, "source") + " (" + string(o, "message") + ")");
        }
        return response;
    }

    static List<FileEntry> parseFiles(String json) throws JSONException {
        JSONArray files = new JSONObject(json).getJSONArray("files");
        List<FileEntry> entries = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            FileEntry e = new FileEntry();
            e.n = o.getInt("n");
            e.name = o.getString("name");
            entries.add(e);
        }
        return entries;
    }

    private SearchResponse parse(byte[] body) throws BgSubsException {
        try {
            return parseSearch(new String(body, "UTF-8"));
        } catch (JSONException | IOException e) {
            throw new BgSubsException(BgSubsException.Kind.SERVER, "invalid server response");
        }
    }

    private HttpUrl base() throws BgSubsException {
        checkConfigured();
        HttpUrl url = serverUrl(server);
        if (url == null) {
            throw new BgSubsException(BgSubsException.Kind.BAD_SERVER, "invalid server address");
        }
        return url;
    }

    private HttpUrl checkedSearchUrl(BgSubsQuery query) throws BgSubsException {
        HttpUrl url = searchUrl(server, query);
        if (url == null) {
            throw new BgSubsException(BgSubsException.Kind.BAD_SERVER, "invalid server address");
        }
        return url;
    }

    private void checkConfigured() throws BgSubsException {
        if (token.isEmpty() || server.isEmpty()) {
            throw new BgSubsException(BgSubsException.Kind.NO_TOKEN, "server or token not set");
        }
    }

    private byte[] get(HttpUrl url) throws BgSubsException {
        checkConfigured();
        Request request = new Request.Builder().url(url).header("X-Api-Key", token).build();
        Call call = http.newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            int code = response.code();
            if (code == 200) {
                if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                    throw new BgSubsException(BgSubsException.Kind.SERVER, "response too large");
                }
                return body.bytes();
            }
            String detail = detail(body.string(), "HTTP " + code);
            if (code == 401) throw new BgSubsException(BgSubsException.Kind.UNAUTHORIZED, detail);
            if (code == 422) throw new BgSubsException(BgSubsException.Kind.UNPROCESSABLE, detail);
            throw new BgSubsException(BgSubsException.Kind.SERVER, detail);
        } catch (IOException e) {
            throw new BgSubsException(BgSubsException.Kind.NETWORK, e.getMessage() == null ? "network error" : e.getMessage());
        } finally {
            currentCall = null;
        }
    }

    private static String detail(String body, String fallback) {
        try {
            Object detail = new JSONObject(body).opt("detail");
            return detail instanceof String ? (String) detail : fallback;
        } catch (JSONException e) {
            return fallback;
        }
    }

    private static String string(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    private static Integer integer(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optInt(key);
    }

    /**
     * Parses the server address; null if it is not a usable http(s) URL. Parsed as a whole (not with "/search"
     * appended as text), because OkHttp reads "http:/search" leniently as host "search".
     */
    private static HttpUrl serverUrl(String server) {
        String s = normalizeServer(server);
        HttpUrl url = s.isEmpty() ? null : HttpUrl.parse(s);
        return url == null || url.host().isEmpty() ? null : url;
    }

    /** Trims, drops trailing slashes and assumes https:// when no scheme was typed ("subs.dushkov.eu"). */
    private static String normalizeServer(String server) {
        String s = server == null ? "" : server.trim();
        boolean hasScheme = s.contains("://");  // checked before stripping, or "http://" would become host "http"
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.isEmpty() && !hasScheme) {
            s = "https://" + s;
        }
        return s;
    }

    static String sourceName(String source) {
        throw new UnsupportedOperationException();
    }

    static final class Result {
        String id, source, title, release, fps, date;
        Integer year, downloads, filesHint;

        String primaryLine() {
            throw new UnsupportedOperationException();
        }

        String metaLine() {
            throw new UnsupportedOperationException();
        }

        String label() {
            StringBuilder sb = new StringBuilder(source == null ? "?" : source);
            String name = release != null ? release : title;
            if (name != null) sb.append(" · ").append(name);
            if (fps != null) sb.append(" · ").append(fps);
            if (downloads != null) sb.append(" · ").append(downloads).append('↓');
            return sb.toString();
        }
    }

    static final class SearchResponse {
        final List<Result> results = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    static final class FileEntry {
        int n;
        String name;
    }

    static final class BgSubsException extends Exception {
        enum Kind { NO_TOKEN, BAD_SERVER, UNAUTHORIZED, NETWORK, SERVER, UNPROCESSABLE }

        final Kind kind;

        BgSubsException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}

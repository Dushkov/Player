package com.brouken.player;

import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import org.json.JSONException;

/** HTTP client for the bgsubs server (/search, /files, /download). */
final class BgSubsClient {

    BgSubsClient(String server, String token, OkHttpClient http) {
    }

    SearchResponse search(BgSubsQuery query) throws BgSubsException {
        throw new UnsupportedOperationException();
    }

    List<FileEntry> files(String id) throws BgSubsException {
        throw new UnsupportedOperationException();
    }

    byte[] download(String id, int n) throws BgSubsException {
        throw new UnsupportedOperationException();
    }

    static HttpUrl searchUrl(String server, BgSubsQuery query) {
        throw new UnsupportedOperationException();
    }

    static SearchResponse parseSearch(String json) throws JSONException {
        throw new UnsupportedOperationException();
    }

    static List<FileEntry> parseFiles(String json) throws JSONException {
        throw new UnsupportedOperationException();
    }

    static final class Result {
        String id, source, title, release, fps, date;
        Integer year, downloads, filesHint;

        String label() {
            throw new UnsupportedOperationException();
        }
    }

    static final class SearchResponse {
        List<Result> results;
        List<String> errors;
    }

    static final class FileEntry {
        int n;
        String name;
    }

    static final class BgSubsException extends Exception {
        enum Kind { NO_TOKEN, UNAUTHORIZED, NETWORK, SERVER, UNPROCESSABLE }

        final Kind kind;

        BgSubsException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}

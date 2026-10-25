package com.brouken.player;

import org.json.JSONException;

/** Checks GitHub Releases of Dushkov/Player for a newer Just Player BG and installs it. */
final class BgSubsUpdater {

    static Long versionCodeFromTag(String tag) {
        throw new UnsupportedOperationException();
    }

    static Release parseLatest(String json) throws JSONException {
        throw new UnsupportedOperationException();
    }

    static final class Release {
        long versionCode;
        String versionName;
        String apkUrl;
        long size;

        boolean isNewerThan(long installedVersionCode) {
            throw new UnsupportedOperationException();
        }
    }
}

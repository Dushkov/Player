package com.brouken.player;

import java.util.List;

/** Parsed SRT cues and which of them show at a playback position with a timing offset. */
final class BgSubsCues {

    static BgSubsCues parse(String srt) {
        throw new UnsupportedOperationException();
    }

    int size() {
        throw new UnsupportedOperationException();
    }

    List<String> textsAt(long positionMs, long offsetMs) {
        throw new UnsupportedOperationException();
    }

    static String offsetLabel(long offsetMs) {
        throw new UnsupportedOperationException();
    }
}

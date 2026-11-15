package com.brouken.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed SRT cues and which of them show at a playback position with a timing offset. */
final class BgSubsCues {

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern TAG = Pattern.compile("<[^>]*>|\\{[^}]*\\}");

    private final long[] starts;
    private final long[] ends;
    private final String[] texts;

    private BgSubsCues(long[] starts, long[] ends, String[] texts) {
        this.starts = starts;
        this.ends = ends;
        this.texts = texts;
    }

    /** The server always returns clean UTF-8 SRT; HTML/ASS styling tags are dropped (shown as plain text). */
    static BgSubsCues parse(String srt) {
        List<long[]> times = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        String[] lines = (srt == null ? "" : srt).replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIME.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            long start = ms(m, 1);
            long end = ms(m, 5);
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().isEmpty() && !TIME.matcher(lines[j]).find()) {
                String text = TAG.matcher(lines[j]).replaceAll("").trim();
                if (!text.isEmpty()) {
                    if (body.length() > 0) body.append('\n');
                    body.append(text);
                }
                j++;
            }
            i = j - 1;
            if (body.length() > 0 && end > start) {
                times.add(new long[]{start, end});
                bodies.add(body.toString());
            }
        }
        long[] s = new long[times.size()];
        long[] e = new long[times.size()];
        for (int k = 0; k < s.length; k++) {
            s[k] = times.get(k)[0];
            e[k] = times.get(k)[1];
        }
        return new BgSubsCues(s, e, bodies.toArray(new String[0]));
    }

    int size() {
        return texts.length;
    }

    /** Lines visible at positionMs; a positive offset shows every cue later, a negative one earlier. */
    List<String> textsAt(long positionMs, long offsetMs) {
        long t = positionMs - offsetMs;
        List<String> visible = null;
        for (int k = 0; k < texts.length; k++) {
            if (starts[k] <= t && t < ends[k]) {
                if (visible == null) visible = new ArrayList<>(2);
                visible.add(texts[k]);
            }
        }
        return visible == null ? Collections.<String>emptyList() : visible;
    }

    /** The first line not yet due with the current offset (the one to wait for in tap sync), or -1. */
    int nextCue(long positionMs, long offsetMs) {
        int best = -1;
        for (int k = 0; k < texts.length; k++) {
            if (starts[k] + offsetMs > positionMs && (best < 0 || starts[k] < starts[best])) {
                best = k;
            }
        }
        return best;
    }

    /** The line written right after line `index`, or -1. */
    int nextCueAfter(int index) {
        int best = -1;
        for (int k = 0; k < texts.length; k++) {
            if (starts[k] > starts[index] && (best < 0 || starts[k] < starts[best])) {
                best = k;
            }
        }
        return best;
    }

    String text(int index) {
        return texts[index];
    }

    /** OK pressed at positionMs when line `index` was heard: the offset that makes the line show right then. */
    long offsetForTap(int index, long positionMs) {
        return positionMs - starts[index];
    }

    /** "1,2 s по-късно", "0,5 s по-рано", "без отместване": the offset in words, not as a sign. */
    static String offsetWords(long offsetMs) {
        if (offsetMs == 0) {
            return "без отместване";
        }
        String seconds = String.format(Locale.ROOT, "%.1f", Math.abs(offsetMs) / 1000.0).replace('.', ',');
        return seconds + (offsetMs > 0 ? " s по-късно" : " s по-рано");
    }

    /** Middle line of the sync bar. */
    static String syncStatus(long offsetMs) {
        return offsetMs == 0 ? "Субтитрите са без отместване" : "Субтитрите идват " + offsetWords(offsetMs);
    }

    private static long ms(Matcher m, int group) {
        String frac = m.group(group + 3);
        while (frac.length() < 3) frac += "0";
        return ((Long.parseLong(m.group(group)) * 60 + Long.parseLong(m.group(group + 1))) * 60
                + Long.parseLong(m.group(group + 2))) * 1000 + Long.parseLong(frac.substring(0, 3));
    }
}

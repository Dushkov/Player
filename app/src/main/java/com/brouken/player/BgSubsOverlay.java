package com.brouken.player;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.text.Cue;
import androidx.media3.ui.SubtitleView;

import java.util.Collections;
import java.util.List;

/**
 * Shows BG subtitles itself instead of handing the file to ExoPlayer, so the timing offset can change live
 * without reloading the video. Cues go into Just Player's own SubtitleView, so they look like its subtitles.
 */
final class BgSubsOverlay {

    private static final long TICK_MS = 50;

    private final PlayerActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BgSubsCues cues;
    private long offsetMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, TICK_MS);
        }
    };

    BgSubsOverlay(PlayerActivity activity) {
        this.activity = activity;
    }

    void show(BgSubsCues cues, long offsetMs) {
        this.cues = cues;
        this.offsetMs = offsetMs;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void clear() {
        handler.removeCallbacks(tick);
        cues = null;
        SubtitleView view = subtitleView();
        if (view != null) {
            view.setCues(Collections.<Cue>emptyList());
        }
    }

    boolean isActive() {
        return cues != null;
    }

    BgSubsCues cues() {
        return cues;
    }

    long offsetMs() {
        return offsetMs;
    }

    void setOffset(long offsetMs) {
        this.offsetMs = offsetMs;
        render();
    }

    private void render() {
        SubtitleView view = subtitleView();
        if (cues == null || PlayerActivity.player == null || view == null) {
            return;
        }
        List<String> now = cues.textsAt(PlayerActivity.player.getCurrentPosition(), offsetMs);
        // set every tick, even unchanged: ExoPlayer clears the view on seeks and track changes.
        // Overlapping cues become one cue on two lines; separate cues would be drawn on top of each other.
        view.setCues(now.isEmpty()
                ? Collections.<Cue>emptyList()
                : Collections.singletonList(new Cue.Builder().setText(String.join("\n", now)).build()));
    }

    private SubtitleView subtitleView() {
        return activity.playerView == null ? null : activity.playerView.getSubtitleView();
    }
}

package com.brouken.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/** "BG" button flow: search bgsubs, pick a result (and a file), load the subtitle into the current video. */
final class BgSubsDialog {

    static final String PREF_SERVER = "bgsubs_server";
    static final String PREF_TOKEN = "bgsubs_token";
    static final String DEFAULT_SERVER = "https://subs.dushkov.eu";
    private static final String ALIAS_PREFIX = "bgsubs_alias_";

    private interface Work<T> {
        T run(BgSubsClient client) throws BgSubsClient.BgSubsException;
    }

    private interface Done<T> {
        void accept(T value);
    }

    private final PlayerActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient();

    // touched on the UI thread only
    private int generation;
    private BgSubsClient client;
    private BgSubsQuery lastQuery;
    private BgSubsClient.SearchResponse lastResponse;

    private final BgSubsLibrary library;
    private final BgSubsOverlay overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoCloseSync = this::stopSync;
    private String loadedKey;        // library key of the subtitle on screen, null if none
    private boolean turnedOff;       // the viewer switched BG subtitles off for this video
    private LinearLayout syncBar;    // non-null while the sync bar is open
    private boolean syncFine;        // false: "press OK when you hear …" step; true: ◀ ▶ fine tuning
    private int tapCue = -1;         // the line waited for in the tap step
    private BgSubsInfoScreen infoScreen;  // set at the end of the constructor

    BgSubsDialog(PlayerActivity activity) {
        this.activity = activity;
        this.library = new BgSubsLibrary(new File(activity.getFilesDir(), "bgsubs-library"), 300);
        this.overlay = new BgSubsOverlay(activity);
        this.infoScreen = new BgSubsInfoScreen(activity, new BgSubsInfoScreen.Opener() {
            @Override
            public void openTitle(String kind, int tmdbId) {
                run(R.string.bgsubs_info_loading, c -> c.titleInfo(kind, tmdbId), infoScreen::show, () -> { });
            }

            @Override
            public void openPerson(int id) {
                run(R.string.bgsubs_info_loading, c -> c.person(id), infoScreen::showPerson, () -> { });
            }
        }, http);
    }

    /** ℹ button: rating, awards, plot, actors and similar titles for what is playing. */
    void showInfo() {
        final BgSubsQuery q = prefill();
        if (q.title.isEmpty()) {
            toast(activity.getString(R.string.bgsubs_info_no_title));
            return;
        }
        run(R.string.bgsubs_info_loading, c -> {
            try {
                return c.info(q);
            } catch (BgSubsClient.BgSubsException e) {
                if (e.kind == BgSubsClient.BgSubsException.Kind.NOT_FOUND) {
                    throw new BgSubsClient.BgSubsException(e.kind,
                            activity.getString(R.string.bgsubs_info_not_found, describe(q)), true);
                }
                if (e.kind == BgSubsClient.BgSubsException.Kind.UNAVAILABLE) {
                    throw new BgSubsClient.BgSubsException(e.kind,
                            activity.getString(R.string.bgsubs_info_unavailable), true);
                }
                throw e;
            }
        }, infoScreen::show, () -> { });
    }

    /**
     * Called as the player starts a video: if subtitles were saved for this film/episode, set them as the
     * subtitle before the media item is built, so they show from the first frame without a network request.
     */
    void applySaved() {
        if (turnedOff || overlay.isActive() || (activity.apiAccess && !activity.apiSubs.isEmpty())) {
            return;  // already showing ours, switched off, or TiviMate passed its own subtitles
        }
        String key = currentKey();
        BgSubsLibrary.Entry saved = key == null ? null : library.find(key);
        if (saved == null) {
            return;
        }
        try {
            overlay.show(BgSubsCues.parse(readText(saved.file)), saved.offsetMs);
        } catch (IOException e) {
            return;
        }
        loadedKey = key;
        library.touch(key, System.currentTimeMillis());
        String name = saved.offsetMs == 0 ? saved.name
                : saved.name + " (" + BgSubsCues.offsetWords(saved.offsetMs) + ")";
        toast(activity.getString(R.string.bgsubs_saved_loaded, name));
    }

    /** Library key of the video TiviMate is playing, or null when it sent no usable title. */
    private String currentKey() {
        BgSubsQuery q = prefill();
        return q.title.isEmpty() ? null : BgSubsQuery.libraryKey(q, String.valueOf(activity.mPrefs.mediaUri));
    }

    void show() {
        if (overlay.isActive()) {
            showMenu();
        } else if (lastResponse != null) {
            showResults();
        } else {
            showSearch(lastQuery != null ? lastQuery : prefill());
        }
    }

    /** BG pressed while our subtitles are on screen: sync, pick others, or switch them off. */
    private void showMenu() {
        String[] items = {
                activity.getString(R.string.bgsubs_menu_sync, BgSubsCues.offsetWords(overlay.offsetMs())),
                activity.getString(R.string.bgsubs_menu_other),
                activity.getString(R.string.bgsubs_menu_off),
        };
        builder()
                .setTitle(R.string.bgsubs_search_title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        startSync();
                    } else if (which == 1) {
                        if (lastResponse != null) {
                            showResults();
                        } else {
                            showSearch(lastQuery != null ? lastQuery : prefill());
                        }
                    } else {
                        overlay.clear();
                        loadedKey = null;
                        turnedOff = true;
                        toast(activity.getString(R.string.bgsubs_off));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Sync in two steps while the film keeps playing: first OK when the shown line is heard (the offset is
     * computed from that), then ◀ Забави / Избързай ▶ to fine-tune.
     */
    private void startSync() {
        activity.playerView.hideController();
        if (syncBar == null) {
            syncBar = new LinearLayout(activity);
            syncBar.setOrientation(LinearLayout.VERTICAL);
            syncBar.setBackgroundColor(0xDD000000);
            syncBar.setPadding(dp(28), dp(14), dp(28), dp(14));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(720), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = dp(40);
            ((ViewGroup) activity.getWindow().getDecorView()).addView(syncBar, params);
        }
        syncFine = false;
        tapCue = PlayerActivity.player == null ? -1
                : overlay.cues().nextCue(PlayerActivity.player.getCurrentPosition(), overlay.offsetMs());
        updateSyncBar();
    }

    private void updateSyncBar() {
        syncBar.removeAllViews();
        if (!syncFine) {
            syncBar.addView(barText(activity.getString(R.string.bgsubs_tap_title), 16, 0xFFC0C0C0, Gravity.CENTER));
            String line = tapCue < 0 ? activity.getString(R.string.bgsubs_tap_none)
                    : "„" + overlay.cues().text(tapCue).replace('\n', ' ') + "“";
            TextView quote = barText(line, 22, 0xFFFFFFFF, Gravity.CENTER);
            quote.setTypeface(Typeface.DEFAULT_BOLD);
            quote.setPadding(0, dp(6), 0, dp(6));
            syncBar.addView(quote);
            syncBar.addView(barText(activity.getString(R.string.bgsubs_tap_keys), 14, 0xFFC0C0C0, Gravity.CENTER));
            handler.removeCallbacks(autoCloseSync);
            handler.postDelayed(autoCloseSync, 120_000);  // waiting for a line can take a while
        } else {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView late = barText(activity.getString(R.string.bgsubs_fine_late), 20, 0xFFFFFFFF, Gravity.START);
            TextView early = barText(activity.getString(R.string.bgsubs_fine_early), 20, 0xFFFFFFFF, Gravity.END);
            late.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            early.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(late);
            row.addView(early);
            syncBar.addView(row);
            TextView now = barText(activity.getString(R.string.bgsubs_fine_now,
                    BgSubsCues.offsetWords(overlay.offsetMs())), 15, 0xFFC0C0C0, Gravity.CENTER);
            now.setPadding(0, dp(8), 0, 0);
            syncBar.addView(now);
            handler.removeCallbacks(autoCloseSync);
            handler.postDelayed(autoCloseSync, 8000);
        }
    }

    private TextView barText(String text, int sp, int color, int gravity) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        return t;
    }

    private void stopSync() {
        handler.removeCallbacks(autoCloseSync);
        if (syncBar == null) {
            return;
        }
        ((ViewGroup) syncBar.getParent()).removeView(syncBar);
        syncBar = null;
        if (loadedKey != null) {
            library.setOffset(loadedKey, overlay.offsetMs());
        }
        long offset = overlay.offsetMs();
        toast(offset == 0 ? BgSubsCues.syncStatus(0)
                : activity.getString(R.string.bgsubs_sync_saved, BgSubsCues.offsetWords(offset)));
    }

    /** Called first in PlayerActivity.dispatchKeyEvent: while the sync bar is open the remote drives it. */
    boolean onKey(KeyEvent event) {
        if (syncBar == null) {
            return false;
        }
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!ok && !back && !arrow) {
            return false;  // play/pause and other keys keep working
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (back) {
            if (!down) stopSync();
        } else if (!syncFine) {
            if (ok && !down && tapCue >= 0 && PlayerActivity.player != null) {
                overlay.setOffset(clampOffset(overlay.cues().offsetForTap(tapCue, PlayerActivity.player.getCurrentPosition())));
                syncFine = true;
                updateSyncBar();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && down && tapCue >= 0) {
                int next = overlay.cues().nextCueAfter(tapCue);
                if (next >= 0) tapCue = next;
                updateSyncBar();
            } else if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && down) {
                syncFine = true;
                updateSyncBar();
            }
        } else {
            if (ok && !down) {
                stopSync();
            } else if (down && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // ◀ "Забави" shows them later, "Избързай" ▶ earlier (as the user asked). Holding repeats.
                long step = code == KeyEvent.KEYCODE_DPAD_LEFT ? 200 : -200;
                overlay.setOffset(clampOffset(overlay.offsetMs() + step));
                updateSyncBar();
            }
        }
        return true;
    }

    private static long clampOffset(long offsetMs) {
        return Math.max(-600_000, Math.min(600_000, offsetMs));
    }

    /** Forget results and abandon requests in flight; called when a new video starts. */
    void reset() {
        generation++;
        if (client != null) {
            client.cancel();
        }
        lastQuery = null;
        lastResponse = null;
        stopSync();
        overlay.clear();
        loadedKey = null;
        turnedOff = false;
        infoScreen.closeAll();
    }

    private void showSearch(BgSubsQuery query) {
        final int pad = dp(20);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, dp(8), pad, 0);

        final EditText title = field(InputType.TYPE_CLASS_TEXT, query.title);
        layout.addView(label(R.string.bgsubs_field_title));
        layout.addView(title);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText season = field(InputType.TYPE_CLASS_NUMBER, BgSubsQuery.fieldText(query.season));
        final EditText episode = field(InputType.TYPE_CLASS_NUMBER, BgSubsQuery.fieldText(query.episode));
        final EditText year = field(InputType.TYPE_CLASS_NUMBER, BgSubsQuery.fieldText(query.year));
        row.addView(column(R.string.bgsubs_field_season, season));
        row.addView(column(R.string.bgsubs_field_episode, episode));
        row.addView(column(R.string.bgsubs_field_year, year));
        layout.addView(row);

        final String key = currentKey();
        final BgSubsLibrary.Entry saved = key == null ? null : library.find(key);
        if (saved != null) {
            LinearLayout savedRow = new LinearLayout(activity);
            savedRow.setOrientation(LinearLayout.HORIZONTAL);
            savedRow.setPadding(0, dp(12), 0, 0);
            TextView savedText = label(0);
            savedText.setText(activity.getString(R.string.bgsubs_saved_current, saved.name));
            savedText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button forget = new Button(activity);
            forget.setText(R.string.bgsubs_saved_forget);
            forget.setOnClickListener(v -> {
                library.forget(key);
                savedRow.setVisibility(View.GONE);
                toast(activity.getString(R.string.bgsubs_saved_forgotten));
            });
            savedRow.addView(savedText);
            savedRow.addView(forget);
            layout.addView(savedRow);
        }

        final AlertDialog dialog = builder()
                .setTitle(R.string.bgsubs_search_title)
                .setView(layout)
                .setPositiveButton(R.string.bgsubs_search, null)  // click handled below so errors keep the form open
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        final Runnable submit = () -> {
            final BgSubsQuery q;
            try {
                q = BgSubsQuery.fromFields(title.getText().toString(), season.getText().toString(),
                        episode.getText().toString(), year.getText().toString());
            } catch (IllegalArgumentException e) {
                boolean noTitle = title.getText().toString().trim().isEmpty();
                toast(activity.getString(noTitle ? R.string.bgsubs_enter_title : R.string.bgsubs_bad_number));
                (noTitle ? title : season).requestFocus();
                return;
            }
            dialog.dismiss();
            rememberCorrectedTitle(q);
            search(q);
        };
        year.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        year.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit.run();
                return true;
            }
            return false;
        });
        // fields are pre-filled: start on "Търси" and keep the on-screen keyboard closed until a field is picked
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.setOnShowListener(d -> {
            Button searchButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            searchButton.setOnClickListener(v -> submit.run());
            searchButton.requestFocus();
        });
        dialog.show();
    }

    private EditText field(int inputType, String text) {
        EditText e = new EditText(activity);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        e.setText(text);
        e.setSelection(e.getText().length());
        return e;
    }

    private TextView label(int textRes) {
        TextView t = new TextView(activity);
        if (textRes != 0) {
            t.setText(textRes);
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private LinearLayout column(int labelRes, EditText field) {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, 0, dp(16), 0);
        c.addView(label(labelRes));
        c.addView(field);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    private void search(BgSubsQuery query) {
        lastQuery = query;
        run(R.string.bgsubs_searching, c -> c.search(query), response -> {
            lastResponse = response;
            showResults();
        }, () -> showSearch(query));
    }

    private void showResults() {
        final BgSubsClient.SearchResponse response = lastResponse;
        if (response.results.isEmpty()) {
            StringBuilder message = new StringBuilder(activity.getString(R.string.bgsubs_no_results, describe(lastQuery)));
            if (!response.errors.isEmpty()) {
                message.append("\n\n").append(activity.getString(R.string.bgsubs_errors, join(response.errors)));
            }
            builder()
                    .setTitle(R.string.bgsubs_results_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.bgsubs_new_search, (d, w) -> showSearch(lastQuery))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        final AlertDialog dialog = builder()
                .setTitle(activity.getString(R.string.bgsubs_results_count, response.results.size()) + " · " + describe(lastQuery))
                .setAdapter(new ResultsAdapter(response), (d, which) -> {
                    if (which < response.results.size()) {
                        pickFile(response.results.get(which));
                    }
                })
                .setNeutralButton(R.string.bgsubs_new_search, (d, w) -> showSearch(lastQuery))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            // strong blue marker for the row under the D-pad focus; row texts turn white on it
            dialog.getListView().setSelector(new ColorDrawable(SELECTED_ROW));
            dialog.getListView().setDrawSelectorOnTop(false);
        });
        dialog.show();
    }

    private static final int SELECTED_ROW = 0xFF1565C0;
    private static final int PRIMARY_TEXT = 0xFFFFFFFF;
    private static final int DETAIL_TEXT = 0xFFC0C0C0;
    private static final int WARNING_TEXT = 0xFFFFCA28;  // amber: machine translation

    /** Two rows per result: release, then source (own colour) and details; the error line is not selectable. */
    private final class ResultsAdapter extends BaseAdapter {
        private final BgSubsClient.SearchResponse response;

        ResultsAdapter(BgSubsClient.SearchResponse response) {
            this.response = response;
        }

        @Override
        public int getCount() {
            return response.results.size() + (response.errors.isEmpty() ? 0 : 1);
        }

        @Override
        public Object getItem(int position) {
            return position < response.results.size() ? response.results.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return response.errors.isEmpty();
        }

        @Override
        public boolean isEnabled(int position) {
            return position < response.results.size();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(24), dp(10), dp(24), dp(10));
            if (position >= response.results.size()) {
                TextView errors = text(activity.getString(R.string.bgsubs_errors, join(response.errors)), 13, DETAIL_TEXT, false);
                row.addView(errors);
                return row;
            }
            BgSubsClient.Result r = response.results.get(position);
            row.addView(text(r.primaryLine(), 16, PRIMARY_TEXT, false));
            LinearLayout meta = new LinearLayout(activity);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setDuplicateParentStateEnabled(true);
            meta.addView(text(BgSubsClient.sourceName(r.source), 13, sourceColor(r.source), true));
            String details = r.detailLine();
            if (!details.isEmpty()) {
                meta.addView(text("  ·  " + details, 13, r.machineTranslated ? WARNING_TEXT : DETAIL_TEXT, false));
            }
            row.addView(meta);
            return row;
        }

        /** Text that turns white when its row is the selected one. */
        private TextView text(String value, int sp, int color, boolean bold) {
            TextView t = new TextView(activity);
            t.setText(value);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_pressed}, {}},
                    new int[]{PRIMARY_TEXT, PRIMARY_TEXT, color}));
            if (bold) {
                t.setTypeface(Typeface.DEFAULT_BOLD);
            }
            t.setDuplicateParentStateEnabled(true);
            return t;
        }
    }

    private static int sourceColor(String source) {
        if (source == null) return DETAIL_TEXT;
        switch (source) {
            case "sabbz": return 0xFF81D4FA;          // light blue
            case "unacs": return 0xFFA5D6A7;          // green
            case "subdl": return 0xFFFFCC80;          // orange
            case "opensubtitles": return 0xFFEF9A9A;  // red
            case "subsource": return 0xFFCE93D8;      // purple
            case "subsland": return 0xFF80CBC4;       // teal
            default: return DETAIL_TEXT;
        }
    }

    private void pickFile(BgSubsClient.Result result) {
        run(R.string.bgsubs_loading, c -> c.files(result.id), files -> {
            if (files.size() == 1) {
                download(result, files.get(0));
                return;
            }
            String[] names = new String[files.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = files.get(i).name;
            }
            builder()
                    .setTitle(R.string.bgsubs_files_title)
                    .setItems(names, (d, which) -> download(result, files.get(which)))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> showResults())
                    .show();
        }, this::showResults);
    }

    private void download(BgSubsClient.Result result, BgSubsClient.FileEntry file) {
        run(R.string.bgsubs_loading, c -> c.download(result.id, file.n), bytes -> {
            apply(file.name, bytes);
            remember(file.name, bytes);
        }, this::showResults);
    }

    /** Keeps the chosen subtitle for this film/episode; it then loads by itself next time. */
    private void remember(String name, byte[] bytes) {
        String key = currentKey();
        if (key == null) {
            return;
        }
        try {
            library.save(key, name, bytes, System.currentTimeMillis());
        } catch (IOException e) {
            toast(activity.getString(R.string.bgsubs_error_server, e.getMessage()));
        }
    }

    /** Shows the chosen subtitle at once (no reload of the video); timing starts at 0 for a new file. */
    private void apply(String name, byte[] bytes) {
        turnedOff = false;
        overlay.show(BgSubsCues.parse(new String(bytes, StandardCharsets.UTF_8)), 0);
        loadedKey = currentKey();
        toast(activity.getString(R.string.bgsubs_loaded, name));
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Runs `work` off the UI thread behind a cancellable progress dialog; results arrive on the UI thread. */
    private <T> void run(int messageRes, Work<T> work, Done<T> done, Runnable afterError) {
        final BgSubsClient c = newClient();
        client = c;
        final int gen = ++generation;
        final AlertDialog progress = builder()
                .setMessage(messageRes)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    generation++;
                    c.cancel();
                })
                .show();
        executor.execute(() -> {
            try {
                T value = work.run(c);
                post(gen, progress, () -> done.accept(value));
            } catch (BgSubsClient.BgSubsException e) {
                post(gen, progress, () -> showError(e, afterError, () -> run(messageRes, work, done, afterError)));
            } catch (RuntimeException e) {
                // never let an unexpected error kill the player mid-film
                BgSubsClient.BgSubsException wrapped = new BgSubsClient.BgSubsException(
                        BgSubsClient.BgSubsException.Kind.SERVER, String.valueOf(e.getMessage()));
                post(gen, progress, () -> showError(wrapped, afterError, null));
            }
        });
    }

    private void post(int gen, AlertDialog progress, Runnable action) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            progress.dismiss();
            if (gen == generation) {  // otherwise cancelled or a new video started meanwhile
                action.run();
            }
        });
    }

    /** retry repeats the failed request; used after logging in with the password. */
    private void showError(BgSubsClient.BgSubsException e, Runnable afterError, Runnable retry) {
        if (e.kind == BgSubsClient.BgSubsException.Kind.NO_TOKEN
                || e.kind == BgSubsClient.BgSubsException.Kind.UNAUTHORIZED) {
            boolean expired = e.kind == BgSubsClient.BgSubsException.Kind.UNAUTHORIZED;
            if (expired) {
                prefs().edit().remove(PREF_TOKEN).apply();  // revoked or password changed on the server
            }
            showLogin(expired ? activity.getString(R.string.bgsubs_login_expired) : null, retry);
            return;
        }
        AlertDialog.Builder builder = builder().setTitle(R.string.bgsubs_search_title);
        switch (e.kind) {
            case BAD_SERVER:
                builder.setMessage(R.string.bgsubs_error_bad_server)
                        .setPositiveButton(R.string.bgsubs_settings,
                                (d, w) -> activity.startActivity(new Intent(activity, SettingsActivity.class)))
                        .setNegativeButton(android.R.string.cancel, null);
                break;
            case NETWORK:
                builder.setMessage(R.string.bgsubs_error_network)
                        .setPositiveButton(android.R.string.ok, (d, w) -> afterError.run());
                break;
            default:
                builder.setMessage(e.forViewer ? e.getMessage()
                                : activity.getString(R.string.bgsubs_error_server, e.getMessage()))
                        .setPositiveButton(android.R.string.ok, (d, w) -> afterError.run());
        }
        builder.show();
    }

    private BgSubsClient newClient() {
        return new BgSubsClient(server(), prefs().getString(PREF_TOKEN, ""), http);
    }

    private String server() {
        String server = prefs().getString(PREF_SERVER, DEFAULT_SERVER);
        return server == null || server.trim().isEmpty() ? DEFAULT_SERVER : server;
    }

    /** Asks for the server's password once; the device token it returns is kept, the password is not. */
    private void showLogin(String message, Runnable retry) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), 0);
        if (message != null) {
            TextView m = label(0);
            m.setText(message);
            layout.addView(m);
        }
        final EditText password = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, "");
        password.setHint(R.string.bgsubs_login_hint);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        layout.addView(password);

        final AlertDialog dialog = builder()
                .setTitle(R.string.bgsubs_login_title)
                .setView(layout)
                .setPositiveButton(R.string.bgsubs_login_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        final Runnable submit = () -> {
            dialog.dismiss();
            login(password.getText().toString(), retry);
        };
        password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run()));
        dialog.show();
    }

    private void login(String password, Runnable retry) {
        final AlertDialog progress = builder().setMessage(R.string.bgsubs_login_progress).setCancelable(false).show();
        final String device = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        final String server = server();
        executor.execute(() -> {
            String token = null;
            BgSubsClient.BgSubsException error = null;
            try {
                token = BgSubsClient.login(server, password, device, http);
            } catch (BgSubsClient.BgSubsException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new BgSubsClient.BgSubsException(BgSubsClient.BgSubsException.Kind.SERVER, String.valueOf(e.getMessage()));
            }
            final String result = token;
            final BgSubsClient.BgSubsException failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                progress.dismiss();
                if (result != null) {
                    prefs().edit().putString(PREF_TOKEN, result).apply();
                    toast(activity.getString(R.string.bgsubs_login_ok));
                    if (retry != null) {
                        retry.run();
                    }
                } else {
                    showLogin(loginMessage(failure), retry);
                }
            });
        });
    }

    private String loginMessage(BgSubsClient.BgSubsException e) {
        switch (e.kind) {
            case WRONG_PASSWORD: return activity.getString(R.string.bgsubs_login_wrong);
            case RATE_LIMITED: return activity.getString(R.string.bgsubs_login_blocked);
            case LOGIN_DISABLED: return activity.getString(R.string.bgsubs_login_disabled);
            case NETWORK: return activity.getString(R.string.bgsubs_error_network);
            case BAD_SERVER: return activity.getString(R.string.bgsubs_error_bad_server);
            default: return activity.getString(R.string.bgsubs_error_server, e.getMessage());
        }
    }

    /** Fields from what TiviMate sent ("Black Doves - S01E02 - …"), with a remembered name correction applied. */
    private BgSubsQuery prefill() {
        BgSubsQuery q = tivimateQuery();
        if (q == null) {
            return new BgSubsQuery("", null, null, null);
        }
        String corrected = prefs().getString(ALIAS_PREFIX + BgSubsQuery.aliasKey(q.title), null);
        return corrected == null ? q : new BgSubsQuery(corrected, q.year, q.season, q.episode);
    }

    /** TiviMate's title, or the stream's file name when there is none; null if nothing usable. */
    private BgSubsQuery tivimateQuery() {
        String raw = activity.apiTitle;
        if (raw == null || raw.trim().isEmpty()) {
            Uri media = activity.mPrefs.mediaUri;
            raw = media == null ? null : media.getLastPathSegment();
            if (raw != null && raw.lastIndexOf('.') > 0) {
                raw = raw.substring(0, raw.lastIndexOf('.'));
            }
        }
        try {
            return raw == null ? null : BgSubsQuery.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "Black Doves" typed as "Black Doves 2024" → used for the next episodes too; typing it back forgets it. */
    private void rememberCorrectedTitle(BgSubsQuery searched) {
        BgSubsQuery original = tivimateQuery();
        if (original == null) {
            return;
        }
        String key = ALIAS_PREFIX + BgSubsQuery.aliasKey(original.title);
        if (BgSubsQuery.aliasKey(searched.title).equals(BgSubsQuery.aliasKey(original.title))) {
            prefs().edit().remove(key).apply();
        } else {
            prefs().edit().putString(key, searched.title).apply();
        }
    }

    private static String describe(BgSubsQuery q) {
        if (q == null) {
            return "";
        }
        if (q.season != null) {
            return q.episode != null
                    ? String.format(Locale.ROOT, "%s S%02dE%02d", q.title, q.season, q.episode)
                    : String.format(Locale.ROOT, "%s S%02d", q.title, q.season);
        }
        return q.year != null ? q.title + " " + q.year : q.title;
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(activity);
    }

    private AlertDialog.Builder builder() {
        // own dark theme: the TV's default dialog theme is light grey and washed out the result colours
        return new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
    }

    private static String join(List<String> items) {
        return android.text.TextUtils.join(", ", items);
    }

    private void toast(String text) {
        Toast toast = Toast.makeText(activity, text, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, dp(64));
        toast.show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}

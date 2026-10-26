package com.brouken.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    BgSubsDialog(PlayerActivity activity) {
        this.activity = activity;
        this.library = new BgSubsLibrary(new File(activity.getFilesDir(), "bgsubs-library"), 300);
    }

    /**
     * Called as the player starts a video: if subtitles were saved for this film/episode, set them as the
     * subtitle before the media item is built, so they show from the first frame without a network request.
     */
    void applySaved() {
        if (activity.mPrefs.subtitleUri != null || (activity.apiAccess && !activity.apiSubs.isEmpty())) {
            return;  // already has a subtitle (ours or TiviMate's)
        }
        String key = currentKey();
        BgSubsLibrary.Entry saved = key == null ? null : library.find(key);
        if (saved == null) {
            return;
        }
        activity.mPrefs.updateSubtitle(Uri.fromFile(saved.file));
        library.touch(key, System.currentTimeMillis());
        toast(activity.getString(R.string.bgsubs_saved_loaded, saved.name));
    }

    /** Library key of the video TiviMate is playing, or null when it sent no usable title. */
    private String currentKey() {
        BgSubsQuery q = prefill();
        return q.title.isEmpty() ? null : BgSubsQuery.libraryKey(q, String.valueOf(activity.mPrefs.mediaUri));
    }

    void show() {
        if (lastResponse != null) {
            showResults();
        } else {
            showSearch(lastQuery != null ? lastQuery : prefill());
        }
    }

    /** Forget results and abandon requests in flight; called when a new video starts. */
    void reset() {
        generation++;
        if (client != null) {
            client.cancel();
        }
        lastQuery = null;
        lastResponse = null;
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
                meta.addView(text("  ·  " + details, 13, DETAIL_TEXT, false));
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
            try {
                apply(write(file.name, bytes), file.name);
                remember(file.name, bytes);
            } catch (IOException e) {
                toast(activity.getString(R.string.bgsubs_error_server, e.getMessage()));
            }
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

    private File write(String name, byte[] bytes) throws IOException {
        File dir = new File(activity.getCacheDir(), "bgsubs");
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.toLowerCase().endsWith(".srt")) {
            safe += ".srt";
        }
        File out = new File(dir, safe);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(bytes);
        }
        return out;
    }

    // same steps as SubtitleFetcher: remember the subtitle and swap it into the current item, keeping the position
    private void apply(File file, String name) {
        Uri uri = Uri.fromFile(file);
        activity.mPrefs.updateSubtitle(uri);
        if (PlayerActivity.player != null) {
            MediaItem mediaItem = PlayerActivity.player.getCurrentMediaItem();
            if (mediaItem != null) {
                MediaItem.SubtitleConfiguration subtitle = SubtitleUtils.buildSubtitle(activity, uri, name, true);
                mediaItem = mediaItem.buildUpon().setSubtitleConfigurations(Collections.singletonList(subtitle)).build();
                PlayerActivity.player.setMediaItem(mediaItem, false);
            }
        }
        toast(activity.getString(R.string.bgsubs_loaded, name));
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
                post(gen, progress, () -> showError(e, afterError));
            } catch (RuntimeException e) {
                // never let an unexpected error kill the player mid-film
                BgSubsClient.BgSubsException wrapped = new BgSubsClient.BgSubsException(
                        BgSubsClient.BgSubsException.Kind.SERVER, String.valueOf(e.getMessage()));
                post(gen, progress, () -> showError(wrapped, afterError));
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

    private void showError(BgSubsClient.BgSubsException e, Runnable afterError) {
        AlertDialog.Builder builder = builder().setTitle(R.string.bgsubs_search_title);
        switch (e.kind) {
            case NO_TOKEN:
                builder.setMessage(R.string.bgsubs_error_no_token)
                        .setPositiveButton(R.string.bgsubs_settings,
                                (d, w) -> activity.startActivity(new Intent(activity, SettingsActivity.class)))
                        .setNegativeButton(android.R.string.cancel, null);
                break;
            case BAD_SERVER:
                builder.setMessage(R.string.bgsubs_error_bad_server)
                        .setPositiveButton(R.string.bgsubs_settings,
                                (d, w) -> activity.startActivity(new Intent(activity, SettingsActivity.class)))
                        .setNegativeButton(android.R.string.cancel, null);
                break;
            case UNAUTHORIZED:
                builder.setMessage(R.string.bgsubs_error_unauthorized)
                        .setPositiveButton(R.string.bgsubs_settings,
                                (d, w) -> activity.startActivity(new Intent(activity, SettingsActivity.class)))
                        .setNegativeButton(android.R.string.cancel, null);
                break;
            case NETWORK:
                builder.setMessage(R.string.bgsubs_error_network)
                        .setPositiveButton(android.R.string.ok, (d, w) -> afterError.run());
                break;
            default:
                builder.setMessage(activity.getString(R.string.bgsubs_error_server, e.getMessage()))
                        .setPositiveButton(android.R.string.ok, (d, w) -> afterError.run());
        }
        builder.show();
    }

    private BgSubsClient newClient() {
        SharedPreferences prefs = prefs();
        String server = prefs.getString(PREF_SERVER, DEFAULT_SERVER);
        if (server == null || server.trim().isEmpty()) {
            server = DEFAULT_SERVER;
        }
        return new BgSubsClient(server, prefs.getString(PREF_TOKEN, ""), http);
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

package com.brouken.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;
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

    BgSubsDialog(PlayerActivity activity) {
        this.activity = activity;
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
        t.setText(textRes);
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
        List<CharSequence> labels = new ArrayList<>();
        for (BgSubsClient.Result r : response.results) {
            labels.add(twoLineLabel(r));
        }
        if (!response.errors.isEmpty()) {
            labels.add(activity.getString(R.string.bgsubs_errors, join(response.errors)));
        }
        builder()
                .setTitle(activity.getString(R.string.bgsubs_results_count, response.results.size()))
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    if (which < response.results.size()) {
                        pickFile(response.results.get(which));
                    } else {
                        showResults();  // the error line is not selectable
                    }
                })
                .setNeutralButton(R.string.bgsubs_new_search, (d, w) -> showSearch(lastQuery))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Release on the first row; smaller grey second row starting with the source name in its own colour. */
    private static CharSequence twoLineLabel(BgSubsClient.Result r) {
        SpannableStringBuilder sb = new SpannableStringBuilder(r.primaryLine());
        int metaStart = sb.length() + 1;
        sb.append('\n').append(r.metaLine());
        int end = sb.length();
        sb.setSpan(new RelativeSizeSpan(0.8f), metaStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF9E9E9E), metaStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int nameEnd = metaStart + BgSubsClient.sourceName(r.source).length();
        sb.setSpan(new ForegroundColorSpan(sourceColor(r.source)), metaStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), metaStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static int sourceColor(String source) {
        if (source == null) return 0xFF9E9E9E;
        switch (source) {
            case "sabbz": return 0xFF4FC3F7;          // light blue
            case "unacs": return 0xFF81C784;          // green
            case "subdl": return 0xFFFFB74D;          // orange
            case "opensubtitles": return 0xFFE57373;  // red
            case "subsource": return 0xFFBA68C8;      // purple
            default: return 0xFF9E9E9E;
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
            } catch (IOException e) {
                toast(activity.getString(R.string.bgsubs_error_server, e.getMessage()));
            }
        }, this::showResults);
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

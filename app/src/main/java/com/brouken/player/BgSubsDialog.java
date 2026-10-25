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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/** "BG" button flow: search bgsubs, pick a result (and a file), load the subtitle into the current video. */
final class BgSubsDialog {

    static final String PREF_SERVER = "bgsubs_server";
    static final String PREF_TOKEN = "bgsubs_token";
    static final String DEFAULT_SERVER = "https://subs.dushkov.eu";

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
    private String lastInput;
    private BgSubsClient.SearchResponse lastResponse;

    BgSubsDialog(PlayerActivity activity) {
        this.activity = activity;
    }

    void show() {
        if (lastResponse != null) {
            showResults();
        } else {
            showSearch(prefill());
        }
    }

    /** Forget results and abandon requests in flight; called when a new video starts. */
    void reset() {
        generation++;
        if (client != null) {
            client.cancel();
        }
        lastInput = null;
        lastResponse = null;
    }

    private void showSearch(String text) {
        final int pad = dp(20);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, dp(8), pad, 0);

        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint(R.string.bgsubs_search_hint);
        input.setText(text);
        input.setSelection(input.getText().length());
        layout.addView(input);

        TextView extras = new TextView(activity);
        extras.setText(tivimateInfo());
        extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        extras.setAlpha(0.6f);
        extras.setPadding(0, dp(8), 0, 0);
        layout.addView(extras);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.bgsubs_search_title)
                .setView(layout)
                .setPositiveButton(R.string.bgsubs_search, (d, w) -> search(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss();
                search(input.getText().toString());
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private void search(String text) {
        final BgSubsQuery query;
        try {
            query = BgSubsQuery.parse(text);
        } catch (IllegalArgumentException e) {
            toast(activity.getString(R.string.bgsubs_enter_title));
            showSearch(text);
            return;
        }
        lastInput = text;
        run(R.string.bgsubs_searching, c -> c.search(query), response -> {
            lastResponse = response;
            showResults();
        }, () -> showSearch(text));
    }

    private void showResults() {
        final BgSubsClient.SearchResponse response = lastResponse;
        if (response.results.isEmpty()) {
            StringBuilder message = new StringBuilder(activity.getString(R.string.bgsubs_no_results, lastInput));
            if (!response.errors.isEmpty()) {
                message.append("\n\n").append(activity.getString(R.string.bgsubs_errors, join(response.errors)));
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.bgsubs_results_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.bgsubs_new_search, (d, w) -> showSearch(lastInput))
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
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.bgsubs_results_count, response.results.size()))
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    if (which < response.results.size()) {
                        pickFile(response.results.get(which));
                    } else {
                        showResults();  // the error line is not selectable
                    }
                })
                .setNeutralButton(R.string.bgsubs_new_search, (d, w) -> showSearch(lastInput))
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
            new AlertDialog.Builder(activity)
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
        final AlertDialog progress = new AlertDialog.Builder(activity)
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
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(R.string.bgsubs_search_title);
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        String server = prefs.getString(PREF_SERVER, DEFAULT_SERVER);
        if (server == null || server.trim().isEmpty()) {
            server = DEFAULT_SERVER;
        }
        return new BgSubsClient(server, prefs.getString(PREF_TOKEN, ""), http);
    }

    private String prefill() {
        if (activity.apiTitle != null && !activity.apiTitle.trim().isEmpty()) {
            return activity.apiTitle.trim();
        }
        Uri media = activity.mPrefs.mediaUri;
        String segment = media == null ? null : media.getLastPathSegment();
        if (segment == null) {
            return "";
        }
        int dot = segment.lastIndexOf('.');
        if (dot > 0) {
            segment = segment.substring(0, dot);
        }
        return segment.replace('.', ' ').replace('_', ' ').trim();
    }

    private String tivimateInfo() {
        Uri media = activity.mPrefs.mediaUri;
        String uri = media == null ? "" : media.toString();
        if (uri.length() > 60) {
            uri = "…" + uri.substring(uri.length() - 60);
        }
        return "TiviMate: title=\"" + (activity.apiTitle == null ? "" : activity.apiTitle) + "\", uri=" + uri;
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

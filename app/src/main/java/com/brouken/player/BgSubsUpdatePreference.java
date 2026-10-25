package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.Preference;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/** "Check for update" row in Settings → BG субтитри: compares with the latest GitHub release and installs it. */
@Keep
public class BgSubsUpdatePreference extends Preference {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final OkHttpClient HTTP = new OkHttpClient();

    private final Handler main = new Handler(Looper.getMainLooper());

    public BgSubsUpdatePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public BgSubsUpdatePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public BgSubsUpdatePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BgSubsUpdatePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setPersistent(false);
        setSummary(getContext().getString(R.string.bgsubs_update_installed, BgSubsUpdater.installedVersionName(getContext())));
    }

    @Override
    protected void onClick() {
        final Context context = getContext();
        final AlertDialog progress = new AlertDialog.Builder(context)
                .setMessage(R.string.bgsubs_update_checking)
                .setCancelable(false)
                .show();
        EXECUTOR.execute(() -> {
            try {
                BgSubsUpdater.Release release = BgSubsUpdater.fetchLatest(HTTP);
                onMain(() -> {
                    progress.dismiss();
                    offer(release);
                });
            } catch (Exception e) {
                onMain(() -> {
                    progress.dismiss();
                    error(e);
                });
            }
        });
    }

    private void offer(BgSubsUpdater.Release release) {
        Context context = getContext();
        if (!release.isNewerThan(BgSubsUpdater.installedVersionCode(context))) {
            new AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.bgsubs_update_latest, BgSubsUpdater.installedVersionName(context)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String size = release.size > 0 ? String.format(java.util.Locale.ROOT, "%.0f", release.size / 1_048_576.0) : "?";
        new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.bgsubs_update_available, release.versionName, size))
                .setPositiveButton(R.string.bgsubs_update_install, (d, w) -> install(release))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void install(BgSubsUpdater.Release release) {
        final Context context = getContext();
        if (!BgSubsUpdater.canInstall(context)) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.bgsubs_update_allow)
                    .setPositiveButton(R.string.bgsubs_settings, (d, w) -> openUnknownSourcesSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.bgsubs_update_downloading, 0))
                .setCancelable(false)
                .show();
        EXECUTOR.execute(() -> {
            try {
                BgSubsUpdater.install(context, HTTP, release, percent -> onMain(() ->
                        progress.setMessage(context.getString(R.string.bgsubs_update_downloading, percent))));
                onMain(progress::dismiss);  // Android's own "Install?" dialog follows
            } catch (Exception e) {
                onMain(() -> {
                    progress.dismiss();
                    error(e);
                });
            }
        });
    }

    private void openUnknownSourcesSettings() {
        Context context = getContext();
        try {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.bgsubs_update_allow_manual, Toast.LENGTH_LONG).show();
        }
    }

    private void error(Exception e) {
        new AlertDialog.Builder(getContext())
                .setMessage(getContext().getString(R.string.bgsubs_update_failed, String.valueOf(e.getMessage())))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Runs on the UI thread unless the settings screen is already gone. */
    private void onMain(Runnable action) {
        main.post(() -> {
            Context context = getContext();
            if (context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed())) {
                return;
            }
            action.run();
        });
    }
}

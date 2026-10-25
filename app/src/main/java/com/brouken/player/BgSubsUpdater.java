package com.brouken.player;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Checks GitHub Releases of Dushkov/Player for a newer Just Player BG and installs it. */
final class BgSubsUpdater {

    static final String LATEST_URL = "https://api.github.com/repos/Dushkov/Player/releases/latest";
    static final String APK_NAME = "JustPlayerBG.apk";
    private static final String RELEASE_NAME_PREFIX = "Just Player BG ";
    private static final String ACTION_INSTALL_STATUS = "com.brouken.player.bgsubs.INSTALL_STATUS";
    // tag written by bgsubs-release.yml: bg-v0.<upstream versionCode>.<run number>
    private static final Pattern TAG = Pattern.compile("^bg-v0\\.(\\d+)\\.(\\d+)$");

    private static boolean statusReceiverRegistered;

    interface Progress {
        void onProgress(int percent);
    }

    private BgSubsUpdater() {
    }

    /** Same formula as the release workflow: upstream versionCode * 1000 + run number. */
    static Long versionCodeFromTag(String tag) {
        if (tag == null) {
            return null;
        }
        Matcher m = TAG.matcher(tag);
        return m.matches() ? Long.parseLong(m.group(1)) * 1000 + Long.parseLong(m.group(2)) : null;
    }

    static Release parseLatest(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        String tag = root.getString("tag_name");
        Long code = versionCodeFromTag(tag);
        if (code == null) {
            throw new JSONException("unexpected release tag " + tag);
        }
        Release release = new Release();
        release.versionCode = code;
        String name = root.optString("name", "");
        release.versionName = name.startsWith(RELEASE_NAME_PREFIX) ? name.substring(RELEASE_NAME_PREFIX.length()) : tag;
        JSONArray assets = root.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (APK_NAME.equals(asset.optString("name"))) {
                release.apkUrl = asset.getString("browser_download_url");
                release.size = asset.optLong("size", -1);
            }
        }
        if (release.apkUrl == null) {
            throw new JSONException("release " + tag + " has no " + APK_NAME);
        }
        return release;
    }

    static Release fetchLatest(OkHttpClient http) throws IOException, JSONException {
        Request request = new Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GitHub HTTP " + response.code());
            }
            return parseLatest(response.body().string());
        }
    }

    static long installedVersionCode(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null) {
            return 0;
        }
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static String installedVersionName(Context context) {
        PackageInfo info = packageInfo(context);
        return info == null || info.versionName == null ? "?" : info.versionName;
    }

    /** Android 8+ asks once whether this app may install apps ("unknown sources"). */
    static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it. Android then shows its own
     * "Install?" confirmation; the result arrives in the status receiver. Blocking: call off the UI thread.
     */
    static void install(Context context, OkHttpClient http, Release release, Progress progress) throws IOException {
        Context app = context.getApplicationContext();
        registerStatusReceiver(app);
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            Request request = new Request.Builder().url(release.apkUrl).build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("download HTTP " + response.code());
                }
                long total = response.body().contentLength() > 0 ? response.body().contentLength() : release.size;
                try (InputStream in = response.body().byteStream();
                     OutputStream out = session.openWrite(APK_NAME, 0, total > 0 ? total : -1)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) {
                            int percent = (int) (done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                progress.onProgress(percent);
                            }
                        }
                    }
                    session.fsync(out);
                }
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(app, sessionId, status, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    private static synchronized void registerStatusReceiver(Context app) {
        if (statusReceiverRegistered) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirm);
                    }
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Toast.makeText(context, context.getString(R.string.bgsubs_update_failed,
                            message == null ? String.valueOf(status) : message), Toast.LENGTH_LONG).show();
                }
            }
        };
        ContextCompat.registerReceiver(app, receiver, new IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        statusReceiverRegistered = true;
    }

    private static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    static final class Release {
        long versionCode;
        String versionName;
        String apkUrl;
        long size;

        boolean isNewerThan(long installedVersionCode) {
            return versionCode > installedVersionCode;
        }
    }
}

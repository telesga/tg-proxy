/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TelezhkaPopup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Looks for a newer Telezhka build in the GitHub releases (at start and every 6 hours),
 * shows the update popup, downloads the APK inside the app and hands it to the Android installer.
 * Releases are numbered one after another (build-N); BuildVars.TELEZHKA_BUILD is filled in by the workflow.
 * Test builds are draft releases, which releases/latest never returns, so they are never offered here.
 */
public class TelezhkaUpdater {

    // update.json is a release file: it is served without the GitHub API limit of 60 requests an hour,
    // which people behind a shared VPN address run out of. The API stays as a fallback.
    private static final String UPDATE_JSON_URL = "https://github.com/" + BuildVars.GITHUB_REPO + "/releases/latest/download/update.json";
    private static final String RELEASES_URL = "https://api.github.com/repos/" + BuildVars.GITHUB_REPO + "/releases/latest";
    private static final long CHECK_PERIOD_MS = 60 * 60 * 1000L;
    private static final String PREFS = "telezhka_updater";
    private static final String KEY_SKIPPED = "skipped_build";
    private static final int MAX_ITEMS = 4;  // lines shown in the popup

    private static boolean checking;
    private static long lastCheck;
    private static boolean shownThisLaunch;
    private static Release pending;

    private static class Release {
        int build;
        String apkUrl;
        long size;
        ArrayList<String> items = new ArrayList<>();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void check(BaseFragment fragment) {
        if (BuildVars.TELEZHKA_BUILD <= 0 || checking) {
            return;  // local builds have no number to compare
        }
        if (pending != null) {
            show(fragment, pending);
            return;
        }
        // at every start of the app, then at most once an hour while it runs
        long now = android.os.SystemClock.elapsedRealtime();
        if (shownThisLaunch || lastCheck != 0 && now - lastCheck < CHECK_PERIOD_MS) {
            return;
        }
        fetchAndShow(fragment, false, null);
    }

    /** "Проверить обновления" in Telezhka settings: asks now, shows the popup even for a build skipped with «Позже». */
    public static void checkNow(BaseFragment fragment, Utilities.Callback<Boolean> done) {
        if (checking) {
            return;
        }
        shownThisLaunch = false;
        fetchAndShow(fragment, true, done);
    }

    private static void fetchAndShow(BaseFragment fragment, boolean manual, Utilities.Callback<Boolean> done) {
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            Release release = fetch();
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (release == null) {
                    if (done != null) {
                        done.run(null);
                    }
                    return;
                }
                lastCheck = android.os.SystemClock.elapsedRealtime();
                boolean newer = BuildVars.TELEZHKA_BUILD > 0 && release.build > BuildVars.TELEZHKA_BUILD;
                if (newer && (manual || release.build != prefs().getInt(KEY_SKIPPED, 0))) {
                    pending = release;
                    show(fragment, release);
                }
                if (done != null) {
                    done.run(newer);
                }
            });
        });
    }

    public static boolean isPopupShownThisLaunch() {
        return shownThisLaunch;
    }

    public static int latestKnownBuild() {
        return pending != null ? pending.build : 0;
    }

    private static Release fetch() {
        Release release = fetchUpdateJson();
        return release != null ? release : fetchApi();
    }

    // {"build":25,"apk":"https://…/Telezhka-25.apk","size":60000000,"notes":["…"]}
    private static Release fetchUpdateJson() {
        String json = get(UPDATE_JSON_URL);
        if (json == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            Release release = new Release();
            release.build = root.getInt("build");
            release.apkUrl = root.getString("apk");
            release.size = root.optLong("size", 0);
            JSONArray notes = root.optJSONArray("notes");
            for (int i = 0; notes != null && i < notes.length() && release.items.size() < MAX_ITEMS; i++) {
                String note = notes.optString(i, "").trim();
                if (!note.isEmpty()) {
                    release.items.add(note);
                }
            }
            return release;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Release fetchApi() {
        String json = get(RELEASES_URL);
        if (json == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            String tag = root.optString("tag_name", "");
            if (!tag.startsWith("build-")) {
                return null;
            }
            Release release = new Release();
            release.build = Integer.parseInt(tag.substring("build-".length()));
            JSONArray assets = root.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name", "").endsWith(".apk")) {
                    release.apkUrl = asset.optString("browser_download_url", null);
                    release.size = asset.optLong("size", 0);
                    break;
                }
            }
            if (TextUtils.isEmpty(release.apkUrl)) {
                return null;
            }
            // "- point" lines of the release notes become the list in the popup
            for (String line : root.optString("body", "").split("\n")) {
                line = line.trim();
                if ((line.startsWith("- ") || line.startsWith("• ")) && release.items.size() < MAX_ITEMS) {
                    release.items.add(line.substring(2).trim());
                }
            }
            return release;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String get(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Telezhka");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > 512 * 1024) {
                        return null;
                    }
                }
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576f).replace('.', ',');
    }

    private static void show(BaseFragment fragment, Release release) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || shownThisLaunch) {
            return;
        }
        shownThisLaunch = true;
        TelezhkaPopup popup = new TelezhkaPopup(activity);
        popup.setCancelableOutside(false);
        popup.setUpdateHero(release.size > 0 ? LocaleController.formatString(R.string.TelezhkaUpdateSize, mb(release.size)) : null)
                .setLabel(LocaleController.getString(R.string.TelezhkaUpdateLabel))
                .setTitleText(LocaleController.formatString(R.string.TelezhkaUpdateTitle, release.build));
        if (release.items.isEmpty()) {
            popup.addDotItem(LocaleController.getString(R.string.TelezhkaUpdateDefaultItem));
        }
        for (String item : release.items) {
            popup.addDotItem(item);
        }
        popup.setMainButton(LocaleController.getString(R.string.TelezhkaUpdateButton), v -> download(fragment, popup, release))
                .setSecondaryButton(LocaleController.getString(R.string.TelezhkaUpdateLater), v -> {
                    prefs().edit().putInt(KEY_SKIPPED, release.build).apply();
                    pending = null;
                    popup.dismiss();
                });
        fragment.showDialog(popup);
    }

    private static File apkFile(int build) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        dir.mkdirs();
        return new File(dir, "Telezhka-" + build + ".apk");
    }

    private static void download(BaseFragment fragment, TelezhkaPopup popup, Release release) {
        File file = apkFile(release.build);
        if (file.exists() && release.size > 0 && file.length() == release.size) {
            install(fragment, popup, file);
            return;
        }
        popup.showProgress(true);
        popup.setProgress(0, LocaleController.getString(R.string.TelezhkaUpdateDownloading), LocaleController.formatString(R.string.TelezhkaUpdateProgress, mb(0), mb(release.size)));
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            File part = new File(file.getPath() + ".part");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Telezhka");
                long total = connection.getContentLength() > 0 ? connection.getContentLength() : release.size;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0, lastPost = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        done += read;
                        if (done - lastPost > 256 * 1024 || done == total) {
                            lastPost = done;
                            final long d = done;
                            AndroidUtilities.runOnUIThread(() -> popup.setProgress(total > 0 ? d / (float) total : 0,
                                    LocaleController.getString(R.string.TelezhkaUpdateDownloading),
                                    LocaleController.formatString(R.string.TelezhkaUpdateProgress, mb(d), mb(total))));
                        }
                    }
                    ok = total <= 0 || done == total;
                }
                ok = ok && part.renameTo(file);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!ok) {
                    part.delete();
                }
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (success) {
                    popup.setProgress(1f, LocaleController.getString(R.string.TelezhkaUpdateReady), "");
                    install(fragment, popup, file);
                } else {
                    popup.showProgress(false);
                    popup.setMainButton(LocaleController.getString(R.string.TelezhkaUpdateButton), v -> download(fragment, popup, release));
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TelezhkaUpdateFailed)).show();
                }
            });
        });
    }

    private static void install(BaseFragment fragment, TelezhkaPopup popup, File file) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        popup.showProgress(false);
        popup.setMainButton(LocaleController.getString(R.string.TelezhkaUpdateInstall), v -> install(fragment, popup, file));
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.TelezhkaUpdateAllow)).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

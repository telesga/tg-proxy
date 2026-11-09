/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * "Оформление Тележки": colour themes, the message font (built-in or the user's own .ttf/.otf files) and what to hide.
 * The themes are colour variants of telezhka.attheme (made by .github/scripts/telezhka_themes.py);
 * the chosen one is put into the "Night" theme, so the rest of Telegram sees one dark theme as before.
 */
public class TelezhkaAppearance {

    public static final String[] THEME_ASSETS = {
            "telezhka.attheme", "telezhka_midnight.attheme", "telezhka_mint.attheme", "telezhka_lavender.attheme", "telezhka_amoled.attheme"
    };
    public static final int[] THEME_NAMES = {
            R.string.TelezhkaThemeGraphite, R.string.TelezhkaThemeMidnight, R.string.TelezhkaThemeMint, R.string.TelezhkaThemeLavender, R.string.TelezhkaThemeAmoled
    };
    // background and accent of each theme, for the swatches
    public static final int[][] THEME_SWATCHES = {
            {0xFF0D0F14, 0xFFFF6B3D}, {0xFF0B0D16, 0xFF6C8CFF}, {0xFF0D1411, 0xFF3DDC97}, {0xFF0F0C15, 0xFFB69CFF}, {0xFF000000, 0xFFFF6B3D}
    };

    public static final int[] FONT_NAMES = {
            R.string.TelezhkaFontDefault, R.string.TelezhkaFontSoft, R.string.TelezhkaFontBold, R.string.TelezhkaFontSerif, R.string.TelezhkaFontMono
    };

    private static final String PREF_THEME = "tz_theme";
    private static final String PREF_FONT = "tz_msg_font";
    private static final String PREF_HIDE_STORIES = "tz_hide_stories";
    private static final String PREF_HIDE_CALL = "tz_hide_call";
    // the user's own fonts: files in filesDir/telezhka_fonts, the list (id + name) in PREF_CUSTOM_FONTS
    private static final String PREF_CUSTOM_FONTS = "tz_custom_fonts";
    private static final String PREF_FONT_CUSTOM = "tz_msg_font_custom";  // id of the own font for messages, "" = a built-in one
    private static final String PREF_APP_FONT = "tz_app_font";            // id of the own font for the whole app, "" = off

    public static final int MAX_CUSTOM_FONTS = 12;
    private static final long MAX_FONT_SIZE = 30L * 1024 * 1024;

    public static final int IMPORT_OK = 0;
    public static final int IMPORT_NOT_A_FONT = 1;
    public static final int IMPORT_TOO_BIG = 2;
    public static final int IMPORT_TOO_MANY = 3;
    public static final int IMPORT_FAILED = 4;

    public static class CustomFont {
        public final String id;
        public final String name;

        CustomFont(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final HashMap<String, Typeface> customTypefaces = new HashMap<>();
    private static final HashMap<String, Typeface> customBoldTypefaces = new HashMap<>();
    // The whole-app font is read once per run: views and paints pick their typeface when they are made,
    // so switching it is applied by restarting the app.
    private static volatile boolean appFontRead;
    private static volatile Typeface appRegular, appBold;

    // Theme's static setup runs early, so read the file directly instead of through MessagesController
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    public static int themeIndex() {
        try {
            int index = prefs().getInt(PREF_THEME, 0);
            return index >= 0 && index < THEME_ASSETS.length ? index : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String themeAsset() {
        return THEME_ASSETS[themeIndex()];
    }

    public static void setTheme(int index) {
        prefs().edit().putInt(PREF_THEME, index).apply();
        Theme.ThemeInfo night = Theme.getTheme("Night");
        if (night == null) {
            return;
        }
        night.assetName = THEME_ASSETS[index];
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, night, false, null, -1);
    }

    public static int fontIndex() {
        int index = prefs().getInt(PREF_FONT, 0);
        return index >= 0 && index < FONT_NAMES.length ? index : 0;
    }

    public static Typeface messageTypeface() {
        String custom = selectedCustomFont();
        if (!custom.isEmpty()) {
            Typeface typeface = customTypeface(custom);
            if (typeface != null) {
                return typeface;
            }
        }
        return typeface(fontIndex());
    }

    public static Typeface typeface(int index) {
        switch (index) {
            case 1:
                return AndroidUtilities.getTypeface("fonts/manrope_regular.ttf");
            case 2:
                return AndroidUtilities.getTypeface("fonts/unbounded_semibold.ttf");
            case 3:
                return Typeface.SERIF;
            case 4:
                return AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO);
            default:
                return AndroidUtilities.regular();
        }
    }

    public static void setFont(int index) {
        prefs().edit().putInt(PREF_FONT, index).putString(PREF_FONT_CUSTOM, "").apply();
        applyMessageFont();
    }

    private static void applyMessageFont() {
        if (Theme.chat_msgTextPaint != null) {
            Theme.chat_msgTextPaint.setTypeface(messageTypeface());
        }
    }

    // ---- The user's own fonts ----

    private static File fontsDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "telezhka_fonts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File fontFile(String id) {
        return new File(fontsDir(), id + ".font");
    }

    public static ArrayList<CustomFont> customFonts() {
        ArrayList<CustomFont> result = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs().getString(PREF_CUSTOM_FONTS, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String id = item.optString("id");
                if (!id.isEmpty() && fontFile(id).exists()) {
                    result.add(new CustomFont(id, item.optString("name", id)));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static void saveCustomFonts(ArrayList<CustomFont> fonts) {
        JSONArray list = new JSONArray();
        try {
            for (CustomFont font : fonts) {
                list.put(new JSONObject().put("id", font.id).put("name", font.name));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString(PREF_CUSTOM_FONTS, list.toString()).apply();
    }

    /** The own font chosen for messages, "" when a built-in one is used. */
    public static String selectedCustomFont() {
        try {
            return prefs().getString(PREF_FONT_CUSTOM, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void setCustomFont(String id) {
        prefs().edit().putString(PREF_FONT_CUSTOM, id).apply();
        applyMessageFont();
    }

    public static Typeface customTypeface(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        synchronized (customTypefaces) {
            if (customTypefaces.containsKey(id)) {
                return customTypefaces.get(id);
            }
            Typeface typeface = loadFont(fontFile(id));
            customTypefaces.put(id, typeface);
            return typeface;
        }
    }

    public static Typeface customBoldTypeface(String id) {
        Typeface regular = customTypeface(id);
        if (regular == null) {
            return null;
        }
        synchronized (customTypefaces) {
            Typeface bold = customBoldTypefaces.get(id);
            if (bold == null) {
                // a single file has one weight; Android draws a synthetic bold from it when it is regular
                bold = Build.VERSION.SDK_INT >= 28 ? Typeface.create(regular, 700, false) : Typeface.create(regular, Typeface.BOLD);
                customBoldTypefaces.put(id, bold);
            }
            return bold;
        }
    }

    private static Typeface loadFont(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                return new Typeface.Builder(file).build();  // null when the file is not a usable font
            }
            return Typeface.createFromFile(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // TrueType, OpenType and font collections; WOFF and other web formats are not supported by Android
    private static boolean looksLikeFont(File file) {
        byte[] head = new byte[4];
        try (InputStream in = new java.io.FileInputStream(file)) {
            if (in.read(head) != 4) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        int tag = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return tag == 0x00010000 || tag == 0x74727565 /* true */ || tag == 0x4F54544F /* OTTO */ || tag == 0x74746366 /* ttcf */;
    }

    /** Copies a picked file into the app and adds it to the list. Runs off the UI thread. */
    public static int importFont(Uri uri) {
        if (customFonts().size() >= MAX_CUSTOM_FONTS) {
            return IMPORT_TOO_MANY;
        }
        String name = null;
        try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        if (name != null) {
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            name = name.replace('_', ' ').trim();
        }
        if (TextUtils.isEmpty(name)) {
            name = LocaleController.getString(R.string.TelezhkaFontOwn);
        }

        String id = "f" + System.currentTimeMillis();
        File tmp = new File(fontsDir(), id + ".tmp");
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                return IMPORT_FAILED;
            }
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FONT_SIZE) {
                    out.close();
                    tmp.delete();
                    return IMPORT_TOO_BIG;
                }
                out.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            tmp.delete();
            return IMPORT_FAILED;
        }
        if (!looksLikeFont(tmp) || loadFont(tmp) == null) {
            tmp.delete();
            return IMPORT_NOT_A_FONT;
        }
        File file = fontFile(id);
        if (!tmp.renameTo(file)) {
            tmp.delete();
            return IMPORT_FAILED;
        }
        ArrayList<CustomFont> fonts = customFonts();
        fonts.add(new CustomFont(id, name));
        saveCustomFonts(fonts);
        lastImportedId = id;
        return IMPORT_OK;
    }

    private static volatile String lastImportedId;

    public static String lastImportedFont() {
        return lastImportedId;
    }

    /** Removes an own font; returns true when it was the whole-app font, so the app needs a restart. */
    public static boolean deleteCustomFont(String id) {
        ArrayList<CustomFont> fonts = customFonts();
        for (int i = fonts.size() - 1; i >= 0; i--) {
            if (fonts.get(i).id.equals(id)) {
                fonts.remove(i);
            }
        }
        saveCustomFonts(fonts);
        boolean wasAppFont = id.equals(appFontId());
        SharedPreferences.Editor editor = prefs().edit();
        if (id.equals(selectedCustomFont())) {
            editor.putString(PREF_FONT_CUSTOM, "");
        }
        if (wasAppFont) {
            editor.putString(PREF_APP_FONT, "");
        }
        editor.apply();
        applyMessageFont();
        // the file stays until the next start if this run still draws with it
        if (!wasAppFont || appRegular == null) {
            synchronized (customTypefaces) {
                customTypefaces.remove(id);
                customBoldTypefaces.remove(id);
            }
            fontFile(id).delete();
        }
        return wasAppFont;
    }

    // ---- Own font for the whole app ----

    public static String appFontId() {
        try {
            return prefs().getString(PREF_APP_FONT, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void setAppFont(String id) {
        prefs().edit().putString(PREF_APP_FONT, id == null ? "" : id).apply();
    }

    /** True when the saved choice differs from what this run draws with, i.e. a restart is pending. */
    public static boolean appFontNeedsRestart() {
        readAppFont();
        String id = appFontId();
        Typeface wanted = id.isEmpty() ? null : customTypeface(id);
        return wanted != appRegular;
    }

    private static void readAppFont() {
        if (appFontRead || ApplicationLoader.applicationContext == null) {
            return;
        }
        synchronized (TelezhkaAppearance.class) {
            if (appFontRead) {
                return;
            }
            String id = appFontId();
            if (!id.isEmpty()) {
                appRegular = customTypeface(id);
                appBold = appRegular != null ? customBoldTypeface(id) : null;
            }
            // clean up files of fonts deleted while they were still in use
            try {
                ArrayList<CustomFont> fonts = customFonts();
                File[] files = fontsDir().listFiles();
                if (files != null) {
                    for (File file : files) {
                        boolean known = false;
                        for (CustomFont font : fonts) {
                            if (file.getName().equals(font.id + ".font")) {
                                known = true;
                                break;
                            }
                        }
                        if (!known) {
                            file.delete();
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            appFontRead = true;
        }
    }

    /** The own font set for the whole app in this run, or null for the usual Telezhka fonts. */
    public static Typeface appTypeface() {
        readAppFont();
        return appRegular;
    }

    public static Typeface appBoldTypeface() {
        readAppFont();
        return appBold;
    }

    public static boolean hideStories() {
        return prefs().getBoolean(PREF_HIDE_STORIES, false);
    }

    public static void setHideStories(boolean hide) {
        prefs().edit().putBoolean(PREF_HIDE_STORIES, hide).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    public static boolean hideCallButton() {
        return prefs().getBoolean(PREF_HIDE_CALL, false);
    }

    public static void setHideCallButton(boolean hide) {
        prefs().edit().putBoolean(PREF_HIDE_CALL, hide).apply();
    }
}

/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelezhkaProxyRescue;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProxyDotGridView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Downloads a list of public MTProto proxies, checks them on the device and enables the fastest one.
 * The rest of the working proxies are saved to the proxy list as spares.
 */
public class ProxyPickerActivity extends BaseFragment {

    // Spare list used when PROXY_LIST_URL is unreachable or returns nothing usable.
    // Add entries as {"server", "port", "secret"}, e.g. {"1.2.3.4", "443", "ee0123...abcd"}.
    private static final String[][] FALLBACK_PROXIES = {
    };

    private static final int MAX_PROXIES = 100;
    private static final int PARALLEL_CHECKS = 12;
    private static final long CHECK_TIMEOUT_MS = 7000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int CONNECT_TRIES = 5;

    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^[0-9A-Fa-f:]{2,39}$");
    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9A-Fa-f]+$");

    private static class Candidate {
        final ProxySettings settings;
        final int index;
        boolean done;
        boolean responded;
        long ping;

        Candidate(ProxySettings settings, int index) {
            this.settings = settings;
            this.index = index;
        }
    }

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int OK_COLOR = 0xFF3DDC97;

    private final boolean autoStart;
    private Runnable onContinue;

    private ButtonWithCounterView pickButton;
    private TextView statusTextView;
    private TextView sourceTextView;
    private TextView titleTextView;
    private TextView pillTextView;
    private View pillDot;
    private ProxyDotGridView gridView;
    private TextView checkedValue;
    private TextView workingValue;
    private TextView bestValue;
    private LinearLayout resultView;
    private TextView resultTitle;
    private TextView resultSubtitle;
    private int bestIndex = -1;
    private long bestPing;

    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private int nextIndex;
    private int running;
    private int checkedCount;
    private int workingCount;
    private boolean inProgress;
    private boolean destroyed;
    private int generation;
    private TelezhkaProxyRescue rescue;

    public ProxyPickerActivity() {
        this(false);
    }

    // autoStart: begin picking as soon as the screen opens (used on first launch)
    public ProxyPickerActivity(boolean autoStart) {
        this.autoStart = autoStart;
    }

    // Shown as "Continue" after a successful pick (first-launch flow)
    public void setOnContinue(Runnable onContinue) {
        this.onContinue = onContinue;
    }

    @Override
    public boolean onFragmentCreate() {
        SharedConfig.loadProxyList();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        generation++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("");
        actionBar.setBackgroundColor(INK);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(INK);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 88));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(4), dp(22), dp(16));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setBackground(Theme.createRoundRectDrawable(dp(20), PANEL));
        pillDot = new View(context);
        pillDot.setBackground(Theme.createCircleDrawable(dp(8), MUTE));
        pill.addView(pillDot, LayoutHelper.createLinear(8, 8, 0, 0, 8, 0));
        pillTextView = new TextView(context);
        pillTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        pillTextView.setTypeface(AndroidUtilities.bold());
        pillTextView.setTextColor(TEXT);
        pillTextView.setText(getString(R.string.ProxyPickerPillIdle));
        pill.addView(pillTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        titleTextView = new TextView(context);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleTextView.setTypeface(AndroidUtilities.display());
        titleTextView.setTextColor(TEXT);
        titleTextView.setText(getString(R.string.ProxyPickerHeadline));
        layout.addView(titleTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusTextView.setLineSpacing(dp(2), 1f);
        statusTextView.setTextColor(MUTE);
        statusTextView.setText(getString(R.string.ProxyPickerInfo));
        layout.addView(statusTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        gridView = new ProxyDotGridView(context);
        gridView.setCount(48);
        layout.addView(gridView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 18));

        LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        checkedValue = addStat(context, stats, getString(R.string.ProxyPickerStatChecked), TEXT, 0);
        workingValue = addStat(context, stats, getString(R.string.ProxyPickerStatWorking), OK_COLOR, 8);
        bestValue = addStat(context, stats, getString(R.string.ProxyPickerStatBest), SIGNAL, 8);
        layout.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        resultView = new LinearLayout(context);
        resultView.setOrientation(LinearLayout.HORIZONTAL);
        resultView.setGravity(Gravity.CENTER_VERTICAL);
        resultView.setPadding(dp(14), dp(14), dp(14), dp(14));
        resultView.setBackground(Theme.createRoundRectDrawable(dp(16), PANEL));
        View resultDot = new View(context);
        resultDot.setBackground(Theme.createCircleDrawable(dp(12), SIGNAL));
        resultView.addView(resultDot, LayoutHelper.createLinear(12, 12, 0, 0, 12, 0));
        LinearLayout resultTexts = new LinearLayout(context);
        resultTexts.setOrientation(LinearLayout.VERTICAL);
        resultTitle = new TextView(context);
        resultTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        resultTitle.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        resultTitle.setTextColor(TEXT);
        resultTitle.setSingleLine(true);
        resultTitle.setEllipsize(TextUtils.TruncateAt.END);
        resultTexts.addView(resultTitle);
        resultSubtitle = new TextView(context);
        resultSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        resultSubtitle.setTextColor(MUTE);
        resultTexts.addView(resultSubtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        resultView.addView(resultTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        resultView.setAlpha(0f);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        TextView warningTextView = new TextView(context);
        warningTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        warningTextView.setTextColor(MUTE);
        warningTextView.setText(getString(R.string.ProxyPickerWarning));
        layout.addView(warningTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        sourceTextView = new TextView(context);
        sourceTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sourceTextView.setTypeface(AndroidUtilities.bold());
        sourceTextView.setTextColor(SIGNAL);
        sourceTextView.setPadding(0, dp(10), 0, dp(10));
        sourceTextView.setOnClickListener(v -> showListUrlDialog(this, this::updateSourceText));
        updateSourceText();
        layout.addView(sourceTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        pickButton = new ButtonWithCounterView(context, getResourceProvider());
        pickButton.setText(getString(R.string.ProxyPickerButton), false);
        pickButton.setOnClickListener(v -> {
            if (inProgress) {
                cancel();
            } else if (resultView.getVisibility() == View.VISIBLE && onContinue != null) {
                onContinue.run();
            } else {
                start();
            }
        });
        root.addView(pickButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 54, Gravity.BOTTOM, 22, 0, 22, 22));

        fragmentView = root;
        if (autoStart) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!inProgress && !destroyed) {
                    start();
                }
            }, 350);
        }
        return fragmentView;
    }

    private void updateSourceText() {
        if (sourceTextView != null) {
            sourceTextView.setText(formatString(R.string.TelezhkaListSourceRow, getListSourceLabel()));
        }
    }

    private TextView addStat(Context context, LinearLayout parent, String label, int color, int leftMargin) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));
        tile.setBackground(Theme.createRoundRectDrawable(dp(14), PANEL));
        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        value.setTextColor(color);
        value.setText("0");
        tile.addView(value);
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        name.setTextColor(MUTE);
        name.setText(label);
        tile.addView(name);
        parent.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, leftMargin, 0, 0, 0));
        return value;
    }

    private void setPill(String text, int dotColor, boolean blink) {
        pillTextView.setText(text);
        pillDot.setBackground(Theme.createCircleDrawable(dp(8), dotColor));
        pillDot.animate().cancel();
        pillDot.setAlpha(1f);
        if (blink) {
            pillDot.animate().alpha(0.25f).setDuration(500).withEndAction(() -> {
                if (inProgress && !destroyed) {
                    setPill(pillTextView.getText().toString(), dotColor, true);
                }
            }).start();
        }
    }

    private void showResult(String title, String subtitle) {
        resultTitle.setText(title);
        resultSubtitle.setText(subtitle);
        resultView.setVisibility(View.VISIBLE);
        resultView.setTranslationY(dp(12));
        resultView.animate().alpha(1f).translationY(0).setDuration(450).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private void markBest(int index, long ping) {
        if (bestIndex >= 0 && bestIndex != index) {
            gridView.setState(bestIndex, ProxyDotGridView.OK);
        }
        bestIndex = index;
        bestPing = ping;
        gridView.setState(index, ProxyDotGridView.BEST);
        bestValue.setText(String.valueOf(ping));
    }

    private void start() {
        // The user picks by hand now: stop a background search so the two do not switch proxies at once
        if (TelezhkaProxyRescue.isRunning()) {
            TelezhkaProxyRescue.getActive().cancel();
        }
        rescue = null;
        inProgress = true;
        final int gen = ++generation;
        pickButton.setText(getString(R.string.Cancel), true);
        statusTextView.setText(getString(R.string.ProxyPickerLoading));
        titleTextView.setText(getString(R.string.ProxyPickerHeadlineChecking));
        setPill(getString(R.string.ProxyPickerPillChecking), SIGNAL, true);
        resultView.animate().cancel();
        resultView.setVisibility(View.GONE);
        resultView.setAlpha(0f);
        bestIndex = -1;
        bestPing = 0;
        checkedValue.setText("0");
        workingValue.setText("0");
        bestValue.setText("—");
        gridView.resetAll();
        Utilities.externalNetworkQueue.postRunnable(() -> {
            ArrayList<ProxySettings> list = new ArrayList<>();
            for (String url : getListUrls()) {
                list = parseProxyList(downloadList(url));
                if (!list.isEmpty()) {
                    break;
                }
            }
            if (list.isEmpty()) {
                list = loadFallbackList();
            }
            final ArrayList<ProxySettings> result = list;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == generation) {
                    startChecks(result);
                }
            });
        });
    }

    private void cancel() {
        generation++;
        if (rescue != null) {
            rescue.cancel();
            rescue = null;
        }
        finish(getString(R.string.ProxyPickerCancelled));
    }

    private void finish(CharSequence status) {
        inProgress = false;
        if (destroyed) {
            return;
        }
        pickButton.setText(getString(R.string.ProxyPickerButton), true);
        statusTextView.setText(status);
        titleTextView.setText(getString(R.string.ProxyPickerHeadline));
        setPill(getString(R.string.ProxyPickerPillIdle), MUTE, false);
        if (checkedCount > 0) {
            saveLastPick();
        }
    }

    // Read by ProxyStatusActivity ("last pick" card)
    private void saveLastPick() {
        MessagesController.getGlobalMainSettings().edit()
                .putString("telezhka_last_pick", gridView.snapshot())
                .putInt("telezhka_last_pick_checked", Math.max(checkedCount, candidates.size()))
                .putInt("telezhka_last_pick_working", workingCount)
                .putLong("telezhka_last_pick_time", System.currentTimeMillis())
                .apply();
    }

    private void finishSuccess(ProxySettings settings, long ping, int spares) {
        inProgress = false;
        if (destroyed) {
            return;
        }
        pickButton.setText(getString(onContinue != null ? R.string.ProxyPickerContinue : R.string.ProxyPickerAgain), true);
        titleTextView.setText(getString(R.string.ProxyPickerHeadlineDone));
        statusTextView.setText(getString(R.string.ProxyPickerDoneInfo));
        setPill(getString(R.string.ProxyPickerPillDone), OK_COLOR, false);
        bestValue.setText(String.valueOf(ping));
        saveLastPick();
        showResult(settings.getAddress() + " : " + settings.getPort(), formatString(R.string.ProxyPickerResultSub, ping, spares));
    }

    public static final String PREF_LIST_URL = "telezhka_proxy_list_url";

    // Address typed in the app, or null to use the built-in ones
    public static String getCustomListUrl() {
        String url = MessagesController.getGlobalMainSettings().getString(PREF_LIST_URL, null);
        return TextUtils.isEmpty(url) ? null : url;
    }

    public static ArrayList<String> getListUrls() {
        ArrayList<String> urls = new ArrayList<>();
        String custom = getCustomListUrl();
        if (custom != null) {
            urls.add(custom);
        }
        urls.add(BuildVars.PROXY_LIST_URL);
        for (String mirror : BuildVars.PROXY_LIST_MIRRORS) {
            if (!urls.contains(mirror)) {
                urls.add(mirror);
            }
        }
        return urls;
    }

    // Short label of the list source for the UI: host of the custom address, or "GitHub"
    public static String getListSourceLabel() {
        String custom = getCustomListUrl();
        if (custom == null) {
            return getString(R.string.TelezhkaListSourceDefault);
        }
        try {
            String host = new URL(custom).getHost();
            return TextUtils.isEmpty(host) ? custom : host;
        } catch (Exception e) {
            return custom;
        }
    }

    public static void showListUrlDialog(BaseFragment fragment, Runnable onChanged) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(4), dp(24), 0);
        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(MUTE);
        info.setText(getString(R.string.TelezhkaListSourceInfo));
        container.addView(info);
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTE);
        input.setHint("https://my-server.ru/proxies.json");
        input.setCursorColor(SIGNAL);
        input.setCursorSize(dp(20));
        input.setCursorWidth(1.5f);
        input.setLineColors(0xFF262C37, SIGNAL, 0xFFE5484D);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        String custom = getCustomListUrl();
        if (custom != null) {
            input.setText(custom);
            input.setSelection(custom.length());
        }
        container.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.TelezhkaListSourceTitle));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (d, which) -> {
            // Used only if the button below could not be overridden; keeps the old address on bad input
            String url = input.getText().toString().trim();
            if (url.isEmpty()) {
                MessagesController.getGlobalMainSettings().edit().remove(PREF_LIST_URL).apply();
            } else if (isValidListUrl(url)) {
                MessagesController.getGlobalMainSettings().edit().putString(PREF_LIST_URL, url).apply();
            }
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (custom != null) {
            builder.setNeutralButton(getString(R.string.TelezhkaListSourceReset), (dialog, which) -> {
                MessagesController.getGlobalMainSettings().edit().remove(PREF_LIST_URL).apply();
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (save != null) {
            save.setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) {
                    MessagesController.getGlobalMainSettings().edit().remove(PREF_LIST_URL).apply();
                } else if (!isValidListUrl(url)) {
                    input.setErrorText(getString(R.string.TelezhkaListSourceBad));
                    AndroidUtilities.shakeViewSpring(input, 6);
                    return;
                } else {
                    MessagesController.getGlobalMainSettings().edit().putString(PREF_LIST_URL, url).apply();
                }
                dialog.dismiss();
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private static boolean isValidListUrl(String url) {
        if (url.length() > 2000 || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return false;
        }
        try {
            return !TextUtils.isEmpty(new URL(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public static String downloadList(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE_BYTES) {
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

    public static ArrayList<ProxySettings> parseProxyList(String json) {
        ArrayList<ProxySettings> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        HashSet<ProxySettings> seen = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && result.size() < MAX_PROXIES; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Object port = object.opt("port");
                ProxySettings settings = buildProxy(object.optString("server", null), port instanceof Number ? ((Number) port).intValue() : parsePort(object.optString("port", null)), object.optString("secret", null));
                if (settings != null && seen.add(settings)) {
                    result.add(settings);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static ArrayList<ProxySettings> loadFallbackList() {
        ArrayList<ProxySettings> result = new ArrayList<>();
        HashSet<ProxySettings> seen = new HashSet<>();
        for (String[] entry : FALLBACK_PROXIES) {
            if (entry.length < 3 || result.size() >= MAX_PROXIES) {
                continue;
            }
            ProxySettings settings = buildProxy(entry[0], parsePort(entry[1]), entry[2]);
            if (settings != null && seen.add(settings)) {
                result.add(settings);
            }
        }
        return result;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // Rejects anything that is not a plausible MTProto proxy: host name or IP, port 1-65535,
    // hex secret of 32 (plain), 34 (dd) or 34+ (ee + fake-TLS domain) characters.
    private static ProxySettings buildProxy(String server, int port, String secret) {
        if (server == null || secret == null) {
            return null;
        }
        server = server.trim();
        secret = secret.trim();
        if (server.isEmpty() || server.length() > 253 || !HOST_PATTERN.matcher(server).matches()) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        if (secret.length() < 32 || secret.length() > 600 || secret.length() % 2 != 0 || !HEX_PATTERN.matcher(secret).matches()) {
            return null;
        }
        ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.MTPROTO)
                .setAddress(server)
                .setPort(port)
                .setSecret(secret)
                .build();
        return settings.isValid() ? settings : null;
    }

    private void startChecks(ArrayList<ProxySettings> list) {
        if (destroyed) {
            return;
        }
        if (list.isEmpty()) {
            finish(getString(R.string.ProxyPickerNoList));
            return;
        }
        candidates.clear();
        for (ProxySettings settings : list) {
            candidates.add(new Candidate(settings, candidates.size()));
        }
        gridView.setCount(candidates.size());
        nextIndex = 0;
        running = 0;
        checkedCount = 0;
        workingCount = 0;
        updateProgress();
        scheduleChecks(generation);
    }

    private void updateProgress() {
        statusTextView.setText(formatString(R.string.ProxyPickerProgress, checkedCount, candidates.size(), workingCount));
        checkedValue.setText(String.valueOf(checkedCount));
        workingValue.setText(String.valueOf(workingCount));
    }

    private void scheduleChecks(int gen) {
        while (gen == generation && running < PARALLEL_CHECKS && nextIndex < candidates.size()) {
            final Candidate candidate = candidates.get(nextIndex++);
            running++;
            if (!destroyed) {
                gridView.setState(candidate.index, ProxyDotGridView.SCANNING);
            }
            final Runnable timeout = () -> onCheckResult(gen, candidate, -1, false);
            AndroidUtilities.runOnUIThread(timeout, CHECK_TIMEOUT_MS);
            ConnectionsManager.getInstance(currentAccount).checkProxy(candidate.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                AndroidUtilities.cancelRunOnUIThread(timeout);
                onCheckResult(gen, candidate, time, true);
            }));
        }
        if (gen == generation && running == 0 && nextIndex >= candidates.size()) {
            onChecksDone(gen);
        }
    }

    private void onCheckResult(int gen, Candidate candidate, long time, boolean responded) {
        if (gen != generation || candidate.done) {
            return;
        }
        candidate.done = true;
        candidate.responded = responded;
        candidate.ping = time;
        running--;
        checkedCount++;
        if (time > 0) {
            workingCount++;
        }
        if (!destroyed) {
            gridView.setState(candidate.index, time > 0 ? ProxyDotGridView.OK : ProxyDotGridView.BAD);
            if (time > 0 && (bestPing == 0 || time < bestPing)) {
                markBest(candidate.index, time);
            }
            updateProgress();
        }
        scheduleChecks(gen);
    }

    private void onChecksDone(int gen) {
        ArrayList<Candidate> working = new ArrayList<>();
        boolean anyResponse = false;
        for (Candidate candidate : candidates) {
            anyResponse |= candidate.responded;
            if (candidate.ping > 0) {
                working.add(candidate);
            }
        }
        if (!working.isEmpty()) {
            Collections.sort(working, (a, b) -> Long.compare(a.ping, b.ping));
            applyWorking(working);
            Candidate best = working.get(0);
            finishSuccess(best.settings, best.ping, working.size() - 1);
            return;
        }
        if (!anyResponse && !isConnected()) {
            // checkProxy needs an auth key, which a fresh install cannot get while Telegram is blocked:
            // the checks never answer. Fall back to TCP reachability and try proxies for real.
            startTcpChecks(gen);
            return;
        }
        finish(getString(R.string.ProxyPickerNothingFound));
    }

    private boolean isConnected() {
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    // Telegram is unreachable and the checks got no answer: emergency mode (TCP checks, then real connection tries)
    private void startTcpChecks(int gen) {
        statusTextView.setText(getString(R.string.ProxyPickerTcpCheck));
        gridView.resetAll();
        ArrayList<ProxySettings> list = new ArrayList<>();
        for (Candidate candidate : candidates) {
            gridView.setState(candidate.index, ProxyDotGridView.SCANNING);
            list.add(candidate.settings);
        }
        rescue = TelezhkaProxyRescue.start(list, list.size(), CONNECT_TRIES, new TelezhkaProxyRescue.Callback() {
            @Override
            public void onTcpChecked(int index, boolean reachable) {
                if (gen == generation && !destroyed) {
                    gridView.setState(index, reachable ? ProxyDotGridView.OK : ProxyDotGridView.BAD);
                }
            }

            @Override
            public void onTcpDone(int reachable, int total) {
                if (gen == generation && !destroyed) {
                    workingValue.setText(String.valueOf(reachable));
                    checkedValue.setText(String.valueOf(total));
                }
            }

            @Override
            public void onTrying(int index, int attempt, int attempts) {
                if (gen == generation && !destroyed) {
                    statusTextView.setText(formatString(R.string.ProxyPickerTrying, attempt, attempts));
                }
            }

            @Override
            public void onTryFailed(int index) {
                if (gen == generation && !destroyed) {
                    gridView.setState(index, ProxyDotGridView.BAD);
                }
            }

            @Override
            public void onFinished(TelezhkaProxyRescue.Result result) {
                rescue = null;
                if (gen != generation || result.cancelled) {
                    return;
                }
                if (result.settings == null) {
                    finish(getString(R.string.ProxyPickerNothingFound));
                    return;
                }
                if (!destroyed) {
                    markBest(result.index, result.ping);
                }
                finishSuccess(result.settings, result.ping, result.spares);
            }
        });
    }

    private void applyWorking(ArrayList<Candidate> working) {
        long now = SystemClock.elapsedRealtime();
        SharedConfig.ProxyInfo best = null;
        // addProxy puts new entries on top, so add the slowest first.
        for (int i = working.size() - 1; i >= 0; i--) {
            Candidate candidate = working.get(i);
            SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(candidate.settings));
            info.ping = candidate.ping;
            info.available = true;
            info.availableCheckTime = now;
            best = info;
        }
        TelezhkaProxyRescue.enable(best);
        SharedConfig.saveProxyList();
    }
}

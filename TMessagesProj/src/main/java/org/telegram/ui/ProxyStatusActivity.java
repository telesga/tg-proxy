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
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelezhkaKeepAliveService;
import org.telegram.messenger.TelezhkaPingMonitor;
import org.telegram.messenger.TelezhkaProxyAutopilot;
import org.telegram.messenger.TelezhkaProxyRescue;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProxyDotGridView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Date;

/**
 * "Connection" screen: live ping with a sparkline, the last pick as a dot grid,
 * spare proxies (tap to switch) and a button to pick again.
 */
public class ProxyStatusActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int LINE = 0xFF262C37;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int OK = 0xFF3DDC97;
    private static final int OK_DIM = 0xFF123A2A;

    private TextView pillText;
    private View pillDot;
    private TextView pingText;
    private TextView addressText;
    private SparklineView sparkline;
    private LinearLayout sparesList;
    private ProxyDotGridView lastGrid;
    private TextView lastInfo;
    private LinearLayout lastCard;
    private TextView sourceValue;
    private TextView autopilotStatus;
    private TextView aliveText;
    private TextView minAliveValue;
    private TextView batteryLabel;
    private TextView batteryInfo;
    private LinearLayout batteryRow;
    private TextView searchText;
    // Status of the autopilot's own search: refreshed on changes and once a second for the countdown
    private final Runnable searchListener = this::updateSearch;
    private final Runnable searchTicker = new Runnable() {
        @Override
        public void run() {
            updateSearch();
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };
    private final TelezhkaPingMonitor.Listener pingListener = ping -> updateHero();

    @Override
    public boolean onFragmentCreate() {
        SharedConfig.loadProxyList();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        TelezhkaPingMonitor.stop(pingListener);
        TelezhkaProxyRescue.removeStatusListener(searchListener);
        AndroidUtilities.cancelRunOnUIThread(searchTicker);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAll();
        TelezhkaPingMonitor.start(pingListener, 2000);
        TelezhkaProxyRescue.addStatusListener(searchListener);
        AndroidUtilities.cancelRunOnUIThread(searchTicker);
        searchTicker.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        TelezhkaPingMonitor.stop(pingListener);
        TelezhkaProxyRescue.removeStatusListener(searchListener);
        AndroidUtilities.cancelRunOnUIThread(searchTicker);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        updateAll();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaConnectionTitle));
        actionBar.setBackgroundColor(INK);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setTitleColor(TEXT);
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
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(8), dp(18), dp(28));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Hero card: status, big ping, sparkline, address
        LinearLayout hero = card(context);
        hero.setPadding(dp(18), dp(18), dp(18), dp(16));
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setBackground(Theme.createRoundRectDrawable(dp(20), INK));
        pillDot = new View(context);
        pill.addView(pillDot, LayoutHelper.createLinear(8, 8, 0, 0, 8, 0));
        pillText = new TextView(context);
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        pillText.setTypeface(AndroidUtilities.bold());
        pillText.setTextColor(TEXT);
        pill.addView(pillText);
        hero.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        // "Ищу рабочий прокси… (3 из 8)": shown while the autopilot searches by itself
        searchText = new TextView(context);
        searchText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        searchText.setTextColor(SIGNAL);
        searchText.setVisibility(View.GONE);
        hero.addView(searchText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        pingText = new TextView(context);
        pingText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
        pingText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        pingText.setTextColor(TEXT);
        hero.addView(pingText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        sparkline = new SparklineView(context);
        hero.addView(sparkline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 8, 0, 8));

        LinearLayout addressRow = new LinearLayout(context);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressText = new TextView(context);
        addressText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        addressText.setTypeface(Typeface.MONOSPACE);
        addressText.setTextColor(MUTE);
        addressText.setSingleLine(true);
        addressText.setEllipsize(TextUtils.TruncateAt.END);
        addressRow.addView(addressText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        TextView live = new TextView(context);
        live.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        live.setTextColor(MUTE);
        live.setText(getString(R.string.TelezhkaConnectionLive));
        addressRow.addView(live);
        hero.addView(addressRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Last pick
        lastCard = card(context);
        lastCard.setPadding(dp(14), dp(14), dp(14), dp(12));
        lastGrid = new ProxyDotGridView(context);
        lastGrid.setColumns(12);
        lastCard.addView(lastGrid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        lastInfo = new TextView(context);
        lastInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        lastInfo.setTextColor(MUTE);
        lastCard.addView(lastInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        layout.addView(header(context, getString(R.string.TelezhkaConnectionLastPick)));
        layout.addView(lastCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Spares
        layout.addView(header(context, getString(R.string.TelezhkaConnectionSpares)));
        sparesList = card(context);
        layout.addView(sparesList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ButtonWithCounterView again = new ButtonWithCounterView(context, getResourceProvider());
        again.setText(getString(R.string.ProxyPickerAgain), false);
        again.setOnClickListener(v -> presentFragment(new ProxyPickerActivity(true)));
        layout.addView(again, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 18, 0, 0));

        // Autopilot: keeps the list fresh, removes dead proxies and switches by itself
        layout.addView(header(context, getString(R.string.TelezhkaAutopilotTitle)));
        LinearLayout autoCard = card(context);
        LinearLayout autoRow = new LinearLayout(context);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(dp(16), dp(14), dp(16), dp(6));
        LinearLayout autoTexts = new LinearLayout(context);
        autoTexts.setOrientation(LinearLayout.VERTICAL);
        TextView autoLabel = new TextView(context);
        autoLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        autoLabel.setTypeface(AndroidUtilities.bold());
        autoLabel.setTextColor(TEXT);
        autoLabel.setText(getString(R.string.TelezhkaAutopilotLabel));
        autoTexts.addView(autoLabel);
        TextView autoInfo = new TextView(context);
        autoInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        autoInfo.setTextColor(MUTE);
        autoInfo.setText(getString(R.string.TelezhkaAutopilotInfo));
        autoTexts.addView(autoInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        autoRow.addView(autoTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        org.telegram.ui.Components.Switch autoSwitch = new org.telegram.ui.Components.Switch(context);
        autoSwitch.setChecked(TelezhkaProxyAutopilot.isEnabled(), false);
        autoRow.addView(autoSwitch, LayoutHelper.createLinear(37, 40));
        autoRow.setOnClickListener(v -> {
            boolean now = !TelezhkaProxyAutopilot.isEnabled();
            TelezhkaProxyAutopilot.setEnabled(now);
            autoSwitch.setChecked(now, true);
            updateAutopilot();
        });
        autoCard.addView(autoRow);
        autopilotStatus = new TextView(context);
        autopilotStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        autopilotStatus.setTextColor(MUTE);
        autopilotStatus.setPadding(dp(16), dp(4), dp(16), dp(2));
        autoCard.addView(autopilotStatus);
        aliveText = new TextView(context);
        aliveText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        aliveText.setTextColor(MUTE);
        aliveText.setPadding(dp(16), dp(2), dp(16), dp(14));
        autoCard.addView(aliveText);
        autoCard.addView(divider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 14, 0, 14, 0));
        // Threshold: look for new proxies while this many still answer
        LinearLayout minAliveRow = new LinearLayout(context);
        minAliveRow.setOrientation(LinearLayout.HORIZONTAL);
        minAliveRow.setGravity(Gravity.CENTER_VERTICAL);
        minAliveRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        minAliveRow.setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));
        TextView minAliveLabel = new TextView(context);
        minAliveLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        minAliveLabel.setTextColor(TEXT);
        minAliveLabel.setText(getString(R.string.TelezhkaMinAliveLabel));
        minAliveRow.addView(minAliveLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        minAliveValue = new TextView(context);
        minAliveValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        minAliveValue.setTextColor(SIGNAL);
        minAliveRow.addView(minAliveValue);
        minAliveRow.setOnClickListener(v -> showMinAliveDialog());
        autoCard.addView(minAliveRow);
        layout.addView(autoCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateAutopilot();

        // Background: battery exemption and "always connected"
        layout.addView(header(context, getString(R.string.TelezhkaBackgroundTitle)));
        LinearLayout backgroundCard = card(context);
        batteryRow = new LinearLayout(context);
        batteryRow.setOrientation(LinearLayout.VERTICAL);
        batteryRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        batteryRow.setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));
        batteryLabel = new TextView(context);
        batteryLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        batteryLabel.setTypeface(AndroidUtilities.bold());
        batteryLabel.setTextColor(SIGNAL);
        batteryLabel.setText(getString(R.string.TelezhkaBatteryLabel));
        batteryRow.addView(batteryLabel);
        batteryInfo = new TextView(context);
        batteryInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        batteryInfo.setTextColor(MUTE);
        batteryRow.addView(batteryInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        batteryRow.setOnClickListener(v -> askBatteryExemption());
        backgroundCard.addView(batteryRow);
        backgroundCard.addView(divider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 14, 0, 14, 0));
        LinearLayout keepRow = new LinearLayout(context);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        keepRow.setGravity(Gravity.CENTER_VERTICAL);
        keepRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout keepTexts = new LinearLayout(context);
        keepTexts.setOrientation(LinearLayout.VERTICAL);
        TextView keepLabel = new TextView(context);
        keepLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        keepLabel.setTypeface(AndroidUtilities.bold());
        keepLabel.setTextColor(TEXT);
        keepLabel.setText(getString(R.string.TelezhkaKeepAliveLabel));
        keepTexts.addView(keepLabel);
        TextView keepInfo = new TextView(context);
        keepInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        keepInfo.setTextColor(MUTE);
        keepInfo.setText(getString(R.string.TelezhkaKeepAliveInfo));
        keepTexts.addView(keepInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        keepRow.addView(keepTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        org.telegram.ui.Components.Switch keepSwitch = new org.telegram.ui.Components.Switch(context);
        keepSwitch.setChecked(TelezhkaKeepAliveService.isEnabled(), false);
        keepRow.addView(keepSwitch, LayoutHelper.createLinear(37, 40));
        keepRow.setOnClickListener(v -> {
            boolean now = !TelezhkaKeepAliveService.isEnabled();
            TelezhkaKeepAliveService.setEnabled(now);
            keepSwitch.setChecked(now, true);
        });
        backgroundCard.addView(keepRow);
        layout.addView(backgroundCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateBackground();

        layout.addView(header(context, getString(R.string.TelezhkaListSourceTitle)));
        LinearLayout sourceCard = card(context);
        LinearLayout sourceRow = new LinearLayout(context);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        sourceRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        sourceRow.setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));
        TextView sourceLabel = new TextView(context);
        sourceLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sourceLabel.setTextColor(TEXT);
        sourceLabel.setText(getString(R.string.TelezhkaListSourceLabel));
        sourceRow.addView(sourceLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        sourceValue = new TextView(context);
        sourceValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sourceValue.setTypeface(Typeface.MONOSPACE);
        sourceValue.setTextColor(MUTE);
        sourceValue.setSingleLine(true);
        sourceValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sourceValue.setMaxWidth(dp(170));
        sourceRow.addView(sourceValue);
        sourceRow.setOnClickListener(v -> ProxyPickerActivity.showListUrlDialog(this, this::updateSource));
        sourceCard.addView(sourceRow);
        layout.addView(sourceCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateSource();

        TextView all = new TextView(context);
        all.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        all.setTypeface(AndroidUtilities.bold());
        all.setTextColor(SIGNAL);
        all.setGravity(Gravity.CENTER);
        all.setText(getString(R.string.TelezhkaConnectionAllSettings));
        all.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        all.setOnClickListener(v -> presentFragment(new ProxyListActivity()));
        layout.addView(all, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 0));

        TextView warning = new TextView(context);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        warning.setTextColor(MUTE);
        warning.setGravity(Gravity.CENTER);
        warning.setText(getString(R.string.ProxyPickerWarning));
        layout.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        fragmentView = root;
        updateAll();
        return fragmentView;
    }

    private void updateAutopilot() {
        if (autopilotStatus == null) {
            return;
        }
        updateReserve();
        if (!TelezhkaProxyAutopilot.isEnabled()) {
            autopilotStatus.setText(getString(R.string.TelezhkaAutopilotOff));
            return;
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        long last = preferences.getLong(TelezhkaProxyAutopilot.PREF_LAST_RUN, 0);
        if (last == 0) {
            autopilotStatus.setText(getString(R.string.TelezhkaAutopilotNever));
            return;
        }
        int minutes = (int) Math.max(0, (System.currentTimeMillis() - last) / 60000);
        autopilotStatus.setText(formatString(R.string.TelezhkaAutopilotStatus, minutes,
                preferences.getInt(TelezhkaProxyAutopilot.PREF_ADDED, 0), preferences.getInt(TelezhkaProxyAutopilot.PREF_REMOVED, 0)));
    }

    // "Живых прокси: 7 из 12" and the threshold value
    private void updateReserve() {
        if (aliveText == null) {
            return;
        }
        minAliveValue.setText(formatString(R.string.TelezhkaMinAliveValue, TelezhkaProxyAutopilot.getMinAlive()));
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        long time = preferences.getLong(TelezhkaProxyAutopilot.PREF_ALIVE_TIME, 0);
        if (time == 0 || !TelezhkaProxyAutopilot.isEnabled()) {
            aliveText.setText(getString(R.string.TelezhkaAliveNever));
            aliveText.setTextColor(MUTE);
            return;
        }
        int alive = preferences.getInt(TelezhkaProxyAutopilot.PREF_ALIVE, 0);
        int total = Math.max(alive, preferences.getInt(TelezhkaProxyAutopilot.PREF_ALIVE_TOTAL, 0));
        int minutes = (int) Math.max(0, (System.currentTimeMillis() - time) / 60000);
        aliveText.setText(formatString(R.string.TelezhkaAliveStatus, alive, total, minutes));
        aliveText.setTextColor(alive < TelezhkaProxyAutopilot.getMinAlive() ? SIGNAL : OK);
    }

    private void showMinAliveDialog() {
        if (getParentActivity() == null) {
            return;
        }
        int[] choices = TelezhkaProxyAutopilot.MIN_ALIVE_CHOICES;
        CharSequence[] items = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            items[i] = formatString(R.string.TelezhkaMinAliveItem, choices[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.TelezhkaMinAliveTitle));
        builder.setItems(items, (dialog, which) -> {
            TelezhkaProxyAutopilot.setMinAlive(choices[which]);
            updateReserve();
        });
        showDialog(builder.create());
    }

    private boolean isBatteryExempt() {
        try {
            PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return powerManager == null || powerManager.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    // Shown as an action until Android lets the app work in the background, then as a plain note
    private void updateBackground() {
        if (batteryRow == null) {
            return;
        }
        boolean exempt = isBatteryExempt();
        batteryLabel.setVisibility(exempt ? View.GONE : View.VISIBLE);
        batteryInfo.setText(getString(exempt ? R.string.TelezhkaBatteryAllowed : R.string.TelezhkaBatteryInfo));
        batteryInfo.setTextColor(exempt ? OK : MUTE);
        batteryRow.setClickable(!exempt);
    }

    private void askBatteryExemption() {
        if (getParentActivity() == null || isBatteryExempt()) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
            getParentActivity().startActivity(intent);
        } catch (Throwable e) {
            try {
                getParentActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignore) {
            }
        }
    }

    private View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        return divider;
    }

    private void updateSearch() {
        if (searchText == null) {
            return;
        }
        String status = TelezhkaProxyAutopilot.getSearchStatus();
        if (status == null) {
            searchText.setVisibility(View.GONE);
        } else {
            if (!status.contentEquals(searchText.getText())) {
                searchText.setText(status);
            }
            searchText.setVisibility(View.VISIBLE);
        }
    }

    private void updateSource() {
        if (sourceValue != null) {
            sourceValue.setText(ProxyPickerActivity.getListSourceLabel());
        }
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(20), PANEL));
        return card;
    }

    private TextView header(Context context, String text) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.12f);
        header.setTextColor(MUTE);
        header.setText(text.toUpperCase());
        header.setPadding(dp(4), dp(22), 0, dp(8));
        return header;
    }

    private boolean isConnected() {
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private void updateAll() {
        if (fragmentView == null) {
            return;
        }
        updateAutopilot();
        updateBackground();
        updateHero();
        updateSearch();
        updateLastPick();
        updateSpares();
    }

    private void updateHero() {
        if (pingText == null) {
            return;
        }
        boolean proxy = SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null;
        boolean connected = isConnected();
        int dotColor;
        if (!connected) {
            pillText.setText(getString(R.string.TelezhkaConnectionConnecting));
            dotColor = SIGNAL;
        } else if (proxy) {
            pillText.setText(getString(R.string.TelezhkaConnectionViaProxy));
            dotColor = OK;
        } else {
            pillText.setText(getString(R.string.TelezhkaConnectionDirect));
            dotColor = OK;
        }
        pillDot.setBackground(Theme.createCircleDrawable(dp(8), dotColor));
        long ping = TelezhkaPingMonitor.getLastPing();
        String value = proxy && ping > 0 ? String.valueOf(ping) : "—";
        if (!value.contentEquals(pingText.getText().toString().split(" ")[0])) {
            pingText.setScaleX(0.9f);
            pingText.setScaleY(0.9f);
            pingText.setPivotX(0);
            pingText.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
        pingText.setText(value + " " + getString(R.string.TelezhkaMs));
        addressText.setText(proxy ? SharedConfig.currentProxy.settings.getAddress() + " : " + SharedConfig.currentProxy.settings.getPort() : getString(R.string.TelezhkaConnectionNoProxy));
        sparkline.setValues(TelezhkaPingMonitor.getHistory());
    }

    private void updateLastPick() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        String snapshot = preferences.getString("telezhka_last_pick", null);
        if (TextUtils.isEmpty(snapshot)) {
            lastGrid.setVisibility(View.GONE);
            lastInfo.setText(getString(R.string.TelezhkaConnectionNoPick));
            return;
        }
        lastGrid.setVisibility(View.VISIBLE);
        lastGrid.restore(snapshot);
        long time = preferences.getLong("telezhka_last_pick_time", 0);
        lastInfo.setText(formatString(R.string.TelezhkaConnectionPickInfo,
                preferences.getInt("telezhka_last_pick_checked", snapshot.length()),
                preferences.getInt("telezhka_last_pick_working", 0),
                DateFormat.getTimeFormat(getParentActivity() != null ? getParentActivity() : lastInfo.getContext()).format(new Date(time))));
    }

    private void updateSpares() {
        sparesList.removeAllViews();
        Context context = sparesList.getContext();
        ArrayList<SharedConfig.ProxyInfo> spares = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info != SharedConfig.currentProxy || !SharedConfig.isProxyEnabled()) {
                spares.add(info);
            }
        }
        if (spares.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(MUTE);
            empty.setPadding(dp(16), dp(14), dp(16), dp(14));
            empty.setText(getString(R.string.TelezhkaConnectionNoSpares));
            sparesList.addView(empty);
            return;
        }
        for (int i = 0; i < spares.size(); i++) {
            SharedConfig.ProxyInfo info = spares.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));
            row.setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));
            PingBarsView bars = new PingBarsView(context, info.available ? info.ping : -1);
            row.addView(bars, LayoutHelper.createLinear(16, 14, 0, 0, 12, 0));
            TextView address = new TextView(context);
            address.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            address.setTypeface(Typeface.MONOSPACE);
            address.setTextColor(TEXT);
            address.setSingleLine(true);
            address.setEllipsize(TextUtils.TruncateAt.END);
            address.setText(info.settings.getAddress() + " : " + info.settings.getPort());
            row.addView(address, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView ping = new TextView(context);
            ping.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            ping.setTypeface(Typeface.MONOSPACE);
            ping.setTextColor(MUTE);
            ping.setText(info.available && info.ping > 0 ? info.ping + " " + getString(R.string.TelezhkaMs) : "—");
            row.addView(ping, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
            row.setOnClickListener(v -> switchTo(info));
            row.setAlpha(0f);
            row.setTranslationY(dp(8));
            row.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            sparesList.addView(row);
            if (i < spares.size() - 1) {
                View divider = new View(context);
                divider.setBackgroundColor(LINE);
                sparesList.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 14, 0, 14, 0));
            }
        }
    }

    private void switchTo(SharedConfig.ProxyInfo info) {
        // A hand-picked proxy wins over a background search
        if (TelezhkaProxyRescue.isRunning()) {
            TelezhkaProxyRescue.getActive().cancel();
        }
        TelezhkaProxyRescue.enable(info);
        updateAll();
    }

    private static class PingBarsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int level;

        PingBarsView(Context context, long ping) {
            super(context);
            level = ping <= 0 ? 0 : ping < 150 ? 3 : ping < 280 ? 2 : 1;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = dp(3), gap = dp(2);
            for (int i = 0; i < 3; i++) {
                float h = getHeight() * (i + 1) / 3f;
                paint.setColor(i < level ? (level == 1 ? SIGNAL : OK) : LINE);
                float x = i * (w + gap);
                canvas.drawRoundRect(x, getHeight() - h, x + w, getHeight(), dp(1), dp(1), paint);
            }
        }
    }

    // Ping history: area fill, line, highlighted last point
    private static class SparklineView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path area = new Path();
        private final ArrayList<Long> values = new ArrayList<>();

        SparklineView(Context context) {
            super(context);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(OK);
            fill.setColor(OK);
            fill.setAlpha(30);
            dot.setColor(OK);
        }

        void setValues(ArrayList<Long> history) {
            values.clear();
            values.addAll(history);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int n = values.size();
            float w = getWidth(), h = getHeight(), pad = dp(5);
            if (n < 2) {
                line.setAlpha(80);
                canvas.drawLine(0, h / 2, w, h / 2, line);
                line.setAlpha(255);
                return;
            }
            long min = Long.MAX_VALUE, max = 0;
            for (long v : values) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            float range = Math.max(20, max - min);
            path.reset();
            area.reset();
            float lastX = 0, lastY = 0;
            for (int i = 0; i < n; i++) {
                float x = w * i / (TelezhkaPingMonitor.MAX_HISTORY - 1f) + (w - w * (n - 1) / (TelezhkaPingMonitor.MAX_HISTORY - 1f));
                float y = pad + (h - pad * 2) * (1 - (values.get(i) - min) / range);
                if (i == 0) {
                    path.moveTo(x, y);
                    area.moveTo(x, h);
                    area.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    area.lineTo(x, y);
                }
                lastX = x;
                lastY = y;
            }
            area.lineTo(lastX, h);
            area.close();
            canvas.drawPath(area, fill);
            canvas.drawPath(path, line);
            dot.setColor(OK_DIM);
            canvas.drawCircle(lastX - dp(1), lastY, dp(7), dot);
            dot.setColor(OK);
            canvas.drawCircle(lastX - dp(1), lastY, dp(4), dot);
        }
    }
}

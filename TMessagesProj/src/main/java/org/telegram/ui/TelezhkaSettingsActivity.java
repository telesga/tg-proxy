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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaHiddenChats;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.TelezhkaPingMonitor;
import org.telegram.messenger.TelezhkaAppearance;
import org.telegram.messenger.TelezhkaAutoReply;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelezhkaPrivacy;
import org.telegram.messenger.TelezhkaProxyAutopilot;
import org.telegram.messenger.TelezhkaStats;
import org.telegram.messenger.TelezhkaUpdater;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.TelezhkaPopup;

/**
 * "Настройки Тележки": everything Telezhka adds in one place —
 * kept deleted messages, hidden chats, connection, updates and, last, saving where it is forbidden.
 */
public class TelezhkaSettingsActivity extends BaseFragment {

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int BAD = 0xFFFF5A5F;
    private static final int OK = 0xFF3DDC97;
    private static final String RISK_ACK = "telezhka_risk_ack";

    private LinearLayout statusCard;
    private View statusDot;
    private TextView statusTitle, statusInfo;
    private LinearLayout appearanceTile, hiddenTile, connectionTile, updatesTile, autoReplyRow;
    private final TelezhkaPingMonitor.Listener pingListener = ping -> updateStatus();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaSettingsTitle));
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
        layout.setPadding(dp(16), 0, dp(16), dp(28));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Status: is Telegram connected, and how
        statusCard = new LinearLayout(context);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(14), dp(14));
        statusCard.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(20), PANEL, 0xFF1D222C));
        statusDot = new View(context);
        statusCard.addView(statusDot, LayoutHelper.createLinear(10, 10, 0, 0, 14, 0));
        LinearLayout statusTexts = new LinearLayout(context);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        statusTitle = new TextView(context);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusTitle.setTypeface(AndroidUtilities.bold());
        statusTitle.setTextColor(TEXT);
        statusTexts.addView(statusTitle);
        statusInfo = new TextView(context);
        statusInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        statusInfo.setTextColor(MUTE);
        statusTexts.addView(statusInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        statusCard.addView(statusTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        statusCard.addView(chevron(context), LayoutHelper.createLinear(20, 20));
        statusCard.setOnClickListener(v -> presentFragment(new ProxyStatusActivity()));
        layout.addView(statusCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // Four sections as tiles, each with its current state
        LinearLayout tiles1 = new LinearLayout(context);
        LinearLayout tiles2 = new LinearLayout(context);
        appearanceTile = tile(context, R.drawable.msg_theme, SIGNAL, getString(R.string.TelezhkaAppearanceTitle), () -> presentFragment(new TelezhkaAppearanceActivity()));
        hiddenTile = tile(context, R.drawable.msg_secret, 0xFFB69CFF, getString(R.string.TelezhkaHiddenTitle), this::openHidden);
        connectionTile = tile(context, R.drawable.msg_language, OK, getString(R.string.TelezhkaConnectionTitle), () -> presentFragment(new ProxyStatusActivity()));
        updatesTile = tile(context, R.drawable.msg_retry, 0xFF6C8CFF, getString(R.string.TelezhkaSectionUpdates), () -> TelezhkaUpdater.checkNow(this, newer -> {
            if (newer == null) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TelezhkaCheckFailed)).show();
            } else if (!newer) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaUpToDate)).show();
            }
        }));
        tiles1.addView(appearanceTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 5, 0));
        tiles1.addView(hiddenTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 5, 0, 0, 0));
        tiles2.addView(connectionTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 5, 0));
        tiles2.addView(updatesTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 5, 0, 0, 0));
        layout.addView(tiles1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        layout.addView(tiles2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        // All switches in one card, short captions
        layout.addView(header(context, getString(R.string.TelezhkaSectionFeatures)));
        LinearLayout features = card(context);
        features.addView(switchRow(context, R.drawable.telezhka_ic_ghost, 0xFFB69CFF, getString(R.string.TelezhkaGhostTitle), getString(R.string.TelezhkaGhostShortInfo), TelezhkaPrivacy.GHOST, true));
        features.addView(switchRow(context, R.drawable.msg_delete, BAD, getString(R.string.TelezhkaKeepDeletedShort), getString(R.string.TelezhkaKeepDeletedShortInfo), TelezhkaPrivacy.KEEP_DELETED, false));
        features.addView(switchRow(context, R.drawable.msg_download, OK, getString(R.string.TelezhkaSaveAllTitle), getString(R.string.TelezhkaSaveAllShortInfo), TelezhkaPrivacy.SAVE_ALL, true));
        Switch autoSwitch = new Switch(context);
        autoSwitch.setChecked(TelezhkaProxyAutopilot.isEnabled(), false);
        LinearLayout autoRow = row(context, R.drawable.msg_speed, SIGNAL, getString(R.string.TelezhkaAutopilotLabel), getString(R.string.TelezhkaAutopilotShortInfo), autoSwitch);
        autoRow.setOnClickListener(v -> {
            boolean now = !TelezhkaProxyAutopilot.isEnabled();
            TelezhkaProxyAutopilot.setEnabled(now);
            autoSwitch.setChecked(now, true);
            updateTiles();
        });
        features.addView(autoRow);
        ImageView replyChevron = chevron(context);
        autoReplyRow = row(context, R.drawable.msg_bot, 0xFF6C8CFF, getString(R.string.TelezhkaAutoReplyTitle), getString(R.string.TelezhkaAutoReplyRowOff), replyChevron);
        autoReplyRow.setOnClickListener(v -> presentFragment(new TelezhkaAutoReplyActivity()));
        features.addView(autoReplyRow);
        layout.addView(features);

        // News channel
        LinearLayout news = card(context);
        news.addView(linkRow(context, getString(R.string.TelezhkaNewsRow), "@" + BuildVars.NEWS_CHANNEL, () -> org.telegram.messenger.TelezhkaChannelPromo.showNow(this)));
        layout.addView(news, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // Once a day the app tells the bot it is in use (TelezhkaStats); can be turned off
        LinearLayout stats = card(context);
        Switch statsSwitch = new Switch(context);
        statsSwitch.setChecked(TelezhkaStats.isEnabled(), false);
        LinearLayout statsRow = row(context, R.drawable.msg_stats, 0xFF6C8CFF, getString(R.string.TelezhkaStatsTitle), getString(R.string.TelezhkaStatsInfo), statsSwitch);
        statsRow.setOnClickListener(v -> {
            boolean now = !TelezhkaStats.isEnabled();
            TelezhkaStats.setEnabled(now);
            statsSwitch.setChecked(now, true);
        });
        stats.addView(statsRow);
        layout.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        updateStatus();
        updateTiles();
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        TelezhkaPingMonitor.start(pingListener, 3000);
        updateStatus();
        updateTiles();
    }

    @Override
    public void onPause() {
        super.onPause();
        TelezhkaPingMonitor.stop(pingListener);
    }

    private void updateStatus() {
        if (statusTitle == null) {
            return;
        }
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        boolean connected = state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
        boolean proxy = SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null;
        int spares = Math.max(0, SharedConfig.proxyList.size() - (proxy ? 1 : 0));
        statusDot.setBackground(Theme.createCircleDrawable(dp(10), connected ? OK : SIGNAL));
        if (!connected) {
            statusTitle.setText(getString(R.string.TelezhkaConnectionConnecting));
            statusInfo.setText(formatString(R.string.TelezhkaStatusSpares, spares));
        } else if (proxy) {
            statusTitle.setText(getString(R.string.TelezhkaStatusConnected));
            long ping = TelezhkaPingMonitor.getLastPing();
            statusInfo.setText(ping > 0 ? formatString(R.string.TelezhkaStatusViaProxyPing, ping, spares) : formatString(R.string.TelezhkaStatusViaProxy, spares));
        } else {
            statusTitle.setText(getString(R.string.TelezhkaStatusConnected));
            statusInfo.setText(getString(R.string.TelezhkaStatusDirect));
        }
    }

    private void updateTiles() {
        if (appearanceTile == null) {
            return;
        }
        String icon = "";
        for (LauncherIconController.LauncherIcon i : LauncherIconController.LauncherIcon.values()) {
            if (LauncherIconController.isEnabled(i)) {
                icon = getString(i.title);
            }
        }
        setTileInfo(appearanceTile, getString(TelezhkaAppearance.THEME_NAMES[TelezhkaAppearance.themeIndex()]) + (icon.isEmpty() ? "" : " · " + icon));
        setTileInfo(hiddenTile, getString(TelezhkaHiddenChats.hasPin() ? R.string.TelezhkaPinIsSet : R.string.TelezhkaPinNotSet));
        setTileInfo(connectionTile, getString(TelezhkaProxyAutopilot.isEnabled() ? R.string.TelezhkaTileAuto : R.string.TelezhkaTileManual));
        setTileInfo(autoReplyRow, TelezhkaAutoReply.isEnabled() ? formatString(R.string.TelezhkaAutoReplyOn, TelezhkaAutoReply.answeredCount()) : getString(R.string.TelezhkaAutoReplyRowOff));
        setTileInfo(updatesTile, BuildVars.TELEZHKA_BUILD > 0 ? formatString(R.string.TelezhkaTileBuild, BuildVars.TELEZHKA_BUILD) : getString(R.string.TelezhkaLocalBuildShort));
    }

    // Hidden chats: no PIN yet -> create one; otherwise how to open them and changing the PIN
    private void openHidden() {
        if (!TelezhkaHiddenChats.hasPin()) {
            changePin();
            return;
        }
        TelezhkaPopup popup = new TelezhkaPopup(getParentActivity());
        popup.setIcon(R.drawable.msg_secret)
                .setTitleText(getString(R.string.TelezhkaHiddenTitle))
                .addDotItem(getString(R.string.TelezhkaHowToOpenHiddenInfo))
                .setMainButton(getString(R.string.TelezhkaChangePin), v -> {
                    popup.dismiss();
                    changePin();
                })
                .setSecondaryButton(getString(R.string.Close), v -> popup.dismiss());
        showDialog(popup);
    }

    private LinearLayout tile(Context context, int icon, int color, String title, Runnable onClick) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(14), dp(14), dp(14));
        tile.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(20), PANEL, 0xFF1D222C));
        tile.setMinimumHeight(dp(104));
        FrameLayout box = new FrameLayout(context);
        box.setBackground(Theme.createRoundRectDrawable(dp(12), (color & 0x00FFFFFF) | 0x29000000));
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        box.addView(image, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        tile.addView(box, LayoutHelper.createLinear(36, 36));
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(title);
        tile.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        TextView infoView = new TextView(context);
        infoView.setTag("info");
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        infoView.setTextColor(MUTE);
        infoView.setSingleLine(true);
        infoView.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }

    private static void setTileInfo(LinearLayout tile, String text) {
        TextView info = tile.findViewWithTag("info");
        if (info != null) {
            info.setText(text);
        }
    }

    private ImageView chevron(Context context) {
        ImageView view = new ImageView(context);
        view.setImageResource(R.drawable.msg_arrowright);
        view.setColorFilter(new PorterDuffColorFilter(MUTE, PorterDuff.Mode.SRC_IN));
        return view;
    }

    private void changePin() {
        if (!TelezhkaHiddenChats.hasPin()) {
            presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_CREATE, fragment -> {
                fragment.finishFragment();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinSaved)).show();
            }));
            return;
        }
        // the old PIN first, then the new one
        presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_ENTER, fragment ->
                fragment.presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_CREATE, next -> {
                    next.finishFragment();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinSaved)).show();
                }), true)));
    }

    // Turning on a risky option for the first time explains the risk once
    private void confirmRisk(Runnable onYes) {
        if (ApplicationLoader.applicationContext.getSharedPreferences("telezhka_privacy", Context.MODE_PRIVATE).getBoolean(RISK_ACK, false)) {
            onYes.run();
            return;
        }
        TelezhkaPopup popup = new TelezhkaPopup(getParentActivity());
        popup.setIcon(R.drawable.msg_info)
                .setTitleText(getString(R.string.TelezhkaRiskTitle))
                .addNumberedItem(getString(R.string.TelezhkaRisk1))
                .addNumberedItem(getString(R.string.TelezhkaRisk2))
                .setMainButton(getString(R.string.TelezhkaRiskYes), v -> {
                    popup.dismiss();
                    ApplicationLoader.applicationContext.getSharedPreferences("telezhka_privacy", Context.MODE_PRIVATE).edit().putBoolean(RISK_ACK, true).apply();
                    onYes.run();
                })
                .setSecondaryButton(getString(R.string.Cancel), v -> popup.dismiss());
        showDialog(popup);
    }

    private LinearLayout switchRow(Context context, int icon, int color, String title, String info, String key, boolean risky) {
        Switch sw = new Switch(context);
        sw.setChecked(TelezhkaPrivacy.getRaw(key), false);
        LinearLayout row = row(context, icon, color, title, info, sw);
        row.setOnClickListener(v -> {
            boolean now = !TelezhkaPrivacy.getRaw(key);
            Runnable apply = () -> {
                TelezhkaPrivacy.set(key, now);
                sw.setChecked(now, true);
            };
            if (now && risky) {
                confirmRisk(apply);
            } else {
                apply.run();
            }
        });
        return row;
    }

    private LinearLayout linkRow(Context context, String title, String info, Runnable onClick) {
        ImageView chevron = null;
        if (onClick != null) {
            chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setColorFilter(new PorterDuffColorFilter(MUTE, PorterDuff.Mode.SRC_IN));
        }
        LinearLayout row = row(context, 0, 0, title, info, chevron);
        if (onClick != null) {
            row.setOnClickListener(v -> onClick.run());
        } else {
            row.setBackground(null);
        }
        return row;
    }

    private LinearLayout row(Context context, int icon, int color, String title, String info, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0, 0x0FFFFFFF));
        if (icon != 0) {
            FrameLayout tile = new FrameLayout(context);
            tile.setBackground(Theme.createRoundRectDrawable(dp(12), (color & 0x00FFFFFF) | 0x29000000));
            ImageView image = new ImageView(context);
            image.setImageResource(icon);
            image.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            tile.addView(image, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            row.addView(tile, LayoutHelper.createLinear(36, 36, Gravity.TOP, 0, 0, 12, 0));
        }
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(TEXT);
        titleView.setText(title);
        texts.addView(titleView);
        if (!TextUtils.isEmpty(info)) {
            TextView infoView = new TextView(context);
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setTextColor(MUTE);
            infoView.setLineSpacing(dp(1.5f), 1f);
            infoView.setText(info);
            infoView.setTag("info");
            texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, trailing != null ? 12 : 0, 0));
        if (trailing instanceof Switch) {
            row.addView(trailing, LayoutHelper.createLinear(37, 40));
        } else if (trailing != null) {
            row.addView(trailing, LayoutHelper.createLinear(20, 20));
        }
        return row;
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Theme.createRoundRectDrawable(dp(20), PANEL));
        return card;
    }

    private TextView header(Context context, String text) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.12f);
        header.setTextColor(SIGNAL);
        header.setText(text.toUpperCase());
        header.setPadding(dp(6), dp(22), 0, dp(8));
        return header;
    }
}

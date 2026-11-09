/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaExport;
import org.telegram.messenger.TelezhkaExportWriter;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;

/**
 * "Сохранить переписку" from the chat menu: pick HTML or TXT and how far back, watch the progress,
 * then share the file or put it into Downloads. All steps happen in one Telezhka popup.
 */
public class TelezhkaExportPopup {

    private static final int[] PERIOD_NAMES = {R.string.TelezhkaExportAll, R.string.TelezhkaExportYear, R.string.TelezhkaExportMonth, R.string.TelezhkaExportWeek};
    private static final int[] PERIOD_DAYS = {0, 365, 30, 7};

    private final BaseFragment fragment;
    private final Activity activity;
    private final int account;
    private final long dialogId;
    private final TelezhkaPopup popup;
    private String title = "";
    private int kind = TelezhkaExportWriter.KIND_PRIVATE;
    private boolean html = true;
    private int period = 0;
    private TelezhkaExport export;
    private File result;
    private float lastProgress;

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new TelezhkaExportPopup(fragment, dialogId).open();
    }

    private TelezhkaExportPopup(BaseFragment fragment, long dialogId) {
        this.fragment = fragment;
        this.activity = fragment.getParentActivity();
        this.account = fragment.getCurrentAccount();
        this.dialogId = dialogId;
        this.popup = new TelezhkaPopup(activity);

        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            if (user != null) {
                title = UserObject.isUserSelf(user) ? getString(R.string.SavedMessages) : UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat != null) {
                title = chat.title;
                kind = ChatObject.isChannelAndNotMegaGroup(chat) ? TelezhkaExportWriter.KIND_CHANNEL : TelezhkaExportWriter.KIND_GROUP;
            }
        }
    }

    private void open() {
        popup.setIcon(R.drawable.msg_download).setTitleText(getString(R.string.TelezhkaExportTitle));
        popup.addDotItem(getString(R.string.TelezhkaExportInfo));
        popup.addContent(label(getString(R.string.TelezhkaExportFormat)));
        popup.addContent(chips(new String[]{getString(R.string.TelezhkaExportHtml), getString(R.string.TelezhkaExportTxt)}, html ? 0 : 1, index -> html = index == 0));
        popup.addContent(label(getString(R.string.TelezhkaExportPeriod)));
        String[] periods = new String[PERIOD_NAMES.length];
        for (int i = 0; i < periods.length; i++) {
            periods[i] = getString(PERIOD_NAMES[i]);
        }
        popup.addContent(chips(periods, period, index -> period = index));
        popup.setMainButton(getString(R.string.TelezhkaExportStart), v -> start());
        popup.setSecondaryButton(getString(R.string.Cancel), v -> popup.dismiss());
        if (fragment.showDialog(popup, d -> {
            if (export != null) {
                export.cancel();
            }
        }) == null) {
            popup.show();
        }
    }

    private void start() {
        // a long job: an accidental tap outside should not stop it, the back button and "Отменить" do
        popup.setCancelableOutside(false);
        popup.setCancelable(true);
        popup.clearItems();
        popup.setTitleText(getString(R.string.TelezhkaExportLoadingTitle));
        popup.addDotItem(formatString(R.string.TelezhkaExportLoadingInfo, title));
        popup.showProgress(true);
        lastProgress = 0;
        popup.setProgress(0, getString(R.string.TelezhkaExportLoading), "");
        popup.setProgressCancel(getString(R.string.TelezhkaExportCancel), v -> popup.dismiss());

        int since = PERIOD_DAYS[period] > 0 ? (int) (System.currentTimeMillis() / 1000) - PERIOD_DAYS[period] * 86400 : 0;
        export = new TelezhkaExport(account, dialogId, title, kind, html, since, new TelezhkaExport.Listener() {
            @Override
            public void onProgress(int loaded, float progress) {
                lastProgress = Math.max(0, progress);
                popup.setProgress(Math.max(0, progress), formatString(R.string.TelezhkaExportLoaded, TelezhkaExportWriter.messagesCount(loaded)),
                        progress >= 0 ? Math.round(progress * 100) + "%" : "");
            }

            @Override
            public void onWait(int seconds) {
                popup.setProgress(lastProgress, formatString(R.string.TelezhkaExportWait, seconds), "");
            }

            @Override
            public void onDone(File file, int count) {
                export = null;
                result = file;
                showDone(count);
            }

            @Override
            public void onError(String error) {
                export = null;
                showError(error);
            }
        });
        export.start();
    }

    private void showDone(int count) {
        popup.showProgress(false);
        popup.setCancelableOutside(true);
        popup.clearItems();
        popup.setTitleText(getString(R.string.TelezhkaExportDone));
        popup.addDotItem(formatString(R.string.TelezhkaExportDoneInfo, TelezhkaExportWriter.messagesCount(count), AndroidUtilities.formatFileSize(result.length())));
        popup.addDotItem(getString(html ? R.string.TelezhkaExportDoneHtml : R.string.TelezhkaExportDoneTxt));
        popup.setMainButton(getString(R.string.TelezhkaExportShare), v -> share());
        popup.setSecondaryButton(getString(R.string.TelezhkaExportSave), v -> saveToDownloads());
    }

    private void showError(String error) {
        popup.showProgress(false);
        popup.setCancelableOutside(true);
        popup.clearItems();
        popup.setTitleText(getString(R.string.TelezhkaExportFailed));
        popup.addDotItem(formatString(R.string.TelezhkaExportFailedInfo, error == null ? "?" : error));
        popup.setMainButton(getString(R.string.TelezhkaExportRetry), v -> start());
        popup.setSecondaryButton(getString(R.string.TelezhkaExportClose), v -> popup.dismiss());
    }

    private void share() {
        if (result == null || !result.exists()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(html ? "text/html" : "text/plain");
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", result);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            uri = Uri.fromFile(result);
        }
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, result.getName());
        try {
            activity.startActivity(Intent.createChooser(intent, getString(R.string.ShareFile)));
            popup.dismiss();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveToDownloads() {
        if (result == null || !result.exists()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE)
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.TelezhkaExportNeedPermission)).show();
            return;
        }
        popup.dismiss();
        MediaController.saveFile(result.getAbsolutePath(), activity, 2, result.getName(), html ? "text/html" : "text/plain", uri -> {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaExportSaved)).show();
        });
    }

    // ---------- small views in the popup's style ----------

    private interface IndexListener {
        void onSelect(int index);
    }

    private TextView label(String text) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.12f);
        view.setAllCaps(true);
        view.setTextColor(TelezhkaPopup.MUTE);
        view.setText(text);
        view.setPadding(dp(2), dp(6), 0, 0);
        return view;
    }

    private View chips(String[] names, int selected, IndexListener listener) {
        Context context = activity;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] views = new TextView[names.length];
        final int[] current = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < views.length; i++) {
                boolean on = i == current[0];
                views[i].setTextColor(on ? 0xFFFFFFFF : TelezhkaPopup.TEXT_2);
                views[i].setBackground(Theme.createRoundRectDrawable(dp(14), on ? TelezhkaPopup.SIGNAL : 0x14FFFFFF));
            }
        };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setText(names[i]);
            chip.setPadding(dp(6), dp(9), dp(6), dp(9));
            chip.setOnClickListener(v -> {
                current[0] = index;
                paint.run();
                listener.onSelect(index);
            });
            views[i] = chip;
            row.addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 6, 0, 0, 0));
        }
        paint.run();
        return row;
    }
}

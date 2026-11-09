/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * How many people really use Telezhka: once a day the app says "hi" to the bot
 * (BuildVars.MUSIC_BOT_USERNAME) with the inline query "tz:hi <build>".
 * An inline query creates no message and no notification, and goes through the same
 * connection as Telegram itself, so it works wherever Telezhka works.
 * The bot keeps only the day, the build and a hash of the account (see app_stats.py in the bot).
 * Sent from the first logged-in account, so several accounts on one phone count as one person.
 * Can be turned off in Telezhka settings.
 */
public class TelezhkaStats {

    private static final String PREFS = "telezhka_stats";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_DAY = "last_day";
    private static final long RETRY_MS = 15 * 60 * 1000L;
    // days are counted by Moscow time, the same as in the bot (app_stats.MSK)
    private static final TimeZone DAY_ZONE = TimeZone.getTimeZone("GMT+03:00");

    private static boolean sending;
    private static long lastAttempt;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            ping();
        }
    }

    private static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(DAY_ZONE);
        return format.format(new Date());
    }

    private static int firstAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    /** The chat list was opened: say "hi" if it has not been said today. */
    public static void ping() {
        if (BuildVars.TELEZHKA_BUILD <= 0 || BuildVars.TELEZHKA_TEST || sending || !isEnabled()) {
            return;  // local and test builds are not counted
        }
        String today = today();
        if (today.equals(prefs().getString(KEY_LAST_DAY, ""))) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastAttempt != 0 && now - lastAttempt < RETRY_MS) {
            return;
        }
        int account = firstAccount();
        if (account < 0) {
            return;
        }
        sending = true;
        lastAttempt = now;
        withBot(account, bot -> {
            TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
            req.bot = MessagesController.getInstance(account).getInputUser(bot);
            req.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
            req.query = "tz:hi " + BuildVars.TELEZHKA_BUILD;
            req.offset = "";
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                sending = false;
                if (response instanceof TLRPC.messages_BotResults) {
                    prefs().edit().putString(KEY_LAST_DAY, today).apply();
                } else if (error != null) {
                    FileLog.e("telezhka stats ping failed " + error.text);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }, () -> sending = false);
    }

    private static void withBot(int account, Utilities.Callback<TLRPC.User> callback, Runnable onError) {
        TLRPC.User cached = MessagesController.getInstance(account).getUser(BuildVars.MUSIC_BOT_USERNAME);
        if (cached != null) {
            callback.run(cached);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BuildVars.MUSIC_BOT_USERNAME;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer && !((TLRPC.TL_contacts_resolvedPeer) response).users.isEmpty()) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesStorage.getInstance(account).putUsersAndChats(res.users, null, true, true);
                callback.run(res.users.get(0));
            } else {
                onError.run();
            }
        }));
    }
}

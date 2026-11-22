/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ProxyPickerActivity;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Keeps proxies working without the user doing anything:
 * - every 30 minutes (and soon after start) downloads the fresh list, adds new proxies,
 *   checks all of them and removes the ones that failed two checks in a row;
 * - switches to the fastest spare when the current proxy stops answering or gets much slower;
 * - when Telegram cannot connect and no check answers, runs the same emergency search as the
 *   "Подобрать прокси" button (TelezhkaProxyRescue) and repeats it until it connects:
 *   after 20 s, 1 min, 2 min, 5 min and then every 5 min, and right away when the network
 *   comes back or the app is opened. Does nothing while there is no internet at all.
 * Can be switched off on the "Подключение" screen; while it searches, the screen shows a status line.
 */
public class TelezhkaProxyAutopilot implements NotificationCenter.NotificationCenterDelegate {

    public static final String PREF_ENABLED = "telezhka_autopilot";
    public static final String PREF_LAST_RUN = "telezhka_autopilot_last";
    public static final String PREF_ADDED = "telezhka_autopilot_added";
    public static final String PREF_REMOVED = "telezhka_autopilot_removed";

    private static final long PERIOD_MS = 30 * 60 * 1000L;
    private static final long FIRST_RUN_DELAY_MS = 20 * 1000L;
    private static final long STUCK_MS = 20 * 1000L;
    private static final long URGENT_THROTTLE_MS = 90 * 1000L;
    private static final long CHECK_TIMEOUT_MS = 7000;
    private static final int PARALLEL_CHECKS = 12;
    // Kept after checks; fresh proxies may push the list above it until checks prove which ones are dead
    private static final int MAX_PROXIES = 40;
    // Hard limit for the saved list
    private static final int MAX_STORED = 100;
    // Without a connection only this many are checked with checkProxy before the emergency search starts
    private static final int URGENT_CHECK_LIMIT = 24;
    private static final int RESCUE_TRIES = 8;

    private static final long[] RETRY_DELAYS_MS = {20 * 1000L, 60 * 1000L, 2 * 60 * 1000L, 5 * 60 * 1000L};
    private static final long BUSY_RETRY_MS = 20 * 1000L;
    private static final long NETWORK_GRACE_MS = 3000;
    private static final long FOREGROUND_THROTTLE_MS = 15 * 1000L;
    private static final long RECHECK_AFTER_RESCUE_MS = 60 * 1000L;

    private static final TelezhkaProxyAutopilot INSTANCE = new TelezhkaProxyAutopilot();

    private boolean running;
    private boolean urgentRun;
    private long lastUrgentRun;
    private int lastState = -1;

    // Emergency search failed and the app is still offline: retries are scheduled
    private boolean inTrouble;
    private int retryStep;
    private long nextRetryAt;
    private TelezhkaProxyRescue ownRescue;

    private final Runnable periodic = () -> run(false);
    private final Runnable stuckCheck = this::onStuck;
    private final Runnable retry = this::onRetry;

    public static void init() {
        INSTANCE.initInternal();
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            INSTANCE.schedule(1000);
        } else {
            if (INSTANCE.ownRescue != null) {
                INSTANCE.ownRescue.cancel();
            }
            INSTANCE.resetTrouble();
        }
        TelezhkaProxyRescue.notifyStatus();
    }

    // Run now, e.g. from the "Подключение" screen
    public static void runNow() {
        INSTANCE.run(true);
    }

    // Connectivity changed (ApplicationLoader): if we were stuck without a proxy, try again right away
    public static void onNetworkChanged() {
        AndroidUtilities.runOnUIThread(INSTANCE::onNetworkBack);
    }

    // The app was opened (LaunchActivity.onResume)
    public static void onAppForeground() {
        INSTANCE.onForeground();
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        schedule(FIRST_RUN_DELAY_MS);
    }

    private void schedule(long delay) {
        AndroidUtilities.cancelRunOnUIThread(periodic);
        AndroidUtilities.runOnUIThread(periodic, delay);
    }

    private static int connectionState() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
    }

    private static boolean isConnected() {
        int state = connectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState || account != UserConfig.selectedAccount) {
            return;
        }
        int state = ConnectionsManager.getInstance(account).getConnectionState();
        boolean networkBack = lastState == ConnectionsManager.ConnectionStateWaitingForNetwork && state != ConnectionsManager.ConnectionStateWaitingForNetwork;
        lastState = state;
        if (state == ConnectionsManager.ConnectionStateConnecting || state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
            AndroidUtilities.runOnUIThread(stuckCheck, STUCK_MS);
        } else {
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
        }
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            resetTrouble();
        } else if (networkBack) {
            onNetworkBack();
        }
        TelezhkaProxyRescue.notifyStatus();
    }

    // Still not connected after a while: Telegram's own rotation did not help, act now
    private void onStuck() {
        if (!isEnabled() || isConnected() || inTrouble) {
            return;  // inTrouble: the retry schedule takes care of it
        }
        if (connectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return;  // no internet at all, proxies will not help
        }
        long wait = URGENT_THROTTLE_MS - (SystemClock.elapsedRealtime() - lastUrgentRun);
        if (wait > 0) {
            // Ran not long ago: look again later instead of dropping the check
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
            AndroidUtilities.runOnUIThread(stuckCheck, wait);
            return;
        }
        run(true);
    }

    // ---- retries while there is no connection ----

    private void onRetry() {
        nextRetryAt = 0;
        if (!isEnabled() || isConnected()) {
            resetTrouble();
            TelezhkaProxyRescue.notifyStatus();
            return;
        }
        if (connectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            // No internet: wait for it, onNetworkBack starts the next try
            TelezhkaProxyRescue.notifyStatus();
            return;
        }
        run(true);
    }

    private void scheduleNextRetry() {
        long delay = RETRY_DELAYS_MS[Math.min(retryStep, RETRY_DELAYS_MS.length - 1)];
        retryStep++;
        retryIn(delay);
    }

    private void retryIn(long delay) {
        AndroidUtilities.cancelRunOnUIThread(retry);
        nextRetryAt = SystemClock.elapsedRealtime() + delay;
        AndroidUtilities.runOnUIThread(retry, delay);
        TelezhkaProxyRescue.notifyStatus();
    }

    private void resetTrouble() {
        inTrouble = false;
        retryStep = 0;
        nextRetryAt = 0;
        AndroidUtilities.cancelRunOnUIThread(retry);
    }

    private void onNetworkBack() {
        if (!inTrouble || !isEnabled() || running || isConnected()) {
            return;
        }
        // A short pause: if Telegram connects by itself, the retry sees it and does nothing
        retryIn(NETWORK_GRACE_MS);
    }

    private void onForeground() {
        if (!inTrouble || !isEnabled() || running || isConnected()) {
            return;
        }
        if (SystemClock.elapsedRealtime() - lastUrgentRun < FOREGROUND_THROTTLE_MS) {
            return;
        }
        retryIn(1000);
    }

    // ---- one run ----

    private void run(boolean urgent) {
        if (!urgent) {
            schedule(PERIOD_MS);
        }
        if (!isEnabled()) {
            return;
        }
        if (running || TelezhkaProxyRescue.isRunning()) {
            if (urgent && !isConnected()) {
                retryIn(BUSY_RETRY_MS);
            }
            return;
        }
        running = true;
        urgentRun = urgent;
        if (urgent) {
            lastUrgentRun = SystemClock.elapsedRealtime();
        }
        TelezhkaProxyRescue.notifyStatus();
        Utilities.externalNetworkQueue.postRunnable(() -> {
            ArrayList<ProxySettings> fresh = new ArrayList<>();
            for (String url : ProxyPickerActivity.getListUrls()) {
                fresh = ProxyPickerActivity.parseProxyList(ProxyPickerActivity.downloadList(url));
                if (!fresh.isEmpty()) {
                    break;
                }
            }
            if (fresh.isEmpty()) {
                fresh = ProxyPickerActivity.loadFallbackList();
            }
            final ArrayList<ProxySettings> list = fresh;
            AndroidUtilities.runOnUIThread(() -> merge(list, urgent));
        });
    }

    private void merge(ArrayList<ProxySettings> fresh, boolean urgent) {
        SharedConfig.loadProxyList();
        HashSet<ProxySettings> known = new HashSet<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            known.add(info.settings);
        }
        // New proxies always get in: old dead ones must not take their places.
        // The list is trimmed back after checks, and only by proven failures.
        int added = 0;
        for (ProxySettings settings : fresh) {
            if (SharedConfig.proxyList.size() >= MAX_STORED) {
                break;
            }
            if (known.add(settings)) {
                SharedConfig.proxyList.add(new SharedConfig.ProxyInfo(settings));
                added++;
            }
        }
        if (SharedConfig.proxyList.isEmpty()) {
            endRun(urgent, false);
            return;
        }
        checkAll(fresh, added, urgent);
    }

    private void checkAll(ArrayList<ProxySettings> fresh, int added, boolean urgent) {
        // Fresh list first, then the rest
        final ArrayList<SharedConfig.ProxyInfo> queue = new ArrayList<>();
        HashSet<ProxySettings> freshSet = new HashSet<>(fresh);
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (freshSet.contains(info.settings)) {
                queue.add(info);
            }
        }
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (!freshSet.contains(info.settings)) {
                queue.add(info);
            }
        }
        if (urgent && !isConnected() && queue.size() > URGENT_CHECK_LIMIT) {
            // No connection: do not spend minutes on checks that will likely all time out
            queue.subList(URGENT_CHECK_LIMIT, queue.size()).clear();
        }
        final int[] next = {0};
        final int[] active = {0};
        final int[] responded = {0};
        final HashMap<SharedConfig.ProxyInfo, Long> results = new HashMap<>();
        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            while (active[0] < PARALLEL_CHECKS && next[0] < queue.size()) {
                SharedConfig.ProxyInfo info = queue.get(next[0]++);
                active[0]++;
                final boolean[] done = {false};
                final Runnable timeout = () -> {
                    if (!done[0]) {
                        done[0] = true;
                        results.put(info, -1L);
                        active[0]--;
                        pump[0].run();
                    }
                };
                AndroidUtilities.runOnUIThread(timeout, CHECK_TIMEOUT_MS);
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                    if (done[0]) {
                        return;
                    }
                    done[0] = true;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    responded[0]++;
                    results.put(info, time);
                    active[0]--;
                    pump[0].run();
                }));
            }
            if (active[0] == 0 && next[0] >= queue.size()) {
                finish(results, responded[0] > 0, fresh, added, urgent);
            }
        };
        pump[0].run();
    }

    private void finish(HashMap<SharedConfig.ProxyInfo, Long> results, boolean online, ArrayList<ProxySettings> fresh, int added, boolean urgent) {
        long now = SystemClock.elapsedRealtime();
        HashSet<ProxySettings> freshSet = new HashSet<>(fresh);
        SharedConfig.ProxyInfo best = null;
        ArrayList<SharedConfig.ProxyInfo> failedNow = new ArrayList<>();
        ArrayList<SharedConfig.ProxyInfo> workingNow = new ArrayList<>();
        for (Map.Entry<SharedConfig.ProxyInfo, Long> entry : results.entrySet()) {
            SharedConfig.ProxyInfo info = entry.getKey();
            long ping = entry.getValue();
            info.availableCheckTime = now;
            info.checking = false;
            if (ping > 0) {
                info.ping = ping;
                info.available = true;
                TelezhkaProxyRescue.clearFailures(info.settings);
                workingNow.add(info);
                if (best == null || ping < best.ping) {
                    best = info;
                }
            } else {
                info.available = false;
                info.ping = 0;
                if (online) {
                    // Only count a failure when other proxies did answer: otherwise the network itself is down
                    TelezhkaProxyRescue.addFailure(info.settings);
                    failedNow.add(info);
                }
            }
        }
        int removed = TelezhkaProxyRescue.removeProvenDead();
        if (online) {
            removed += trim(failedNow, workingNow, freshSet);
        }

        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        boolean proxyOn = SharedConfig.isProxyEnabled();
        if (best != null) {
            if (!proxyOn) {
                // Only turn a proxy on when Telegram cannot connect without one
                if (urgent && !isConnected()) {
                    TelezhkaProxyRescue.enable(best);
                }
            } else if (current == null || !current.available) {
                TelezhkaProxyRescue.enable(best);
            } else if (best != current && current.ping > 400 && best.ping * 2 < current.ping) {
                TelezhkaProxyRescue.enable(best);
            }
        }

        SharedConfig.saveProxyList();
        MessagesController.getGlobalMainSettings().edit()
                .putLong(PREF_LAST_RUN, System.currentTimeMillis())
                .putInt(PREF_ADDED, added)
                .putInt(PREF_REMOVED, removed)
                .apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

        if (best == null && urgent && !isConnected()
                && connectionState() != ConnectionsManager.ConnectionStateWaitingForNetwork) {
            // No check answered (or all failed) and still offline: emergency search, like the button
            startRescue(fresh);
            return;
        }
        endRun(urgent, best != null);
    }

    // Back down to MAX_PROXIES. Only proxies that just failed with proof (others answered) or,
    // if still too many, the slowest working ones go. Unchecked proxies are never removed here.
    private int trim(ArrayList<SharedConfig.ProxyInfo> failedNow, ArrayList<SharedConfig.ProxyInfo> workingNow, HashSet<ProxySettings> freshSet) {
        int removed = 0;
        // Old failed ones before fresh failed ones
        java.util.Collections.sort(failedNow, (a, b) -> Boolean.compare(freshSet.contains(a.settings), freshSet.contains(b.settings)));
        for (SharedConfig.ProxyInfo info : failedNow) {
            if (SharedConfig.proxyList.size() <= MAX_PROXIES) {
                return removed;
            }
            if (info != SharedConfig.currentProxy && SharedConfig.proxyList.remove(info)) {
                TelezhkaProxyRescue.clearFailures(info.settings);
                removed++;
            }
        }
        java.util.Collections.sort(workingNow, (a, b) -> Long.compare(b.ping, a.ping));
        for (SharedConfig.ProxyInfo info : workingNow) {
            if (SharedConfig.proxyList.size() <= MAX_PROXIES) {
                break;
            }
            if (info != SharedConfig.currentProxy) {
                SharedConfig.proxyList.remove(info);
            }
        }
        return removed;
    }

    private void startRescue(ArrayList<ProxySettings> fresh) {
        if (TelezhkaProxyRescue.isRunning()) {
            // The picker screen is searching already
            endRun(true, false);
            return;
        }
        ArrayList<ProxySettings> list = new ArrayList<>(fresh);
        HashSet<ProxySettings> seen = new HashSet<>(fresh);
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (seen.add(info.settings)) {
                list.add(info.settings);
            }
        }
        ownRescue = TelezhkaProxyRescue.start(list, fresh.size(), RESCUE_TRIES, this::onRescueFinished);
    }

    private void onRescueFinished(TelezhkaProxyRescue.Result result) {
        ownRescue = null;
        if (result.cancelled) {
            running = false;
            urgentRun = false;
            // The user started a search by hand or switched the autopilot off; look again later if still offline
            if (isEnabled() && !isConnected()) {
                inTrouble = true;
                retryIn(RETRY_DELAYS_MS[1]);
            }
            TelezhkaProxyRescue.notifyStatus();
            return;
        }
        endRun(true, result.settings != null);
        if (result.settings != null) {
            // Spares from the emergency search only accepted TCP: check them properly once connected
            schedule(RECHECK_AFTER_RESCUE_MS);
        }
    }

    private void endRun(boolean urgent, boolean found) {
        running = false;
        urgentRun = false;
        if (urgent && !found && isEnabled() && !isConnected()) {
            inTrouble = true;
            scheduleNextRetry();
        } else if (found || isConnected()) {
            resetTrouble();
        }
        TelezhkaProxyRescue.notifyStatus();
    }

    // ---- status line for the "Подключение" screen; null when there is nothing to show ----

    public static String getSearchStatus() {
        if (!isEnabled()) {
            return null;
        }
        TelezhkaProxyRescue rescue = TelezhkaProxyRescue.getActive();
        if (rescue != null) {
            if (rescue.getPhase() == TelezhkaProxyRescue.PHASE_TRYING) {
                return formatString(R.string.TelezhkaSearchTrying, rescue.getAttempt(), rescue.getAttempts());
            }
            return formatString(R.string.TelezhkaSearchTcp, rescue.getTotal());
        }
        if (INSTANCE.running && INSTANCE.urgentRun) {
            return getString(R.string.TelezhkaSearchLoading);
        }
        if (!INSTANCE.inTrouble || isConnected()) {
            return null;
        }
        if (connectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return getString(R.string.TelezhkaSearchNoNetwork);
        }
        if (INSTANCE.nextRetryAt > 0) {
            long left = Math.max(0, INSTANCE.nextRetryAt - SystemClock.elapsedRealtime());
            String when = left < 60 * 1000L
                    ? formatString(R.string.TelezhkaSearchSeconds, Math.max(1, (int) Math.ceil(left / 1000.0)))
                    : formatString(R.string.TelezhkaSearchMinutes, (int) Math.ceil(left / 60000.0));
            return formatString(R.string.TelezhkaSearchRetry, when);
        }
        return null;
    }
}

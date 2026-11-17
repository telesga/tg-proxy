/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * "Emergency mode": used when Telegram cannot connect at all and proxy checks get no answer
 * (checkProxy needs a working connection or an auth key, which a blocked phone may not have).
 *
 * 1. checks every candidate with a plain TCP connect;
 * 2. turns reachable proxies on one by one and waits for a real connection to Telegram;
 * 3. on success keeps the working proxy on and saves the other reachable ones as spares;
 *    on failure puts the proxy settings back as they were.
 *
 * No UI here: the picker screen and the autopilot both use it. Only one search runs at a time,
 * starting a new one cancels the old one. All callbacks and public methods are on the UI thread.
 */
public class TelezhkaProxyRescue {

    public static final int DEFAULT_TRIES = 5;
    // Failures with proof (others answered, or TCP worked but Telegram did not) before a saved proxy is removed
    public static final int FAILS_TO_REMOVE = 2;

    private static final long TCP_TIMEOUT_MS = 7000;
    private static final int TCP_PARALLEL = 16;
    private static final long CONNECT_WAIT_MS = 12000;
    private static final long CONNECT_POLL_MS = 500;

    public static final int PHASE_TCP = 1;
    public static final int PHASE_TRYING = 2;

    public static class Result {
        public final ProxySettings settings;  // null: nothing connected
        public final int index;               // position of settings in the list passed to start()
        public final long ping;               // TCP connect time, ms
        public final int spares;
        public final boolean cancelled;

        Result(ProxySettings settings, int index, long ping, int spares, boolean cancelled) {
            this.settings = settings;
            this.index = index;
            this.ping = ping;
            this.spares = spares;
            this.cancelled = cancelled;
        }
    }

    public interface Callback {
        default void onTcpChecked(int index, boolean reachable) {
        }

        default void onTcpDone(int reachable, int total) {
        }

        default void onTrying(int index, int attempt, int attempts) {
        }

        default void onTryFailed(int index) {
        }

        void onFinished(Result result);
    }

    // ---- proof-based failure counts, shared with the autopilot ----

    private static final HashMap<ProxySettings, Integer> failures = new HashMap<>();

    public static int getFailures(ProxySettings settings) {
        Integer count = failures.get(settings);
        return count == null ? 0 : count;
    }

    public static int addFailure(ProxySettings settings) {
        int count = getFailures(settings) + 1;
        failures.put(settings, count);
        return count;
    }

    public static void clearFailures(ProxySettings settings) {
        failures.remove(settings);
    }

    // Removes saved proxies that failed FAILS_TO_REMOVE times with proof. Never the current one.
    public static int removeProvenDead() {
        int removed = 0;
        for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
            SharedConfig.ProxyInfo info = SharedConfig.proxyList.get(i);
            if (info != SharedConfig.currentProxy && getFailures(info.settings) >= FAILS_TO_REMOVE) {
                SharedConfig.proxyList.remove(i);
                failures.remove(info.settings);
                removed++;
            }
        }
        return removed;
    }

    // ---- status for the "Подключение" screen ----

    private static TelezhkaProxyRescue active;
    private static final ArrayList<Runnable> statusListeners = new ArrayList<>();

    public static boolean isRunning() {
        return active != null;
    }

    public static TelezhkaProxyRescue getActive() {
        return active;
    }

    public static void addStatusListener(Runnable listener) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    public static void removeStatusListener(Runnable listener) {
        statusListeners.remove(listener);
    }

    public static void notifyStatus() {
        for (Runnable listener : new ArrayList<>(statusListeners)) {
            listener.run();
        }
    }

    // ---- one search ----

    private static class Candidate {
        final ProxySettings settings;
        final int index;
        final boolean preferred;
        volatile long ping = -1;

        Candidate(ProxySettings settings, int index, boolean preferred) {
            this.settings = settings;
            this.index = index;
            this.preferred = preferred;
        }
    }

    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private final int tries;
    private final Callback callback;
    private final boolean wasEnabled;
    private final SharedConfig.ProxyInfo wasCurrent;
    private final HashSet<ProxySettings> savedBefore = new HashSet<>();
    private volatile boolean cancelled;
    private boolean finished;
    private Runnable pendingWait;

    private int phase;
    private int attempt;
    private int attempts;

    public int getPhase() {
        return phase;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getTotal() {
        return candidates.size();
    }

    /**
     * @param list      candidates, best first
     * @param preferred the first {@code preferred} entries (the fresh list) are tried before the rest
     * @param tries     how many reachable proxies to turn on before giving up
     */
    public static TelezhkaProxyRescue start(List<ProxySettings> list, int preferred, int tries, Callback callback) {
        if (active != null) {
            active.cancel();
        }
        TelezhkaProxyRescue rescue = new TelezhkaProxyRescue(list, preferred, tries, callback);
        active = rescue;
        rescue.runTcpChecks();
        notifyStatus();
        return rescue;
    }

    private TelezhkaProxyRescue(List<ProxySettings> list, int preferred, int tries, Callback callback) {
        SharedConfig.loadProxyList();
        HashSet<ProxySettings> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            ProxySettings settings = list.get(i);
            if (settings != null && seen.add(settings)) {
                candidates.add(new Candidate(settings, i, i < preferred));
            }
        }
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            savedBefore.add(info.settings);
        }
        this.tries = Math.max(1, tries);
        this.callback = callback;
        this.wasEnabled = SharedConfig.isProxyEnabled();
        this.wasCurrent = SharedConfig.currentProxy;
    }

    // Stops the search and puts the proxy settings back. The callback gets a cancelled result.
    public void cancel() {
        if (finished) {
            return;
        }
        cancelled = true;
        if (pendingWait != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingWait);
            pendingWait = null;
        }
        if (phase == PHASE_TRYING) {
            restore();
        }
        done(new Result(null, -1, 0, 0, true));
    }

    private void done(Result result) {
        if (finished) {
            return;
        }
        finished = true;
        if (active == this) {
            active = null;
        }
        phase = 0;
        if (callback != null) {
            callback.onFinished(result);
        }
        notifyStatus();
    }

    private void runTcpChecks() {
        phase = PHASE_TCP;
        if (candidates.isEmpty()) {
            done(new Result(null, -1, 0, 0, false));
            return;
        }
        final ArrayList<Candidate> list = new ArrayList<>(candidates);
        new Thread(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(TCP_PARALLEL, list.size()));
            for (Candidate candidate : list) {
                executor.execute(() -> {
                    if (cancelled) {
                        return;
                    }
                    long startTime = SystemClock.elapsedRealtime();
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(candidate.settings.getAddress(), candidate.settings.getPort()), (int) TCP_TIMEOUT_MS);
                        candidate.ping = Math.max(1, SystemClock.elapsedRealtime() - startTime);
                    } catch (Exception ignore) {
                    }
                    final boolean ok = candidate.ping > 0;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!cancelled && callback != null) {
                            callback.onTcpChecked(candidate.index, ok);
                        }
                    });
                });
            }
            executor.shutdown();
            try {
                executor.awaitTermination(TCP_TIMEOUT_MS * (list.size() / TCP_PARALLEL + 2), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
            AndroidUtilities.runOnUIThread(this::onTcpDone);
        }, "telezhka-rescue").start();
    }

    private void onTcpDone() {
        if (cancelled) {
            return;
        }
        ArrayList<Candidate> reachable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.ping > 0) {
                reachable.add(candidate);
            }
        }
        if (!reachable.isEmpty()) {
            // Someone answered, so the network works: a closed port is a proven failure
            for (Candidate candidate : candidates) {
                if (candidate.ping <= 0 && savedBefore.contains(candidate.settings)) {
                    addFailure(candidate.settings);
                }
            }
        }
        if (callback != null) {
            callback.onTcpDone(reachable.size(), candidates.size());
        }
        if (reachable.isEmpty()) {
            done(new Result(null, -1, 0, 0, false));
            return;
        }
        // Fewer proven failures first, then the fresh list, then the fastest
        Collections.sort(reachable, (a, b) -> {
            int fa = getFailures(a.settings), fb = getFailures(b.settings);
            if (fa != fb) {
                return Integer.compare(fa, fb);
            }
            if (a.preferred != b.preferred) {
                return a.preferred ? -1 : 1;
            }
            return Long.compare(a.ping, b.ping);
        });
        phase = PHASE_TRYING;
        attempts = Math.min(reachable.size(), tries);
        tryConnect(reachable, 0);
    }

    private void tryConnect(ArrayList<Candidate> reachable, int index) {
        if (cancelled) {
            return;
        }
        if (index >= attempts) {
            restore();
            SharedConfig.saveProxyList();
            done(new Result(null, -1, 0, 0, false));
            return;
        }
        Candidate candidate = reachable.get(index);
        attempt = index + 1;
        if (callback != null) {
            callback.onTrying(candidate.index, attempt, attempts);
        }
        notifyStatus();
        enable(SharedConfig.addProxy(new SharedConfig.ProxyInfo(candidate.settings)));
        final long startTime = SystemClock.elapsedRealtime();
        pendingWait = new Runnable() {
            @Override
            public void run() {
                pendingWait = null;
                if (cancelled) {
                    return;
                }
                if (isConnected()) {
                    onConnected(reachable, index);
                } else if (SystemClock.elapsedRealtime() - startTime < CONNECT_WAIT_MS) {
                    pendingWait = this;
                    AndroidUtilities.runOnUIThread(this, CONNECT_POLL_MS);
                } else if (isWaitingForNetwork()) {
                    // The internet itself is gone: not the proxy's fault, stop without marking anything
                    restore();
                    SharedConfig.saveProxyList();
                    done(new Result(null, -1, 0, 0, false));
                } else {
                    // TCP worked but Telegram did not: proof that this proxy does not help
                    if (callback != null) {
                        callback.onTryFailed(candidate.index);
                    }
                    if (savedBefore.contains(candidate.settings)) {
                        addFailure(candidate.settings);
                    } else {
                        // Added only for this try: take it back out
                        SharedConfig.ProxyInfo info = findProxy(candidate.settings);
                        if (info != null) {
                            SharedConfig.proxyList.remove(info);
                        }
                    }
                    tryConnect(reachable, index + 1);
                }
            }
        };
        AndroidUtilities.runOnUIThread(pendingWait, CONNECT_POLL_MS);
    }

    private void onConnected(ArrayList<Candidate> reachable, int index) {
        Candidate winner = reachable.get(index);
        clearFailures(winner.settings);
        SharedConfig.ProxyInfo current = findProxy(winner.settings);
        if (current != null) {
            current.available = true;
            current.ping = winner.ping;
            current.availableCheckTime = SystemClock.elapsedRealtime();
        }
        // Spares here are only known to accept TCP; the autopilot re-checks them once connected.
        ArrayList<Candidate> rest = new ArrayList<>(reachable.subList(index + 1, reachable.size()));
        for (int i = rest.size() - 1; i >= 0; i--) {
            SharedConfig.addProxy(new SharedConfig.ProxyInfo(rest.get(i).settings));
        }
        removeProvenDead();
        SharedConfig.saveProxyList();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        done(new Result(winner.settings, winner.index, winner.ping, rest.size(), false));
    }

    // Back to how it was before the search: the old proxy, or no proxy
    private void restore() {
        if (wasEnabled && wasCurrent != null && SharedConfig.proxyList.contains(wasCurrent)) {
            enable(wasCurrent);
        } else {
            disable();
        }
    }

    private static boolean isConnected() {
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private static boolean isWaitingForNetwork() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork;
    }

    public static SharedConfig.ProxyInfo findProxy(ProxySettings settings) {
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.settings.equals(settings)) {
                return info;
            }
        }
        return null;
    }

    public static void enable(SharedConfig.ProxyInfo info) {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        TelezhkaPingMonitor.refresh();
    }

    public static void disable() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.commit();
        ConnectionsManager.setProxySettings(false, null);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}

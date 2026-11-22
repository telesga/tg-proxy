/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Keeps proxies ready while the app is closed, so the user opens it already connected:
 * - every ~30 minutes, when there is internet, Android wakes the app: it refreshes the list and checks
 *   the saved proxies (TelezhkaProxyAutopilot), or searches for a working one if there is no connection;
 * - when a push arrives (a new message) and Telegram cannot connect, the same search starts right away,
 *   as a job, so Android lets it finish instead of killing the app after the push is handled.
 * Android may delay jobs in Doze, and some phones (Xiaomi, Huawei...) skip them unless the app is allowed
 * to work in the background: the "Подключение" screen offers that setting.
 */
public class TelezhkaProxyJob extends JobService {

    private static final int JOB_PERIODIC = 0x7E1E2A01;
    private static final int JOB_NOW = 0x7E1E2A02;

    private static final long PERIOD_MS = 30 * 60 * 1000L;
    // A push may come while Telegram is still connecting: give it this long before searching
    private static final long PUSH_DELAY_MS = 15 * 1000L;
    // At most one push-started job this often
    private static final long PUSH_THROTTLE_MS = 3 * 60 * 1000L;
    // Android stops a job after about 10 minutes
    private static final long MAX_RUN_MS = 9 * 60 * 1000L;
    private static final long POLL_MS = 2000;
    // Wait for Telegram to connect by itself before deciding between a check and a search
    private static final long CONNECT_GRACE_MS = 10 * 1000L;
    // Keep Telegram's network awake while the job runs (it pauses itself in the background)
    private static final long RESUME_EVERY_MS = 20 * 1000L;

    private static long lastPushJob;

    private JobParameters current;
    private long startedAt;
    private long lastResume;
    private boolean started;
    private final Runnable poll = this::onPoll;

    public static void schedulePeriodic() {
        try {
            JobScheduler scheduler = scheduler();
            if (scheduler == null || isPending(scheduler, JOB_PERIODIC)) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(ApplicationLoader.applicationContext, TelezhkaProxyJob.class))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancelAll() {
        try {
            JobScheduler scheduler = scheduler();
            if (scheduler != null) {
                scheduler.cancel(JOB_PERIODIC);
                scheduler.cancel(JOB_NOW);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // From PushListenerController, on the UI thread: a message came, make sure it can be downloaded
    public static void onPush() {
        if (!TelezhkaProxyAutopilot.isEnabled() || !ApplicationLoader.mainInterfacePaused) {
            return;  // off, or the app is open and the autopilot works there anyway
        }
        long now = SystemClock.elapsedRealtime();
        if (lastPushJob != 0 && now - lastPushJob < PUSH_THROTTLE_MS) {
            return;
        }
        lastPushJob = now;
        try {
            JobScheduler scheduler = scheduler();
            if (scheduler == null || isPending(scheduler, JOB_NOW)) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_NOW, new ComponentName(ApplicationLoader.applicationContext, TelezhkaProxyJob.class))
                    .setMinimumLatency(PUSH_DELAY_MS)
                    .setOverrideDeadline(PUSH_DELAY_MS * 4)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static JobScheduler scheduler() {
        return (JobScheduler) ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static boolean isPending(JobScheduler scheduler, int id) {
        for (JobInfo info : scheduler.getAllPendingJobs()) {
            if (info.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnected() {
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            if (!TelezhkaProxyAutopilot.isEnabled()) {
                jobFinished(params, false);
                return;
            }
            current = params;
            startedAt = SystemClock.elapsedRealtime();
            started = false;
            resumeNetwork();
            AndroidUtilities.runOnUIThread(poll, POLL_MS);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Android ends it early (constraints lost, time is up): the autopilot keeps whatever it found
        AndroidUtilities.runOnUIThread(() -> {
            AndroidUtilities.cancelRunOnUIThread(poll);
            current = null;
        });
        return false;
    }

    private void resumeNetwork() {
        lastResume = SystemClock.elapsedRealtime();
        if (ApplicationLoader.mainInterfacePaused) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).resumeNetworkMaybe();
        }
    }

    private void onPoll() {
        if (current == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastResume > RESUME_EVERY_MS) {
            resumeNetwork();
        }
        if (!started) {
            // Let Telegram connect by itself first; then check and refresh, or search if it could not
            if (TelezhkaProxyAutopilot.isBusy()) {
                started = true;  // the app or a push is already running a check: just wait for it
            } else if (isConnected() || now - startedAt > CONNECT_GRACE_MS) {
                TelezhkaProxyAutopilot.runFromBackground();
                started = true;
            }
        } else if (!TelezhkaProxyAutopilot.isBusy() || now - startedAt > MAX_RUN_MS) {
            JobParameters params = current;
            current = null;
            jobFinished(params, false);
            return;
        }
        AndroidUtilities.runOnUIThread(poll, POLL_MS);
    }
}

/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * "Всегда на связи": a foreground service with a quiet permanent notification, so Android does not
 * unload the app and the autopilot keeps checking proxies every few minutes while the app is closed.
 * Off by default (it costs some battery); switched on the "Подключение" screen.
 * Telegram's own keep-alive service is a plain background service that Android 8+ stops within
 * a minute, so it does not help here.
 */
public class TelezhkaKeepAliveService extends Service {

    public static final String PREF_ENABLED = "telezhka_keep_alive";
    private static final String CHANNEL_ID = "telezhka_keep_alive";
    private static final int NOTIFICATION_ID = 0x7E1E2A10;

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            startIfEnabled();
        } else {
            ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, TelezhkaKeepAliveService.class));
        }
    }

    // On app start, after boot and from the switch. Android 12+ refuses to start it from the background
    // (e.g. when a push woke the app): then it starts the next time the app is opened.
    public static void startIfEnabled() {
        if (!isEnabled()) {
            return;
        }
        try {
            Intent intent = new Intent(ApplicationLoader.applicationContext, TelezhkaKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ApplicationLoader.applicationContext.startForegroundService(intent);
            } else {
                ApplicationLoader.applicationContext.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        LocaleController.getString(R.string.TelezhkaKeepAliveChannel), NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                manager.createNotificationChannel(channel);
            }
        }
        Intent open = new Intent(this, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.TelezhkaKeepAliveNotificationTitle))
                .setContentText(LocaleController.getString(R.string.TelezhkaKeepAliveNotificationText))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.phonestats.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/**
 * Keeps the commute log alive in the background and shows a notification with live status
 * and a Stop button. Started only when you tap Start; ends when the log stops.
 */
public class LogService extends Service implements CommuteLogger.Listener {

    static final String CHANNEL = "commute_log";
    static final int NOTIF_ID = 42;
    static final String ACTION_STOP = "com.phonestats.app.STOP_LOG";

    private CommuteLogger logger;
    private NotificationManager nm;
    private boolean inForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        logger = CommuteLogger.get(this);
        nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Commute log", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while the commute log is running");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!inForeground) {
            startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            inForeground = true;
            logger.addListener(this);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            logger.stop("Stopped from the notification");
        }
        if (!logger.isRunning()) finish();
        return START_NOT_STICKY;
    }

    @Override
    public void onLogUpdate() {
        if (!logger.isRunning()) {
            finish();
            return;
        }
        nm.notify(NOTIF_ID, build());
    }

    private void finish() {
        logger.removeListener(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        logger.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, LogService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_log)
                .setContentTitle("Commute log · " + logger.shortStatus())
                .setContentText(logger.summaryLine())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop logging", stopPi).build())
                .build();
    }
}

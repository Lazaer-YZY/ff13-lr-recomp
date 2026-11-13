package com.lrrecomp.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the app's process alive while an ISO import runs (the copy is ~7 GB and takes minutes):
 * a foreground service with a progress notification. It only watches {@link IsoImport#current()},
 * which does the work on its own thread, and stops itself when the import has finished.
 * Without the notification permission (Android 13+) the notification is hidden but the
 * service still runs.
 */
public final class IsoImportService extends Service {
    private static final String CHANNEL = "iso_import";
    private static final int ID = 13;
    private final Handler handler = new Handler(Looper.getMainLooper());

    static void start(Context context) {
        Intent intent = new Intent(context, IsoImportService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game import", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = build(IsoImport.current());
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        handler.removeCallbacksAndMessages(null);
        handler.post(this::tick);
        return START_NOT_STICKY;
    }

    private void tick() {
        IsoImport job = IsoImport.current();
        if (job == null || job.finished()) {
            stopForeground(true);
            stopSelf();
            return;
        }
        getSystemService(NotificationManager.class).notify(ID, build(job));
        handler.postDelayed(this::tick, 1000);
    }

    private Notification build(IsoImport job) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int pct = job == null || job.totalBytes <= 0 ? 0 : (int) (100 * job.doneBytes / job.totalBytes);
        Intent open = new Intent(this, GameFolderActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Importing the game")
            .setContentText(job == null ? "" : IsoImport.gigabytes(job.doneBytes) + " of " + IsoImport.gigabytes(job.totalBytes))
            .setProgress(100, pct, job == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

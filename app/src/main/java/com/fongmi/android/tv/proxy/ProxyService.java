package com.fongmi.android.tv.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;

import java.io.IOException;

public class ProxyService extends android.net.VpnService {

    private static final String CHANNEL = "proxy";
    private static final int NOTIFY_ID = 991;
    public static final String ACTION_START = "proxy_start";
    public static final String ACTION_STOP = "proxy_stop";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor fd;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, ProxyService.class).setAction(ACTION_START);
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context context) {
        try {
            context.startService(new Intent(context, ProxyService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = build(getString(R.string.proxy_notify_start), "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
        handler.post(idleTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            ProxyBox.get().stop();
            return START_NOT_STICKY;
        }
        ProxyBox.get().start(this, new ProxyPlatform(this));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleTask);
        closeFd();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        ProxyBox.get().stop();
        super.onRevoke();
    }

    public void setFd(ParcelFileDescriptor fd) {
        this.fd = fd;
    }

    public void closeFd() {
        try {
            if (fd != null) fd.close();
        } catch (IOException ignored) {
        }
        fd = null;
    }

    private final Runnable idleTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (ProxySetting.isIdle() && ProxyBox.get().isRunning() && ProxyControl.get().idleMillis() > 60000) {
                    ProxyBox.get().stop();
                    ProxySetting.putEnabled(false);
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 15000);
        }
    };

    public void notifyUser(String title, String body) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFY_ID, build(title, body));
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.proxy_title), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.proxy_title));
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification build(String title, String body) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pending = PendingIntent.getService(this, 0, new Intent(this, ProxyService.class).setAction(ACTION_STOP), flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(title == null || title.isEmpty() ? getString(R.string.proxy_title) : title);
        builder.setContentText(body == null ? "" : body);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.addAction(0, getString(R.string.proxy_stop), pending);
        return builder.build();
    }
}

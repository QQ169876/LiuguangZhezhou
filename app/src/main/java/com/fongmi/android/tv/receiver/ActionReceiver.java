package com.fongmi.android.tv.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.service.PlaybackService;

public class ActionReceiver extends BroadcastReceiver {

    public static PendingIntent getPendingIntent(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0, new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // 老版本（5.6.96）用包安装器会话装包时挂的回执；现在已不再发起会话，留着只为兜住历史遗留的广播
        if (intent.getAction() != null && intent.getAction().endsWith(".install.result")) return;
        if (!PlaybackService.isRunning() || intent.getAction() == null) return;
        Intent serviceIntent = new Intent(context, PlaybackService.class);
        serviceIntent.setAction(intent.getAction());
        context.startService(serviceIntent);
    }
}

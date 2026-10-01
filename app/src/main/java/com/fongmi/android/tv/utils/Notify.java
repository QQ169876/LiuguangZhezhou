package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    /**
     * Fragment 版：异步回调、二级对话框的点击都常常落在 Fragment 已经分离之后，
     * 这时候 requireActivity() 自己就会抛异常把 App 带崩，所以这里先看还挂不挂得上。
     */
    public static void progress(Fragment fragment) {
        if (fragment == null || !fragment.isAdded()) return;
        FragmentActivity activity = fragment.getActivity();
        if (activity == null) return;
        progress(activity);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    /**
     * 转圈提示框：异步回调常常落在页面已经切走之后，
     * 这时候再 show 会抛 BadTokenException 直接崩，所以状态不对就不弹。
     */
    private void create(Context context) {
        if (!alive(context)) return;
        try {
            ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
            mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
            if (mDialog.getWindow() != null) mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            mDialog.show();
        } catch (Exception ignored) {
            mDialog = null;
        }
    }

    private boolean alive(Context context) {
        if (context == null) return false;
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return false;
        }
        return true;
    }

    private void makeText(String text) {
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.show();
    }
}

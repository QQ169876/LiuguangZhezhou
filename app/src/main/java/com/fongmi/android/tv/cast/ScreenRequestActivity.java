package com.fongmi.android.tv.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;

/**
 * 专门用来「替人跑一趟授权」的透明页。
 *
 * 录屏必须用 {@code startActivityForResult} 去拿结果，而结果只会回到发起的那个 Activity。
 * 要是直接在播放页 / 首页发这个请求，两边都得为它各自重写一遍 {@code onActivityResult}，
 * 以后再加一个入口又得再来一回。
 *
 * 所以这里垫一个看不见的中间页：它自己去要授权、拿到结果就把服务拉起来、然后立刻消失。
 * 调用方只需要 startActivity 一下，别的都不用管。
 *
 * ⚠️ 拉服务的时机有讲究，别在 {@code onActivityResult} 里当场 {@code startForegroundService}：
 * 那时系统那张授权弹窗还盖在上面，本 Activity 是 paused —— 从后台起前台服务，
 * 系统会不客气地判违规（小米机上就是这么炸的：ForegroundServiceDidNotStartInTimeException，整个 App 被带走）。
 * 正确做法是等 {@code onResume}——弹窗关掉、自己重新站到前台之后再动手。
 *
 * 另外声音那边也要先要一个录音权限（安卓 10 起「抓取别的应用放出来的声音」走的就是录音权限）。
 * 这个权限是**可选**的：用户不给，屏幕广播就退化成只有画面，不影响其余功能。
 */
public final class ScreenRequestActivity extends Activity {

    private static final String TAG = "ScreenRequest";
    private static final int REQUEST_AUDIO = 9520;
    private static final int REQUEST_CAST = 9521;

    private Intent grant;
    private int code;
    private boolean done;
    private final Runnable fallback = () -> {
        // 极少数机器上弹窗关闭后不会回调 onResume（或回调得特别晚），别让人干等着
        if (!done && grant != null) boot();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        request(manager);
    }

    private void request(MediaProjectionManager manager) {
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAST);
        } catch (Throwable e) {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO) return;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            finish();
            return;
        }
        request(manager); // 录音权限给不给都继续：给了就带声音，不给就只有画面
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 授权弹窗收起、本页重新可见：这才是「前台」，可以安心起前台服务了
        App.removeCallbacks(fallback);
        if (!done && grant != null) App.post(this::boot, 120L);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAST) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            DebugLog.d(TAG, "授权没给 resultCode=" + resultCode + " data=" + (data == null ? "空" : "有"));
            finish();
            return;
        }
        grant = data;
        code = resultCode;
        DebugLog.d(TAG, "拿到录屏授权，等本页重新可见再起服务");
        App.post(fallback, 2000L); // 万一没人来接，两秒后自己兜底，总好过卡在原地
    }

    private void boot() {
        done = true;
        Intent data = grant;
        grant = null;
        App.removeCallbacks(fallback);
        if (data == null) {
            finish();
            return;
        }
        DebugLog.d(TAG, "拉起屏幕广播服务");
        ScreenCastService.start(this, code, data);
        App.post(this::check, 1500L); // 一声不吭地失败最坑人：起没起来，过一眼就见分晓
        App.post(this::finishQuietly, 800L); // 让服务先站稳脚跟，本页再退场
    }

    private void check() {
        boolean alive = ScreenCastService.started();
        DebugLog.d(TAG, "起播复核 服务在否=" + alive + " 已在广播=" + ScreenCastService.running());
        if (!alive) Notify.show(R.string.cast_screen_fail_launch);
    }

    private void finishQuietly() {
        try {
            finish();
        } catch (Throwable ignored) {
        }
    }
}

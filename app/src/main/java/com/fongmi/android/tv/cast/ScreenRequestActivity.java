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
 * 另外声音那边也要先要一个录音权限（安卓 10 起「抓取别的应用放出来的声音」走的就是录音权限）。
 * 这个权限是**可选**的：用户不给，屏幕广播就退化成只有画面，不影响其余功能。
 */
public final class ScreenRequestActivity extends Activity {

    private static final int REQUEST_AUDIO = 9520;
    private static final int REQUEST_CAST = 9521;

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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAST && resultCode == RESULT_OK && data != null) {
            ScreenCastService.start(this, resultCode, data);
        }
        finish();
    }
}

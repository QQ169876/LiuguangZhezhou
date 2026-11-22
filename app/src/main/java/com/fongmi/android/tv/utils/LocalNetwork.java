package com.fongmi.android.tv.utils;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fongmi.android.tv.App;

/**
 * Android 17（API 37）本地网络权限适配。
 *
 * 从 Android 17 起，targetSdk 37+ 的应用默认被禁止访问局域网：TCP/UDP 单播/组播/广播全被网络栈拦掉，
 * 未授权时 TCP 连接表现为「超时」——这正是小米 24129PN74C（api37）系统更新后局域网推送全灭、
 * 而荣耀索尼正常、浏览器也能直连的原因（见 developer.android.com/privacy-and-security/local-network-permission）。
 *
 * 该权限属于 NEARBY_DEVICES 权限组，API 37 以下不存在、无需申请，一律视为已授权。
 */
public final class LocalNetwork {

    public static final int REQUEST_CODE = 3701;

    private LocalNetwork() {
    }

    /** 当前系统是否已授予本地网络访问权（API 37 以下恒为已授权） */
    public static boolean granted() {
        if (Build.VERSION.SDK_INT < 37) return true;
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED;
    }

    /** 弹系统权限框申请；调用方在 onRequestPermissionsResult 里判断 REQUEST_CODE 再继续 */
    public static void request(Fragment fragment) {
        fragment.requestPermissions(new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK}, REQUEST_CODE);
    }

    /** 解析一次权限回调：返回是否授予（非本权限请求返回 false） */
    public static boolean isGranted(int requestCode, int[] grantResults) {
        return requestCode == REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }
}

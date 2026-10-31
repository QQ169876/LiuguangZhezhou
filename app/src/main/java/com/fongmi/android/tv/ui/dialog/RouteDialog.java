package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterRouteBinding;
import com.fongmi.android.tv.databinding.DialogRouteBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.GhRoute;
import com.fongmi.android.tv.utils.Gitee;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SelfHost;
import com.fongmi.android.tv.utils.UpdateChannel;

import java.util.List;

/**
 * 更新通道：自动（码云 → 网盘 → GitHub）／码云／网盘／GitHub。
 *
 * 2026-11-01 改：以前这里摆了一整排 GitHub 公益加速代理和「自定义 SOCKS5」，
 * 那些东西现在全部收进 GhRoute 内部 —— GitHub 只是第三条兜底通道，
 * 默认直连、直连不通自动过代理，挑哪台代理这事用户判断不了，也不该让用户挑。
 */
public class RouteDialog {

    public interface Listener {
        void onRouteChanged();
    }

    private final FragmentActivity activity;
    private final Listener listener;
    private int taps;

    public static void show(FragmentActivity activity, Listener listener) {
        new RouteDialog(activity, listener).show();
    }

    private RouteDialog(FragmentActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private List<String> items() {
        return UpdateChannel.list();
    }

    private static String label(String channel) {
        if (UpdateChannel.AUTO.equals(channel)) return ResUtil.getString(R.string.route_auto);
        if (Gitee.is(channel)) return ResUtil.getString(R.string.route_gitee);
        if (SelfHost.is(channel)) return ResUtil.getString(R.string.route_self);
        if (Github.is(channel)) return ResUtil.getString(R.string.route_github);
        return channel;
    }

    private boolean checked(String channel) {
        return channel.equals(UpdateChannel.current());
    }

    private void show() {
        DialogRouteBinding binding = DialogRouteBinding.inflate(LayoutInflater.from(activity));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.setting_route).setView(binding.getRoot()).setNegativeButton(R.string.dialog_negative, null).create();
        render(binding.list);
        binding.ipv6.setChecked(Setting.isGlobalProxy());
        binding.ipv6.setOnClickListener(view -> onIPv6((android.widget.CheckBox) view));
        dialog.show();
    }

    /**
     * 那个标着「IPv6」的复选框跟 IPv6 协议一点关系没有 —— 它只是**全局 SOCKS5 代理**的开关：
     * 勾上 = App 里所有请求（点播、直播、图片、站源视频链接）都走填过的那台 SOCKS5，
     * 取消 = 各走各的原线路。按钮文字是故意拿来混淆视听的，别照着名字去动 DNS / 协议栈。
     * 默认关，要连点七下才真勾上，中间不给任何提示。
     */
    private void onIPv6(android.widget.CheckBox box) {
        if (!box.isChecked()) {
            taps = 0;
            apply(false);
            return;
        }
        if (++taps < 7) {
            box.setChecked(false);
            return;
        }
        taps = 0;
        apply(true);
    }

    private void apply(boolean globalProxy) {
        Setting.putGlobalProxy(globalProxy);
        GhRoute.applyGlobal();
    }

    private void render(LinearLayoutCompat container) {
        container.removeAllViews();
        for (String channel : items()) addRow(container, channel);
    }

    private void addRow(LinearLayoutCompat container, String channel) {
        AdapterRouteBinding row = AdapterRouteBinding.inflate(LayoutInflater.from(activity), container, false);
        row.text.setText(label(channel));
        row.check.setChecked(checked(channel));
        row.delete.setVisibility(View.GONE); // 三条通道都是内置的，没有可删的
        row.getRoot().setOnClickListener(view -> pick(channel));
        container.addView(row.getRoot());
    }

    private void pick(String channel) {
        Setting.putUpdateChannel(channel);
        changed();
    }

    private void changed() {
        if (Setting.isGlobalProxy()) GhRoute.applyGlobal();
        if (listener != null) listener.onRouteChanged();
    }

    /** 设置页那一行显示的当前通道 */
    public static String text() {
        return label(UpdateChannel.current());
    }
}

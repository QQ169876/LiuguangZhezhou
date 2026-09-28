package com.fongmi.android.tv.ui.dialog;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSocksBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.GhRoute;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新线路：自动（直连优先）／直连／公益加速代理／本地 SOCKS5
 */
public class RouteDialog {

    public interface Listener {
        void onRouteChanged();
    }

    private final FragmentActivity activity;
    private final Listener listener;

    public static void show(FragmentActivity activity, Listener listener) {
        new RouteDialog(activity, listener).show();
    }

    private RouteDialog(FragmentActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private List<String> items() {
        List<String> items = new ArrayList<>();
        items.add(GhRoute.AUTO);
        items.add("");
        items.addAll(GhRoute.accel());
        String socks = Setting.getSocks();
        if (GhRoute.validSocks(socks)) items.add(GhRoute.SOCKS5 + socks);
        items.add(GhRoute.CUSTOM);
        return items;
    }

    private String[] labels(List<String> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = label(items.get(i));
        return labels;
    }

    private String label(String route) {
        if (GhRoute.AUTO.equals(route)) return ResUtil.getString(R.string.route_auto);
        if (GhRoute.CUSTOM.equals(route)) return ResUtil.getString(R.string.route_custom);
        if (GhRoute.isDirect(route)) return ResUtil.getString(R.string.route_direct);
        return GhRoute.host(route);
    }

    private void show() {
        List<String> items = items();
        int checked = Math.max(0, Setting.isRouteAuto() ? items.indexOf(GhRoute.AUTO) : items.indexOf(String.valueOf(GhRoute.fixed())));
        new AlertDialog.Builder(activity).setTitle(R.string.setting_route).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(labels(items), checked, (dialog, which) -> {
            dialog.dismiss();
            pick(items.get(which));
        }).show();
    }

    private void pick(String route) {
        if (GhRoute.CUSTOM.equals(route)) {
            socks();
        } else if (GhRoute.AUTO.equals(route)) {
            Setting.putRouteAuto(true);
            Setting.putRoute("");
            changed();
        } else if (GhRoute.isSocks(route)) {
            Setting.putSocks(route.substring(GhRoute.SOCKS5.length()));
            Setting.putRouteAuto(false);
            changed();
        } else {
            Setting.putRoute(route);
            Setting.putRouteAuto(false);
            changed();
        }
    }

    /**
     * SOCKS5：地址 / 端口 / 用户名 / 密码分开填，也可以扫码让手机网页代填
     */
    private void socks() {
        DialogSocksBinding binding = DialogSocksBinding.inflate(LayoutInflater.from(activity));
        String socks = Setting.getSocks();
        if (GhRoute.validSocks(socks)) {
            binding.host.setText(GhRoute.host(socks));
            binding.port.setText(String.valueOf(GhRoute.port(socks)));
            binding.user.setText(GhRoute.user(socks));
            binding.pass.setText(GhRoute.pass(socks));
        }
        binding.scan.setOnClickListener(view -> onScan(binding));
        binding.clear.setOnClickListener(view -> onClear(binding));
        new AlertDialog.Builder(activity).setTitle(R.string.route_custom).setView(binding.getRoot()).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(android.R.string.ok, (dialog, which) -> onSave(binding)).show();
    }

    private void onScan(DialogSocksBinding binding) {
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(8), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
        Notify.show(R.string.route_lan_tip);
    }

    private void onClear(DialogSocksBinding binding) {
        binding.host.setText("");
        binding.port.setText("");
        binding.user.setText("");
        binding.pass.setText("");
        binding.image.setVisibility(View.GONE);
        Setting.putSocks("");
        Setting.putRoute("");
        Setting.putRouteAuto(true);
        Notify.show(R.string.route_auto);
        changed();
    }

    private void onSave(DialogSocksBinding binding) {
        String host = binding.host.getText().toString().trim();
        String port = binding.port.getText().toString().trim();
        String user = binding.user.getText().toString().trim();
        String pass = binding.pass.getText().toString().trim();
        if (host.contains("://") || host.contains("@")) {
            String full = GhRoute.parse(host);
            user = GhRoute.user(full);
            pass = GhRoute.pass(full);
            String address = GhRoute.address(full);
            int index = address.lastIndexOf(':');
            if (index > 0) {
                host = address.substring(0, index);
                port = address.substring(index + 1);
            } else {
                host = address;
            }
        }
        String socks = GhRoute.socks(host, port, user, pass);
        if (!GhRoute.validSocks(socks)) {
            Notify.show(R.string.route_bad);
            return;
        }
        Setting.putSocks(socks);
        Setting.putRoute("");
        Setting.putRouteAuto(false);
        Notify.show(R.string.route_saved);
        changed();
    }

    private void changed() {
        if (listener != null) listener.onRouteChanged();
    }

    public static String text() {
        if (Setting.isRouteAuto()) return ResUtil.getString(R.string.route_auto);
        String route = GhRoute.fixed();
        if (route == null || GhRoute.isDirect(route)) return ResUtil.getString(R.string.route_direct);
        return GhRoute.host(route);
    }
}

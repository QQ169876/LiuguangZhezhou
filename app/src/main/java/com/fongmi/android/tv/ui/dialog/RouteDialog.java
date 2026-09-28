package com.fongmi.android.tv.ui.dialog;

import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.GhRoute;
import com.fongmi.android.tv.utils.Notify;
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

    private void socks() {
        EditText input = new EditText(activity);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        input.setSingleLine(true);
        input.setHint(R.string.route_hint);
        input.setText(Setting.getSocks());
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity).setTitle(R.string.route_custom).setView(box).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                Setting.putSocks("");
                Setting.putRoute("");
                Setting.putRouteAuto(true);
            } else if (GhRoute.validSocks(value)) {
                Setting.putSocks(value);
                Setting.putRouteAuto(false);
            } else {
                Notify.show(R.string.route_bad);
                return;
            }
            changed();
        }).show();
    }

    private void changed() {
        if (listener != null) listener.onRouteChanged();
    }

    public static String text() {
        if (Setting.isRouteAuto()) return ResUtil.getString(R.string.route_auto);
        String route = GhRoute.fixed();
        if (GhRoute.isDirect(route)) return ResUtil.getString(R.string.route_direct);
        return GhRoute.host(route);
    }
}

package com.fongmi.android.tv.ui.dialog;

import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.widget.EditText;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cast.CastNet;
import com.fongmi.android.tv.databinding.DialogIpInputBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Set;

/**
 * 手动指定目标 IP：4 段输入框，参照 Windows 指定 DNS 服务器的样子。
 *
 * 分 4 段是为了 TV 端用遥控器输入方便——前三段按本机局域网 IP 预设好，
 * 一般只要改最后一段；每段输满 3 位自动跳到下一段，不用手打「.」。
 * 手机 / TV 两端共用。
 */
public class IpInputDialog {

    public interface Callback {
        /** 返回拼好的 IP（四段，如 192.168.1.194），无效输入不回调 */
        void onInput(String ip);
    }

    public static void show(FragmentActivity activity, Callback callback) {
        DialogIpInputBinding binding = DialogIpInputBinding.inflate(LayoutInflater.from(activity));
        EditText[] fields = {binding.ip1, binding.ip2, binding.ip3, binding.ip4};
        preset(fields);
        wire(fields);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.lan_input_ip)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.lan_input_connect, (dialog, which) -> {
                    String ip = build(fields);
                    if (ip == null) return;
                    callback.onInput(ip);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 前三段取本机局域网 IP（优先 192.168 / 10 / 172 私网），第四段留空等用户输入 */
    private static void preset(EditText[] fields) {
        String lan = pickLanIp();
        if (lan == null) return;
        String[] parts = lan.split("\\.");
        if (parts.length != 4) return;
        for (int i = 0; i < 3; i++) fields[i].setText(parts[i]);
        fields[3].setText("");
    }

    private static String pickLanIp() {
        Set<String> ips = CastNet.selfIps();
        String fallback = null;
        for (String ip : ips) {
            if (ip == null || ip.startsWith("169.254.") || ip.equals("127.0.0.1")) continue;
            if (fallback == null) fallback = ip;
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip;
        }
        return fallback;
    }

    /** 输满 3 位自动跳下一段；删空后跳回上一段，方便遥控器操作 */
    private static void wire(EditText[] fields) {
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            fields[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 3 && index < fields.length - 1) fields[index + 1].requestFocus();
                    else if (s.length() == 0 && index > 0) fields[index - 1].requestFocus();
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                }
            });
        }
    }

    /** 四段都填了且都在 0~255 才拼成 IP，否则返回 null */
    private static String build(EditText[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            String part = fields[i].getText().toString().trim();
            if (part.isEmpty()) return null;
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255) return null;
            if (i > 0) sb.append('.');
            sb.append(value);
        }
        return sb.toString();
    }
}

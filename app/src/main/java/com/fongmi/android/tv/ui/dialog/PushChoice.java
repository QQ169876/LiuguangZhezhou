package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Push;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 推送内容勾选框：首项是「全选 / 全不选」，下面是可多选的推送项。
 * 手机端用触屏点，TV 端用遥控器方向键 + 确定键勾选，操作方式一致。
 */
public class PushChoice {

    public interface Sender {
        void send(List<String> keys);
    }

    public static void show(FragmentActivity activity, String title, boolean tv, Sender sender) {
        List<String> keys = Push.keys(tv);
        String[] items = new String[keys.size() + 1];
        boolean[] checked = new boolean[keys.size() + 1];
        items[0] = activity.getString(R.string.push_select_all);
        for (int i = 0; i < keys.size(); i++) items[i + 1] = activity.getString(Push.label(keys.get(i)));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(title).setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
            checked[which] = isChecked;
            if (which != 0) return;
            AlertDialog alert = (AlertDialog) d;
            for (int i = 1; i < items.length; i++) {
                checked[i] = isChecked;
                alert.getListView().setItemChecked(i, isChecked);
            }
        }).setPositiveButton(R.string.push_send, (d, which) -> {
            List<String> result = new ArrayList<>();
            for (int i = 1; i < items.length; i++) if (checked[i]) result.add(keys.get(i - 1));
            if (result.isEmpty()) Notify.show(R.string.push_nothing);
            else sender.send(result);
        }).setNegativeButton(R.string.dialog_negative, null).create();
        dialog.show();
    }
}

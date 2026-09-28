package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSyncDirectionBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 同步地址或账号改动后第一次做双向同步时，先问清楚以哪边为准，
 * 免得两个人的历史、收藏在没打招呼的情况下互相覆盖。
 */
public class SyncDirectionDialog {

    public static final int CLOUD = 0;
    public static final int LOCAL = 1;
    public static final int MERGE = 2;

    public interface Listener {
        void onDirection(int direction);
    }

    public static void show(FragmentActivity activity, Listener listener) {
        DialogSyncDirectionBinding binding = DialogSyncDirectionBinding.inflate(LayoutInflater.from(activity));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sync_direction_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
        binding.cloud.setOnClickListener(view -> {
            dialog.dismiss();
            listener.onDirection(CLOUD);
        });
        binding.local.setOnClickListener(view -> {
            dialog.dismiss();
            listener.onDirection(LOCAL);
        });
        binding.merge.setOnClickListener(view -> {
            dialog.dismiss();
            listener.onDirection(MERGE);
        });
        binding.cloud.post(binding.cloud::requestFocus);
    }
}

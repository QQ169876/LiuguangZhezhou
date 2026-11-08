package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogBufferBinding;
import com.fongmi.android.tv.impl.BufferListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class BufferDialog extends BaseAlertDialog {

    private DialogBufferBinding binding;

    public static void show(FragmentActivity activity) {
        new BufferDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogBufferBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        SliderUtil.setValue(binding.slider, PlayerSetting.getBufferRaw());
    }

    @Override
    protected void initEvent() {
        binding.slider.addOnChangeListener((slider, value, fromUser) -> {
            int times = Math.round(SliderUtil.snap(slider, value));
            updateTitle(times);
            if (fromUser) ((BufferListener) requireActivity()).setBuffer(times);
        });
        binding.slider.setOnKeyListener((view, keyCode, event) -> {
            boolean enter = KeyUtil.isEnterKey(event);
            if (enter) dismiss();
            return enter;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        updateTitle(Math.round(binding.slider.getValue()));
    }

    /** 0 = 自动：卡顿两次后仅当前视频临时加大缓冲，用户设的值不动 */
    private void updateTitle(int times) {
        if (getDialog() == null) return;
        String value = times == 0 ? getString(com.fongmi.android.tv.R.string.player_buffer_auto) : String.valueOf(times);
        getDialog().setTitle(getString(com.fongmi.android.tv.R.string.player_buffer) + "：" + value);
    }
}

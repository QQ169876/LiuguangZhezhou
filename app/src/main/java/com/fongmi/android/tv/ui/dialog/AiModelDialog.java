package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiModel;
import com.fongmi.android.tv.databinding.DialogAiModelBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.ui.adapter.AiModelAdapter;
import com.fongmi.android.tv.utils.AiCode;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 大模型管理（马先生 2026-11-03 定的那一页）：
 *   - 自建的在前，**顺序就是优先级**，可以手动上下挪；内置两条在后，只给启停；
 *   - 自建的能改能删，内置的改不了也删不掉（地址/Key 打散在包里，也不跟着同步）；
 *   - 两种录入：手动填四项，或**扫码**——TV 上出局域网二维码用手机网页录入，
 *     手机版直接开相机扫别的设备的码；也能把自己的配置生成二维码给别人扫。
 * 一条能用的都没有时，智能识别那头就自动走本地，不报错。
 */
public class AiModelDialog extends BaseAlertDialog implements AiModelAdapter.OnActionListener {

    private ActivityResultLauncher<Intent> launcher;
    private DialogAiModelBinding binding;
    private AiModelAdapter adapter;

    @Override
    public void onCreate(@Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (data == null) return;
            if (AiCode.decode(data.getStringExtra("address"))) {
                Notify.show(R.string.ai_model_saved);
                reload();
            }
        });
    }

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        new AiModelDialog().show(activity.getSupportFragmentManager(), "ai_model");
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAiModelBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        getBinding(); // 基类是先建对话框再 initView，binding 得在这儿就备好，不然 setView 拿到的是 null
        return builder().setTitle(R.string.setting_ai_model).setView(binding.getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        // 不是自己人的，说明里别提内置那两条（内置是内部福利，外面的人压根不该看见）
        binding.tip.setText(AiSetting.isInsider() ? R.string.ai_model_tip : R.string.ai_model_tip_user);
        binding.recycler.setLayoutManager(new LinearLayoutManager(getActivity()));
        binding.recycler.setAdapter(adapter = new AiModelAdapter(this));
        reload();
    }

    @Override
    protected void initEvent() {
        binding.add.setOnClickListener(view -> AiModelEditDialog.show(getActivity(), null, this::reload));
        binding.scan.setOnClickListener(view -> onScan());
        binding.qrcode.setOnClickListener(view -> onQrCode());
    }

    private void reload() {
        adapter.addAll(AiSetting.all());
        binding.empty.setVisibility(adapter.getItemCount() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /**
     * 扫码录入：有相机的（手机版）直接扫别的设备的配置码；
     * 没相机的（电视/盒子）起本地服务出二维码，用手机扫了在网页里填。
     */
    private void onScan() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        if (hasScanner()) {
            Intent intent = new Intent();
            intent.setClassName(activity, "com.fongmi.android.tv.ui.activity.ScanActivity");
            launcher.launch(intent);
            return;
        }
        Server.get().start();
        showQr(Server.get().getAddress(10)); // 第 10 页就是网页里的大模型录入页
        Notify.show(R.string.ai_model_scan_tip);
    }

    /** 把自己的配置生成二维码：别的设备扫一下就录进去了 */
    private void onQrCode() {
        String text = AiCode.encode();
        if (text.isEmpty()) {
            Notify.show(R.string.ai_model_empty);
            return;
        }
        showQr(text);
    }

    private void showQr(String text) {
        Bitmap bitmap = QrHelper.encode(text, 640);
        if (bitmap == null) return;
        ImageView image = new ImageView(requireActivity());
        image.setAdjustViewBounds(true);
        image.setImageBitmap(bitmap);
        new MaterialAlertDialogBuilder(requireActivity()).setView(image).setNegativeButton(R.string.dialog_negative, null).show();
    }

    /** 这个包装没装扫码页（TV 端就是没有） */
    private static boolean hasScanner() {
        try {
            Class.forName("com.fongmi.android.tv.ui.activity.ScanActivity");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void onToggle(AiModel item) {
        if (item.isBuiltin()) AiSetting.putBuiltinOn(item.getId(), !item.isEnabled());
        else {
            item.setEnabled(!item.isEnabled());
            AiSetting.save(item);
        }
        reload();
    }

    @Override
    public void onEdit(AiModel item) {
        AiModelEditDialog.show(getActivity(), item, this::reload);
    }

    @Override
    public void onMove(AiModel item, boolean up) {
        AiSetting.move(item.getId(), up);
        reload();
    }

    @Override
    public void onDelete(AiModel item) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.ai_model_del)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.ai_model_delete, (dialog, which) -> {
                    AiSetting.remove(item.getId());
                    reload();
                }).show();
    }
}

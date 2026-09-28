package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.VpnService;
import android.text.format.DateFormat;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxyBinding;
import com.fongmi.android.tv.proxy.ProxyBox;
import com.fongmi.android.tv.proxy.ProxyControl;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxyService;
import com.fongmi.android.tv.proxy.ProxySetting;
import com.fongmi.android.tv.proxy.ProxySub;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ProxyDialog extends BaseAlertDialog {

    private DialogProxyBinding binding;

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK) start();
        else Notify.show(R.string.proxy_permission);
    });

    public static ProxyDialog create() {
        return new ProxyDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProxyBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.proxy_title).setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setWidth(0.5f);
        binding.sub.setText(ProxySetting.getSub());
        binding.enable.setChecked(ProxySetting.isEnabled() && ProxyBox.get().isRunning());
        binding.idle.setChecked(ProxySetting.isIdle());
        setState();
    }

    @Override
    protected void initEvent() {
        binding.update.setOnClickListener(this::onUpdate);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.test.setOnClickListener(this::onTest);
        binding.nodes.setOnClickListener(this::onNodes);
        binding.add.setOnClickListener(this::onAdd);
        binding.enable.setOnCheckedChangeListener((button, checked) -> onEnable(checked));
        binding.idle.setOnCheckedChangeListener((button, checked) -> ProxySetting.putIdle(checked));
    }

    private void save() {
        ProxySetting.putSub(binding.sub.getText().toString().trim());
    }

    private void setState() {
        List<ProxyNode> nodes = ProxySetting.getNodes();
        long update = ProxySetting.getUpdate();
        String time = update == 0 ? "" : DateFormat.format("MM-dd HH:mm", new Date(update)).toString();
        String status = ProxyBox.get().isRunning() ? getString(R.string.proxy_on) : getString(R.string.proxy_off);
        binding.state.setText(getString(R.string.proxy_state, status) + "  " + nodes.size() + "  " + time);
    }

    private void onUpdate(View view) {
        save();
        Notify.progress(requireActivity());
        ProxySub.update((count, error) -> {
            Notify.dismiss();
            Notify.show(error == null ? getString(R.string.proxy_updated, count) : getString(R.string.proxy_update_fail, error));
            setState();
            if (error == null && ProxyBox.get().isRunning()) ProxyBox.get().reload();
        });
    }

    private void onQrCode(View view) {
        save();
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(8), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        if (!ProxyBox.get().isRunning()) {
            Notify.show(R.string.proxy_off);
            return;
        }
        ProxyControl.get().urlTest();
        Notify.show(R.string.proxy_test);
    }

    private void onEnable(boolean enable) {
        save();
        ProxySetting.putEnabled(enable);
        if (enable) {
            Intent intent = VpnService.prepare(requireActivity());
            if (intent != null) launcher.launch(intent);
            else start();
        } else {
            ProxyService.stop(requireActivity());
            setState();
        }
    }

    private void start() {
        if (ProxySetting.getNodes().isEmpty()) {
            Notify.show(R.string.proxy_empty);
            binding.enable.setChecked(false);
            ProxySetting.putEnabled(false);
            setState();
            return;
        }
        ProxyService.start(requireActivity());
        setState();
    }

    private void onNodes(View view) {
        List<ProxyNode> nodes = ProxyControl.get().getNodes();
        if (nodes.isEmpty()) {
            Notify.show(R.string.proxy_empty);
            return;
        }
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.proxy_auto));
        for (ProxyNode node : nodes) {
            String text = node.getTag();
            if (node.getDelay() > 0) text = text + "  " + node.getDelayText();
            items.add(text);
        }
        int checked = 0;
        String tag = ProxySetting.getTag();
        if (!ProxySetting.AUTO.equals(tag)) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).getTag().equals(tag)) {
                    checked = i + 1;
                    break;
                }
            }
        }
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.proxy_nodes).setSingleChoiceItems(items.toArray(new String[0]), checked, (dialog, which) -> {
            String selected = which == 0 ? ProxySetting.AUTO : nodes.get(which - 1).getTag();
            ProxySetting.putTag(selected);
            if (ProxyBox.get().isRunning()) ProxyControl.get().select(selected);
            dialog.dismiss();
            setState();
        }).setNegativeButton(R.string.dialog_negative, null).show();
    }

    private void onAdd(View view) {
        String link = binding.link.getText().toString().trim();
        if (link.isEmpty()) return;
        int count = ProxySub.add(link);
        if (count == 0) {
            Notify.show(R.string.proxy_unsupported);
            return;
        }
        binding.link.setText("");
        Notify.show(getString(R.string.proxy_added, count));
        setState();
        if (ProxyBox.get().isRunning()) ProxyBox.get().reload();
    }
}

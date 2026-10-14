package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogWebdavBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.webdav.SyncManager;
import com.fongmi.android.tv.webdav.WebDav;
import com.fongmi.android.tv.webdav.WebDavCode;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;

public class WebDavDialog extends BaseAlertDialog {

    private DialogWebdavBinding binding;
    private static final String KNOWN = "webdav_remote_known";
    private boolean asked;
    private String snapshot; // 打开对话框那一刻的设置：关掉时比对，改过就该问一句以哪边为准

    public static WebDavDialog create() {
        return new WebDavDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebdavBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.webdav_title).setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setWidth(0.5f);
        binding.scan.setVisibility(View.GONE);
        binding.url.setText(WebDavSetting.getUrl());
        binding.user.setText(WebDavSetting.getUser());
        binding.pass.setText(WebDavSetting.getPass());
        binding.folder.setText(WebDavSetting.getFolder());
        binding.enable.setChecked(WebDavSetting.isEnabled());
        binding.auto.setChecked(WebDavSetting.isAuto());
        binding.cookie.setChecked(WebDavSetting.isCookie());
        if (snapshot == null) snapshot = snap(); // 只记第一次：扫码导入配置也走这里，别把改动记没了
        setLastText();
    }

    /** 当前设置的指纹：地址/账号/密码/同步标识 + 三个开关 */
    private String snap() {
        return WebDavSetting.getUrl() + "|" + WebDavSetting.getUser() + "|" + WebDavSetting.getPass() + "|" + WebDavSetting.getFolder() + "|" + WebDavSetting.isEnabled() + "|" + WebDavSetting.isAuto() + "|" + WebDavSetting.isCookie();
    }

    private boolean changed() {
        return snapshot != null && !snapshot.equals(snap());
    }

    /**
     * 同步开着、并且这次确实动了设置（换地址/换同步标识/换账号/开关都算）→ 关掉前必须问清楚以哪边为准。
     * 光看 isSwitch() 不够：改了设置却没换目标的情况（比如刚打开同步）同样可能把两边数据混在一起。
     */
    private boolean needAsk() {
        return WebDavSetting.isSyncable() && (WebDavSetting.isSwitch() || changed());
    }

    @Override
    protected void initEvent() {
        binding.sync.setOnClickListener(this::onSync);
        binding.test.setOnClickListener(this::onTest);
        binding.push.setOnClickListener(this::onPush);
        binding.pull.setOnClickListener(this::onPull);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.lan.setOnClickListener(this::onLan);
        binding.enable.setOnCheckedChangeListener((view, checked) -> save());
        binding.auto.setOnCheckedChangeListener((view, checked) -> save());
        binding.cookie.setOnCheckedChangeListener((view, checked) -> save());
        watch(binding.url, binding.user, binding.pass, binding.folder);
    }

    /** 输入框失焦就存一次，不用非得点按钮 */
    /** 输入框失焦就存一次；拿到焦点时把光标挪到内容最右边 */
    private void watch(EditText... views) {
        for (EditText view : views) view.setOnFocusChangeListener((v, focus) -> {
            if (focus) view.setSelection(view.getText().length());
            else save();
        });
    }

    /** 关掉对话框时兜底保存一次，改完直接退出也不会白改 */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save();
        // ⚠️ 对话框一关，DialogFragment 紧接着就被摘下来了：下面 checkRemote 要跑一趟网络，
        // 等它回来 getActivity() 已经是 null，风险提示就「静默不弹」（马先生 2026-10-14 改同步标识后没弹就是这么漏的）。
        // 所以这里先把 Activity 抓在手上，一路往下传。
        FragmentActivity activity = getActivity();
        super.onDismiss(dialog);
        checkRemote(activity, () -> askOnClose(activity, R.string.sync_risk_dav, needAsk()));
    }

    /**
     * 远端这份同步目录里已经有数据（可能是别的设备、别的账号留下的）：
     * 先把话说清楚，别稀里糊涂把两边的观看记录、收藏混在一起。
     */
    private void checkRemote(FragmentActivity activity, Runnable next) {
        if (!WebDavSetting.isValid()) {
            next.run();
            return;
        }
        String scope = WebDavSetting.getScope();
        if (Prefers.getString(KNOWN).equals(scope)) {
            next.run();
            return;
        }
        Task.execute(() -> {
            boolean found = false;
            try {
                found = WebDav.exists(WebDavSetting.getFolderUrl());
            } catch (Exception ignored) {
            }
            boolean exist = found;
            App.post(() -> onRemote(activity, exist, scope, next));
        });
    }

    private void onRemote(FragmentActivity activity, boolean found, String scope, Runnable next) {
        Prefers.put(KNOWN, scope);
        if (!found) {
            next.run();
            return;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        asked = false;
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.webdav_remote_title)
                    .setMessage(R.string.webdav_remote_exists)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> SyncRiskDialog.show(activity, R.string.sync_risk_dav, () -> SyncDirectionDialog.show(activity, direction -> onDirection(activity, direction))))
                    .show();
        } catch (Exception ignored) {
        }
    }

    /**
     * 遥控器改完参数就退出时，只要同步是开着的、这次又动了设置，
     * 就必须问清楚以哪边为准，免得下一次自动同步闷头合并（马先生 2026-10-14 定的：这个口子要堵死）。
     * activity 由调用方传进来：这时候 Fragment 可能已经分离，getActivity() 拿不到了。
     */
    private void askOnClose(FragmentActivity activity, int message, boolean need) {
        if (asked || !need) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        asked = true;
        SyncRiskDialog.show(activity, message, () -> SyncDirectionDialog.show(activity, direction -> onDirection(activity, direction)));
    }

    private void onLan(View view) {
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(6), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
        Notify.show(R.string.webdav_lan_tip);
    }

    private void setLastText() {
        if (WebDavSetting.isSyncable() && WebDavSetting.isSwitch()) {
            binding.last.setText(R.string.sync_direction_wait);
            return;
        }
        long time = WebDavSetting.getLast();
        if (time == 0) binding.last.setText(R.string.webdav_off);
        // 用 App 的上下文取串：同步结果回来得晚，那时候对话框可能已经关了，Fragment 已分离，getString() 会直接崩
        else binding.last.setText(App.get().getString(R.string.webdav_last, DateFormat.format("yyyy-MM-dd HH:mm", new Date(time))));
    }

    private void save() {
        WebDavSetting.putUrl(binding.url.getText().toString());
        WebDavSetting.putUser(binding.user.getText().toString());
        WebDavSetting.putPass(binding.pass.getText().toString());
        WebDavSetting.putFolder(binding.folder.getText().toString());
        WebDavSetting.putEnabled(binding.enable.isChecked());
        WebDavSetting.putAuto(binding.auto.isChecked());
        WebDavSetting.putCookie(binding.cookie.isChecked());
    }

    private void onQrCode(View view) {
        save();
        Bitmap bitmap = QrHelper.encode(WebDavCode.encode(), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        save();
        Notify.progress(this);
        Task.execute(() -> {
            boolean ok = WebDav.test();
            App.post(() -> {
                Notify.dismiss();
                Notify.show(ok ? R.string.webdav_test_ok : R.string.webdav_test_fail);
            });
        });
    }

    private void onSync(View view) {
        save();
        if (WebDavSetting.isSwitch()) {
            // 目标变过：先讲风险，确认后再选 合并/本机/云端
            FragmentActivity activity = requireActivity();
            // 确认风险的回调是延迟执行的，那时候 Fragment 可能已经分离，先把 Activity 抓在手上
            SyncRiskDialog.show(activity, R.string.sync_risk_dav, () -> SyncDirectionDialog.show(activity, this::onDirection));
        } else {
            Notify.progress(this);
            SyncManager.sync(getListener());
        }
    }

    /**
     * 选完方向：换过目标就作废旧基线；不管换没换，当前目标都记成「已确认」，
     * 免得只是改了个开关也一直卡在「待选择同步方向」上。
     */
    private void checkSwitch() {
        asked = true;
        if (WebDavSetting.isSwitch()) SyncManager.resetBase();
        WebDavSetting.putConfirm();
    }

    private void onDirection(int direction) {
        checkSwitch();
        // 关掉对话框后才弹的方向选择，到这里 Fragment 多半已分离，转圈框要跟着 Fragment 走，没附加就别弹
        if (isAdded()) Notify.progress(this);
        if (direction == SyncDirectionDialog.CLOUD) SyncManager.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) SyncManager.push(getListener());
        else SyncManager.sync(getListener());
    }

    private void onDirection(FragmentActivity activity, int direction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkSwitch();
        Notify.progress(activity);
        if (direction == SyncDirectionDialog.CLOUD) SyncManager.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) SyncManager.push(getListener());
        else SyncManager.sync(getListener());
    }

    private void onPush(View view) {
        save();
        checkSwitch();
        Notify.progress(this);
        SyncManager.push(getListener());
    }

    private void onPull(View view) {
        save();
        checkSwitch();
        Notify.progress(this);
        SyncManager.pull(getListener());
    }

    private SyncManager.Listener getListener() {
        return (success, message) -> {
            Notify.dismiss();
            Notify.show(message);
            // 结果回来时对话框可能已经关了：binding 还在不代表 Fragment 还挂着，两个都要看
            if (isAdded() && binding != null) setLastText();
        };
    }
}

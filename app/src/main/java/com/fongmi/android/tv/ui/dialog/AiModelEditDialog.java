package com.fongmi.android.tv.ui.dialog;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiModel;
import com.fongmi.android.tv.databinding.DialogAiModelEditBinding;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.utils.AiChat;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 一条大模型的录入/修改：名称、接入地址（OpenAI 兼容）、API Key、模型名。
 * 底下那个「测一下」是真的发一句话过去——通不通用了才知道，别存一条用不了的进去。
 * 三端共用（TV 上用遥控器填，也能扫网页录入）。
 */
public class AiModelEditDialog extends BaseAlertDialog {

    private DialogAiModelEditBinding binding;
    private AiModel model;
    private Runnable onDone;

    public static AiModelEditDialog create(AiModel model) {
        AiModelEditDialog dialog = new AiModelEditDialog();
        dialog.model = model == null ? AiModel.create() : model;
        return dialog;
    }

    public AiModelEditDialog done(Runnable onDone) {
        this.onDone = onDone;
        return this;
    }

    public void show(FragmentManager manager) {
        if (manager.isDestroyed()) return;
        show(manager, "ai_model_edit");
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAiModelEditBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        getBinding(); // 同上：基类 onCreateDialog 先走 getBuilder，binding 必须在这里就有
        if (model == null) model = AiModel.create(); // 弹窗被系统重建过（转屏、进程回收）时字段是空的，getBuilder 比 initView 先跑，兜底得放这儿
        return builder().setTitle(model.getName().isEmpty() ? R.string.ai_model_add : R.string.dialog_edit)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> save())
                .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        if (model == null) model = AiModel.create(); // 双保险：getBuilder 已经兜过一次，这里再兜一遍
        if (model.getName().isEmpty() && model.getEndpoint().isEmpty()) model.setEndpoint("https://");
        binding.name.setText(model.getName());
        binding.endpoint.setText(model.getEndpoint());
        binding.key.setText(model.getKey());
        binding.model.setText(model.getModel());
    }

    @Override
    protected void initEvent() {
        binding.test.setOnClickListener(view -> test());
    }

    /** 拿当前填的这四个值去问一句，通不通当场给个话 */
    private void test() {
        AiModel probe = read();
        if (probe == null) {
            Notify.show(R.string.ai_model_incomplete);
            return;
        }
        Notify.show(R.string.ai_model_testing);
        Task.execute(() -> {
            String fail = AiChat.probe(probe);
            App.post(() -> Notify.show(fail == null ? App.get().getString(R.string.ai_model_test_ok) : App.get().getString(R.string.ai_model_test_fail, fail)));
        });
    }

    private void save() {
        AiModel item = read();
        if (item == null) {
            Notify.show(R.string.ai_model_incomplete);
            return;
        }
        AiSetting.save(item);
        Notify.show(R.string.ai_model_saved);
        if (onDone != null) onDone.run();
    }

    /** 四个值读出来；地址/Key/模型名缺一样就当没填完，不存半条 */
    private AiModel read() {
        model.setName(text(binding.name.getText()));
        model.setEndpoint(text(binding.endpoint.getText()));
        model.setKey(text(binding.key.getText()));
        model.setModel(text(binding.model.getText()));
        return model.getEndpoint().isEmpty() || model.getKey().isEmpty() || model.getModel().isEmpty() ? null : model;
    }

    private String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    public static void show(FragmentActivity activity, AiModel model, Runnable onDone) {
        if (activity == null || activity.isFinishing()) return;
        create(model).done(onDone).show(activity.getSupportFragmentManager());
    }
}

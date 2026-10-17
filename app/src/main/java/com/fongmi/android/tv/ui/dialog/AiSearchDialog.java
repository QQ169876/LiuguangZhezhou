package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.databinding.DialogAiSearchBinding;
import com.fongmi.android.tv.ui.adapter.AiPickAdapter;
import com.fongmi.android.tv.utils.AiSearchRunner;

import java.util.List;

/**
 * 智能搜索（手机版入口）：把一句大白话交给大模型理解成找片指令，候选片单列在弹窗里，
 * 点哪部就拿准片名走正常的全站搜索。流程在 AiSearchRunner 里（两端共用），这里只管展示。
 */
public final class AiSearchDialog extends BaseBottomSheetDialog implements AiPickAdapter.OnClickListener, AiSearchRunner.View {

    private OnPickListener listener;
    private AiPickAdapter adapter;
    private DialogAiSearchBinding binding;
    private String question;

    public interface OnPickListener {

        /** 用户选定了要搜的名字（片名/人名/题材词），调用方拿去走正常的全站搜索 */
        void onPick(String keyword);
    }

    public static AiSearchDialog create(String question, OnPickListener listener) {
        AiSearchDialog dialog = new AiSearchDialog();
        dialog.question = question;
        dialog.listener = listener;
        return dialog;
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        show(activity.getSupportFragmentManager(), "ai_search");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogAiSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.question.setText(getString(R.string.ai_search_asked, question));
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new AiPickAdapter(this));
        binding.recycler.setItemAnimator(null);
        AiSearchRunner.run(question, this);
    }

    @Override
    public void onThinking() {
        if (binding == null) return;
        binding.progress.setVisibility(VISIBLE);
        binding.tip.setVisibility(GONE);
        binding.recycler.setVisibility(GONE);
    }

    @Override
    public void onSummary(String summary) {
        if (binding == null || summary.isEmpty()) return;
        binding.summary.setText(getString(R.string.ai_search_understand, summary));
        binding.summary.setVisibility(VISIBLE);
    }

    @Override
    public void onRows(List<TmdbWork> rows) {
        if (binding == null) return;
        adapter.setItems(rows);
        binding.progress.setVisibility(GONE);
        binding.tip.setVisibility(GONE);
        binding.recycler.setVisibility(VISIBLE);
    }

    @Override
    public void onEnrich(int position, TmdbWork work) {
        if (binding == null) return;
        adapter.update(position, work);
    }

    @Override
    public void onTip(int resId) {
        if (binding == null) return;
        binding.progress.setVisibility(GONE);
        binding.recycler.setVisibility(GONE);
        binding.tip.setText(resId);
        binding.tip.setVisibility(VISIBLE);
    }

    @Override
    public void onDirect(String keyword) {
        pick(keyword);
    }

    private void pick(String keyword) {
        dismissAllowingStateLoss();
        try {
            if (listener != null) listener.onPick(keyword);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onItemClick(TmdbWork item) {
        pick(item.getName());
    }
}

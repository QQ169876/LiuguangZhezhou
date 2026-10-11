package com.fongmi.android.tv.ui.dialog;

import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.databinding.DialogStarBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * 详情页点「演员」那行弹出来的：把站点给的演员串拆成一个个名字，点一下关注/取消关注。
 * 关注的演员在收藏页的「演员」分类里能看到，跟着同步走，几台设备共用一份。
 */
public class StarDialog extends BaseAlertDialog {

    private DialogStarBinding binding;
    private String actor;

    public static StarDialog create() {
        return new StarDialog();
    }

    public StarDialog actor(String actor) {
        this.actor = actor;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogStarBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.star).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        List<String> names = Star.split(actor);
        int pad = ResUtil.dp2px(12);
        for (String name : names) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(pad, pad, pad, pad);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setBackgroundResource(R.drawable.selector_vod);
            MaterialTextView nameView = new MaterialTextView(requireContext());
            nameView.setText(name);
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameView.setSingleLine(true);
            MaterialTextView stateView = new MaterialTextView(requireContext());
            stateView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            setState(stateView, name);
            row.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(stateView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnClickListener(view -> onToggle(name, stateView));
            binding.actors.addView(row);
        }
    }

    private void setState(MaterialTextView view, String name) {
        view.setText(Star.exist(name) ? R.string.star_followed : R.string.star_follow);
    }

    private void onToggle(String name, MaterialTextView view) {
        boolean followed = Star.toggle(name);
        setState(view, name);
        Notify.show(followed ? R.string.star_add : R.string.star_del);
        RefreshEvent.star();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (binding.actors.getChildCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}

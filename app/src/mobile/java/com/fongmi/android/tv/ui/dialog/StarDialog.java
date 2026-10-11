package com.fongmi.android.tv.ui.dialog;

import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.ui.custom.StarWorks;
import com.fongmi.android.tv.ui.dialog.StarWorksDialog;
import com.fongmi.android.tv.databinding.DialogStarBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * 详情页长按「演员」那行弹出来的：把站点给的演员串拆成一个个名字，点一下关注/取消关注。
 * 关注的演员在收藏页的「演员」分类里能看到，跟着同步走，几台设备共用一份。
 */
public class StarDialog extends BaseAlertDialog {

    private DialogStarBinding binding;
    private StarWorks.Action works;
    private String actor;

    public static StarDialog create() {
        return new StarDialog();
    }

    public StarDialog actor(String actor) {
        this.actor = actor;
        return this;
    }

    /** 点「作品」之后往哪跳：手机走 SearchActivity，由外面给 */
    public StarDialog works(StarWorks.Action works) {
        this.works = works;
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
        TypedValue ripple = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (String name : names) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 左边一大块点着切关注，右边「作品」另开一条路去看他演过什么
            LinearLayout main = new LinearLayout(requireContext());
            main.setOrientation(LinearLayout.HORIZONTAL);
            main.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            main.setPadding(pad, pad, pad, pad);
            main.setBackgroundResource(ripple.resourceId);
            MaterialTextView nameView = new MaterialTextView(requireContext());
            nameView.setText(name);
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameView.setSingleLine(true);
            MaterialTextView stateView = new MaterialTextView(requireContext());
            stateView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            setState(stateView, name);
            main.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            main.addView(stateView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            main.setOnClickListener(view -> onToggle(name, stateView));
            MaterialTextView worksView = new MaterialTextView(requireContext());
            worksView.setText(R.string.star_works);
            worksView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            worksView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            worksView.setPadding(pad, pad, pad, pad);
            worksView.setBackgroundResource(ripple.resourceId);
            worksView.setOnClickListener(view -> onWorks(name));
            row.addView(main);
            row.addView(worksView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            binding.actors.addView(row);
        }
    }

    private void onWorks(String name) {
        dismissAllowingStateLoss();
        FragmentActivity activity = getActivity();
        if (activity != null && works != null) StarWorksDialog.create().actor(name).action(works).show(activity);
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
    }
}

package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.databinding.DialogStarWorksBinding;
import com.fongmi.android.tv.ui.custom.StarWorks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 点演员弹出来的「他演过什么」。
 *
 * 两端共用这一个（TV 和手机只是对话框宽窄不同），列表内容在 StarWorks 里铺。
 * 点中一部就 dismiss 并把准确的片名交给外面去搜——TMDB 只管把名字查准，找片源还是站点的事。
 */
public class StarWorksDialog extends BaseAlertDialog {

    private DialogStarWorksBinding binding;
    private StarWorks.Action action;
    private String actor;

    public static StarWorksDialog create() {
        return new StarWorksDialog();
    }

    public StarWorksDialog actor(String actor) {
        this.actor = actor;
        return this;
    }

    public StarWorksDialog action(StarWorks.Action action) {
        this.action = action;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogStarWorksBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(getString(R.string.tmdb_works, actor)).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        // 点了就先把框收掉再跳搜索页，不然跳转完回来还盖着一层
        new StarWorks(binding, new StarWorks.Action() {
            @Override
            public void onPick(TmdbWork work) {
                dismissAllowingStateLoss();
                if (action != null) action.onPick(work);
            }

            @Override
            public void onDirect(String name) {
                dismissAllowingStateLoss();
                if (action != null) action.onDirect(name);
            }
        }).show(actor);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (Product.getDeviceType() == 0) setWidth(0.5f); // TV 上屏大，给宽一点好放剧名
    }
}

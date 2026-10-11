package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.adapter.StarAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.custom.StarWorks;
import com.fongmi.android.tv.ui.dialog.StarWorksDialog;
import com.fongmi.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 收藏页。两个分类：影视（老样子）、演员（关注的演员，跨站共用一份，跟着同步走）。
 * 演员点进去是拿名字全站搜一遍；长按进删除态，再点一下是取消关注。
 */
public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener, StarAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mKeepAdapter;
    private StarAdapter mStarAdapter;
    private boolean star;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setTabs();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
        mKeepAdapter = new KeepAdapter(this);
        mStarAdapter = new StarAdapter(this);
    }

    private void setTabs() {
        mBinding.tabVod.setOnClickListener(view -> setTab(false));
        mBinding.tabStar.setOnClickListener(view -> setTab(true));
        setTab(false);
    }

    private void setTab(boolean star) {
        this.star = star;
        mBinding.tabVod.setSelected(!star);
        mBinding.tabStar.setSelected(star);
        mBinding.tabVod.setAlpha(star ? 0.5f : 1f);
        mBinding.tabStar.setAlpha(star ? 1f : 0.5f);
        mBinding.recycler.setAdapter(star ? mStarAdapter : mKeepAdapter);
        load();
    }

    private void load() {
        if (star) getStar();
        else getKeep();
    }

    private void getKeep() {
        mKeepAdapter.setItems(Keep.getVod(), () -> mBinding.progressLayout.showContent(true, mKeepAdapter.getItemCount()));
    }

    private void getStar() {
        mStarAdapter.setItems(Star.getAll(), () -> mBinding.progressLayout.showContent(true, mStarAdapter.getItemCount()));
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP) load();
        else if (event.getType() == RefreshEvent.Type.STAR && star) getStar();
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        mKeepAdapter.remove(item.deleteUser(), () -> {
            if (mKeepAdapter.getItemCount() == 0) mKeepAdapter.setDelete(false);
        });
    }

    /** 点关注的演员：先用 TMDB 把他演过的片列出来，点片名再回 APP 搜（查不到也能直接搜名字） */
    @Override
    public void onItemClick(Star item) {
        StarWorksDialog dialog = StarWorksDialog.create().actor(item.getName());
        dialog.action(new StarWorks.Action() {
            @Override
            public void onPick(TmdbWork work) {
                CollectActivity.start(KeepActivity.this, work.getName());
            }

            @Override
            public void onDirect(String actor) {
                CollectActivity.start(KeepActivity.this, actor);
            }
        });
        dialog.show(this);
    }

    @Override
    public void onItemDelete(Star item) {
        Star.remove(item.getName());
        mStarAdapter.remove(item, () -> {
            if (mStarAdapter.getItemCount() == 0) mStarAdapter.setDelete(false);
            mBinding.progressLayout.showContent(true, mStarAdapter.getItemCount());
        });
        Notify.show(R.string.star_del);
    }

    @Override
    public boolean onLongClick() {
        if (star) mStarAdapter.setDelete(true);
        else mKeepAdapter.setDelete(true);
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (star && mStarAdapter.isDelete()) mStarAdapter.setDelete(false);
        else if (!star && mKeepAdapter.isDelete()) mKeepAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}

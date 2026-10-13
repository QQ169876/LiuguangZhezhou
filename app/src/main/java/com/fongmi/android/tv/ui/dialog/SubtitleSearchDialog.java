package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.OpenSubApi;
import com.fongmi.android.tv.api.SubtitleApi;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.api.SubdlApi;
import com.fongmi.android.tv.bean.SubtitleSearchPage;
import com.fongmi.android.tv.databinding.DialogSubtitleSearchBinding;
import com.fongmi.android.tv.impl.SubtitleListener;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.ui.adapter.SubtitleAdapter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SubtitleName;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayDeque;
import java.util.List;

public final class SubtitleSearchDialog extends BaseBottomSheetDialog implements SubtitleAdapter.OnClickListener, SubtitleListener {

    private final ArrayDeque<State> states;
    private final SubtitleAdapter adapter;
    private final CustomScroller scroller;

    private DialogSubtitleSearchBinding binding;
    private PlayerManager player;
    private String keyword;
    private int nextPos;
    private int setting = SubtitleSetting.getSearchSource(); // 用户选的来源（自动/伪射手/OpenSubtitles）
    private int active = SubtitleSetting.SOURCE_OPENSUB;    // 这一趟真正在查的来源（自动模式下会切到备用）
    private boolean triedEnglish;                           // 英文片名兜底只做一次，避免来回打转

    public SubtitleSearchDialog() {
        this.states = new ArrayDeque<>();
        this.adapter = new SubtitleAdapter(this);
        this.scroller = new CustomScroller(ignored -> loadMore());
    }

    public static SubtitleSearchDialog create() {
        return new SubtitleSearchDialog();
    }

    public SubtitleSearchDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleSearchDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.addOnScrollListener(scroller);
        showSource();
        Util.showKeyboard(binding.keyword);
        setKeyword(getTitle());
    }

    @Override
    protected void initEvent() {
        binding.setting.setOnClickListener(this::onSetting);
        binding.source.setOnClickListener(this::onSource);
        requireDialog().setOnKeyListener((dialog, keyCode, event) -> KeyUtil.isActionDown(event) && KeyUtil.isBackKey(event) && restoreState());
        binding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && !getKeyword().isEmpty()) search();
            return true;
        });
        binding.keyword.setOnKeyListener((view, keyCode, event) -> {
            if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && binding.recycler.getVisibility() == VISIBLE) return binding.recycler.requestFocus();
            return false;
        });
    }

    @Override
    protected void onBackInvoked() {
        restoreState();
    }

    @Override
    public void onItemClick(SubtitleSearchItem item) {
        if (item.hasUrl()) applySub(item);
        else detail(item);
    }

    private CharSequence getTitle() {
        MediaMetadata metadata = player.getMetadata();
        return metadata == null || TextUtils.isEmpty(metadata.title) ? "" : metadata.title;
    }

    private String getKeyword() {
        CharSequence text = binding.keyword.getText();
        return text == null ? "" : text.toString().trim();
    }

    private void setKeyword(CharSequence text) {
        if (text == null) text = "";
        binding.keyword.setText(text);
        binding.keyword.setSelection(text.length());
    }

    private void onSetting(View view) {
        SubtitleApiDialog.show(this);
    }

    /** 切换字幕来源：自动 → 伪射手 → OpenSubtitles → 自动……（「自动」= 主源不行就换备用） */
    private void onSource(View view) {
        setting = setting == SubtitleSetting.SOURCE_SUBDL ? SubtitleSetting.SOURCE_AUTO : setting + 1;
        SubtitleSetting.putSearchSource(setting);
        showSource();
        // 只要搜过（哪怕结果是空的）就按新来源立刻重来一遍——最常遇到的就是「这家没搜到，换一家试试」
        if (!TextUtils.isEmpty(keyword)) search();
    }

    private void showSource() {
        binding.source.setText(switch (setting) {
            case SubtitleSetting.SOURCE_ASSRT -> R.string.subtitle_source_assrt;
            case SubtitleSetting.SOURCE_OPENSUB -> R.string.subtitle_source_opensub;
            case SubtitleSetting.SOURCE_SUBDL -> R.string.subtitle_source_subdl;
            default -> R.string.subtitle_source_auto;
        });
    }

    private void showProgress() {
        binding.recycler.setVisibility(GONE);
        binding.progress.setVisibility(VISIBLE);
    }

    private void showResults(boolean empty) {
        binding.progress.setVisibility(GONE);
        binding.recycler.setVisibility(empty ? GONE : VISIBLE);
        if (!empty) binding.recycler.requestFocus();
        updateBackCallback();
    }

    /**
     * 开始搜。原来这里只认伪射手一家，而且**没填 token 就直接 return**，
     * 点搜索一点反应都没有，看着像坏的。现在按来源分派：
     * 「自动」是伪射手有 token 就先走它（中文片更全），没有 / 搜失败 / 没结果就自动换 OpenSubtitles。
     */
    private void search() {
        Util.hideKeyboard(binding.keyword);
        showProgress();
        states.clear();
        adapter.clear();
        scroller.reset();
        keyword = getKeyword();
        nextPos = 0;
        triedEnglish = false;
        if (setting == SubtitleSetting.SOURCE_ASSRT && !SubtitleApi.hasToken()) {
            showResults(true);
            Notify.show(R.string.subtitle_search_need_token);
            return;
        }
        if (setting == SubtitleSetting.SOURCE_SUBDL && !SubdlApi.hasKey()) {
            showResults(true);
            Notify.show(R.string.subtitle_search_need_subdl_key);
            return;
        }
        // 「自动」：谁有凭证谁先上——伪射手（填了 token）→ SubDL（填了 key）→ OpenSubtitles（免 key 兜底）
        active = setting == SubtitleSetting.SOURCE_AUTO ? firstSource() : setting;
        requestSearch();
    }

    @Override
    public void setSubtitleToken(String token) {
        SubtitleSetting.putSearchToken(token);
        if (SubtitleApi.hasToken() && !getKeyword().isEmpty()) search();
    }

    @Override
    public void setSubtitleKeys(String token, String subdlKey) {
        SubtitleSetting.putSearchToken(token);
        SubtitleSetting.putSearchSubdlKey(subdlKey);
        if ((SubtitleApi.hasToken() || SubdlApi.hasKey()) && !getKeyword().isEmpty()) search();
    }

    private void detail(SubtitleSearchItem item) {
        showProgress();
        updateBackCallback();
        scroller.endLoading(hasMore());
        SubtitleApi.detail(item.getId(), this::showItems, this::onError);
    }

    private void applySub(SubtitleSearchItem item) {
        if (item.isZip()) downloadSub(item);
        else applySub(item.toSub());
    }

    private void applySub(Sub sub) {
        player.setSub(sub);
        dismiss();
    }

    private void downloadSub(SubtitleSearchItem item) {
        showProgress();
        updateBackCallback();
        scroller.endLoading(hasMore());
        if (active == SubtitleSetting.SOURCE_OPENSUB) OpenSubApi.loadArchive(item, this::showArchive, this::onError);
        else SubtitleApi.loadArchive(item, this::showArchive, this::onError);
    }

    private boolean loadMore() {
        boolean load = hasMore();
        if (load) requestSearch();
        return load;
    }

    private boolean hasMore() {
        return !scroller.isDisable();
    }

    private void requestSearch() {
        updateBackCallback();
        if (active == SubtitleSetting.SOURCE_ASSRT) SubtitleApi.search(keyword, nextPos, this::onSearch, this::onSearchError);
        else if (active == SubtitleSetting.SOURCE_SUBDL) SubdlApi.search(keyword, this::onSearch, this::onSearchError);
        else OpenSubApi.search(keyword, nextPos, this::onSearch, this::onSearchError);
    }

    /** 每家"一页给你几条"，用来判断还有没有下一页 */
    private int countOf(int source) {
        if (source == SubtitleSetting.SOURCE_ASSRT) return SubtitleApi.SEARCH_COUNT;
        if (source == SubtitleSetting.SOURCE_SUBDL) return SubdlApi.SEARCH_COUNT;
        return OpenSubApi.SEARCH_COUNT;
    }

    /** 自动模式的首选来源：有凭证的优先，都没有就用免 key 的 OpenSubtitles */
    private int firstSource() {
        if (SubtitleApi.hasToken()) return SubtitleSetting.SOURCE_ASSRT;
        if (SubdlApi.hasKey()) return SubtitleSetting.SOURCE_SUBDL;
        return SubtitleSetting.SOURCE_OPENSUB;
    }

    private void onSearchError(Exception e) {
        scroller.endLoading(false);
        if (useBackup()) return; // 主源出错：自动模式下换备用源再试，别急着报错
        onError(e);
    }

    /**
     * 用**英文片名**再搜一次。
     *
     * 实测（2026-10-14）：OpenSubtitles 库里只有英文/原文件名，拿「盗梦空间」「沙丘」「奥本海默」
     * 这类中文名去搜，返回全是 0 条——而 App 里显示的偏偏就是中文名，等于外语片在线搜字幕搜不到。
     * 好在播放地址基本都是英文压制名（Dune.Part.Two.2024.2160p...），抠出片名再搜一次就有了。
     * 只试一次，抠不出来就照常报空，不会来回打转。
     */
    private boolean retryEnglish() {
        if (triedEnglish || player == null) return false;
        if (active != SubtitleSetting.SOURCE_OPENSUB && active != SubtitleSetting.SOURCE_SUBDL) return false;
        String english = SubtitleName.fromUrl(player.getUrl());
        if (TextUtils.isEmpty(english) || english.equalsIgnoreCase(keyword)) return false;
        triedEnglish = true;
        setKeyword(keyword = english); // 让用户看见换成了什么，不满意还能接着改
        nextPos = 0;
        showProgress();
        Notify.show(R.string.subtitle_search_retry_en);
        requestSearch();
        return true;
    }

    /** 自动模式下主源不行就换下一家从头再搜一遍；返回 true 表示已经换了 */
    private boolean useBackup() {
        if (setting != SubtitleSetting.SOURCE_AUTO) return false;
        int next = nextSource(active);
        if (next < 0) return false;
        active = next;
        nextPos = 0;
        requestSearch();
        return true;
    }

    /** 自动模式的尝试顺序：伪射手 → SubDL（没填 key 就跳过）→ OpenSubtitles */
    private int nextSource(int current) {
        if (current == SubtitleSetting.SOURCE_ASSRT) return SubdlApi.hasKey() ? SubtitleSetting.SOURCE_SUBDL : SubtitleSetting.SOURCE_OPENSUB;
        if (current == SubtitleSetting.SOURCE_SUBDL) return SubtitleSetting.SOURCE_OPENSUB;
        return -1;
    }

    private void onSearch(SubtitleSearchPage page) {
        // SubDL 一次就给全（最多 30 条、没有翻页），它的 SEARCH_COUNT 是 MAX_VALUE，这里自然判成"没有下一页"，
        // 否则会一直以为还有更多、反复用同一批结果去 loadMore。
        boolean hasMore = page.getResultCount() >= countOf(active);
        nextPos += page.getResultCount();
        scroller.endLoading(hasMore);
        adapter.addAll(page.getItems());
        if (adapter.getItemCount() == 0) {
            if (hasMore) loadMore();
            else if (!useBackup() && !retryEnglish()) onEmpty();
        } else {
            showSearchResults();
        }
    }

    private void showSearchResults() {
        showResults(false);
        RecyclerView recycler = binding.recycler;
        recycler.post(() -> scroller.checkMore(recycler));
    }

    private void showArchive(List<SubtitleSearchItem> items) {
        if (items.isEmpty()) onEmpty();
        else if (items.size() == 1) applySub(items.get(0));
        else showItems(items);
    }

    private void showItems(List<SubtitleSearchItem> items) {
        saveState();
        adapter.setItems(items);
        scroller.endLoading(false);
        showResults(false);
    }

    private void onEmpty() {
        showResults(adapter.getItemCount() == 0);
        Notify.show(R.string.error_empty);
    }

    private void onError(Exception e) {
        showResults(adapter.getItemCount() == 0);
        Notify.show(TextUtils.isEmpty(e.getMessage()) ? ResUtil.getString(R.string.error_empty) : e.getMessage());
    }

    /** 两家源都停：搜索请求、下载，一个都别留着在后台跑 */
    private void cancelAll() {
        SubtitleApi.cancel();
        OpenSubApi.cancel();
        SubdlApi.cancel();
    }

    private void saveState() {
        states.push(new State(adapter.getItems(), keyword, nextPos, hasMore()));
    }

    private boolean restoreState() {
        if (canCancelRequest()) {
            cancelAll();
            showResults(false);
            return true;
        } else if (!states.isEmpty()) {
            restore(states.pop());
            return true;
        } else {
            return false;
        }
    }

    private void restore(State state) {
        scroller.reset();
        cancelAll();
        nextPos = state.nextPos();
        adapter.setItems(state.items());
        scroller.endLoading(state.hasMore());
        setKeyword(keyword = state.keyword());
        showResults(adapter.getItemCount() == 0);
    }

    private void updateBackCallback() {
        setBackCallbackEnabled(!states.isEmpty() || canCancelRequest());
    }

    private boolean canCancelRequest() {
        return binding.progress.getVisibility() == VISIBLE && adapter.getItemCount() > 0;
    }

    @Override
    public void onDestroyView() {
        states.clear();
        binding = null;
        cancelAll();
        super.onDestroyView();
    }

    private record State(List<SubtitleSearchItem> items, String keyword, int nextPos, boolean hasMore) {
    }
}

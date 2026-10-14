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
import com.fongmi.android.tv.playback.vod.BingeTags;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.ui.adapter.SubtitleAdapter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SubtitleName;
import com.fongmi.android.tv.utils.Util;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SubtitleSearchDialog extends BaseBottomSheetDialog implements SubtitleAdapter.OnClickListener, SubtitleListener {

    private final ArrayDeque<State> states;
    private final SubtitleAdapter adapter;
    private final CustomScroller scroller;
    private final Set<Integer> pending;    // 这一趟还在等回应的来源
    private final Set<Integer> exhausted;  // 已经翻到底（或出错）的来源
    private final Set<Integer> broken;     // 直接报错（连不上/凭证失效）的源：自动模式下这一趟不再带上它
    private final Map<Integer, Integer> cursors; // 每家各自的翻页游标

    private DialogSubtitleSearchBinding binding;
    private PlayerManager player;
    private String keyword;
    private List<Integer> sources;         // 这一趟要查哪几家
    private List<String> queries;          // 要试的几种写法：中文原名 → 去掉季号的主标题 → 播放地址里的英文原名
    private final Map<Integer, Integer> queryIndex; // 每家用到了第几种写法
    private int setting = SubtitleSetting.getSearchSource(); // 用户选的来源（自动/伪射手/OpenSubtitles/SubDL）
    private Exception lastError;
    private boolean queryNotified;                          // 「换了种写法再搜」只提示一次

    public SubtitleSearchDialog() {
        this.states = new ArrayDeque<>();
        this.adapter = new SubtitleAdapter(this);
        this.scroller = new CustomScroller(ignored -> loadMore());
        this.pending = new HashSet<>();
        this.exhausted = new HashSet<>();
        this.broken = new HashSet<>();
        this.cursors = new HashMap<>();
        this.queryIndex = new HashMap<>();
        this.sources = new ArrayList<>();
        this.queries = new ArrayList<>();
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

    /** 切换字幕来源：自动 → 伪射手 → OpenSubtitles → SubDL → 自动……（「自动」= 几家一起查、结果合并） */
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
     * 开始搜。
     * <p>
     * 「自动」以前是「挑一家，搜不到才换下一家」——等于一次只看一家的库，这家没有就白等一轮。
     * 现在改成**几家同时查、结果合并展示**（马先生 2026-10-14：几家源各有各的库，
     * 合起来才找得到东西）；只有明确指定某一家时才单查那家。
     */
    private void search() {
        Util.hideKeyboard(binding.keyword);
        showProgress();
        states.clear();
        adapter.clear();
        scroller.reset();
        keyword = getKeyword();
        lastError = null;
        queryNotified = false;
        cancelAll();
        pending.clear();
        exhausted.clear();
        cursors.clear();
        queryIndex.clear();
        queries = buildQueries(keyword);
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
        sources = resolveSources();
        if (sources.isEmpty()) {
            onEmpty();
            return;
        }
        for (int source : sources) requestSearch(source);
    }

    /**
     * 这一趟要查哪几家：指定来源就只查它，「自动」= 有凭证的几家全上 + 免 key 的 OpenSubtitles 兜底。
     *
     * 报错过的（连不上、超时、凭证失效）这次先不排：自动模式每趟都带上它，等于每搜一次白等一轮失败，
     * 结果还得等别的家。等用户换了来源或重开弹窗再给它机会。
     */
    private List<Integer> resolveSources() {
        if (setting != SubtitleSetting.SOURCE_AUTO) return List.of(setting);
        List<Integer> list = new ArrayList<>();
        if (SubtitleApi.hasToken() && !broken.contains(SubtitleSetting.SOURCE_ASSRT)) list.add(SubtitleSetting.SOURCE_ASSRT); // 伪射手：中文片最全
        if (SubdlApi.hasKey() && !broken.contains(SubtitleSetting.SOURCE_SUBDL)) list.add(SubtitleSetting.SOURCE_SUBDL);
        if (!broken.contains(SubtitleSetting.SOURCE_OPENSUB)) list.add(SubtitleSetting.SOURCE_OPENSUB); // OpenSubtitles：不用 key，外语片兜底
        if (list.isEmpty()) return List.of(SubtitleSetting.SOURCE_OPENSUB); // 全都报过错就再给一次机会，别让用户无从下手
        return list;
    }

    /**
     * 要试的几种写法（按顺序逐个试，**每家各自推进**，谁先出结果谁先亮）：
     *   1. 剧名原样（一般是中文）；
     *   2. 去掉季号/续集后缀的主标题：「庆余年 第二季」→「庆余年」，字幕站多半按整部剧存；
     *   3. 播放地址里抠出来的英文原名：OpenSubtitles / SubDL 的库里只有英文名——
     *      实测（2026-10-15）拿「庆余年」「沙丘」这类中文名去搜，两家都是 0 条，不换写法就搜不到。
     */
    private List<String> buildQueries(String input) {
        List<String> list = new ArrayList<>();
        addQuery(list, input);
        addQuery(list, BingeTags.serialMainOf(input));
        if (player != null) addQuery(list, SubtitleName.fromUrl(player.getUrl()));
        return list;
    }

    private void addQuery(List<String> list, String value) {
        if (TextUtils.isEmpty(value)) return;
        String text = value.trim();
        if (text.length() < 2) return;
        for (String exist : list) if (exist.equalsIgnoreCase(text)) return;
        list.add(text);
    }

    private void requestSearch(int source) {
        pending.add(source);
        String query = queryOf(source);
        int pos = cursors.getOrDefault(source, 0);
        if (source == SubtitleSetting.SOURCE_ASSRT) SubtitleApi.search(query, pos, page -> onSearch(source, page), e -> onSearchError(source, e));
        else if (source == SubtitleSetting.SOURCE_SUBDL) SubdlApi.search(query, page -> onSearch(source, page), e -> onSearchError(source, e));
        else OpenSubApi.search(query, pos, page -> onSearch(source, page), e -> onSearchError(source, e));
    }

    /** 这家这一趟该用哪种写法 */
    private String queryOf(int source) {
        if (queries.isEmpty()) return keyword;
        int index = Math.min(queryIndex.getOrDefault(source, 0), queries.size() - 1);
        return queries.get(index);
    }

    /** 换下一种写法再查这家；没有下一种就到此为止。换写法时提示一声，让用户看见搜的是什么 */
    private boolean nextQuery(int source) {
        int index = queryIndex.getOrDefault(source, 0) + 1;
        if (index >= queries.size()) {
            exhausted.add(source);
            return false;
        }
        queryIndex.put(source, index);
        cursors.put(source, 0); // 换了写法就从头翻
        setKeyword(keyword = queries.get(index));
        if (!queryNotified) {
            queryNotified = true; // 几家可能同时换写法，提示一次就够
            Notify.show(R.string.subtitle_search_retry_en);
        }
        requestSearch(source);
        return true;
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
        SubtitleApi.loadArchive(item, this::showArchive, this::onError);
    }

    private boolean loadMore() {
        if (!hasMore() || !pending.isEmpty()) return false;
        boolean any = false;
        for (int source : sources) if (!exhausted.contains(source)) {
            requestSearch(source);
            any = true;
        }
        return any;
    }

    private boolean hasMore() {
        return !scroller.isDisable();
    }

    /** 还有哪家没翻到底 */
    private boolean hasMoreSources() {
        for (int source : sources) if (!exhausted.contains(source)) return true;
        return false;
    }

    /** 每家"一页给你几条"，用来判断还有没有下一页 */
    private int countOf(int source) {
        if (source == SubtitleSetting.SOURCE_ASSRT) return SubtitleApi.SEARCH_COUNT;
        if (source == SubtitleSetting.SOURCE_SUBDL) return SubdlApi.SEARCH_COUNT;
        return OpenSubApi.SEARCH_COUNT;
    }

    private void onSearch(int source, SubtitleSearchPage page) {
        if (binding == null || !pending.remove(source)) return; // 弹窗已经关了/换了来源，这一趟的结果就别往里塞了
        // 这家用这个写法一条都没有：换下一种写法再来（不是"这家没有"，是真没搜着）
        if (page.getResultCount() == 0 && nextQuery(source)) return;
        cursors.put(source, cursors.getOrDefault(source, 0) + page.getResultCount());
        if (page.getResultCount() < countOf(source)) exhausted.add(source);
        addItems(source, page.getItems());
        scroller.endLoading(hasMoreSources());
        if (adapter.getItemCount() > 0) showSearchResults(); // 先回来的先亮出来，别干等最慢那家
        if (pending.isEmpty()) onBatchDone();
    }

    /**
     * 把一家给的结果并进大列表：打上这家来源的标记，跟前面几家重复的丢掉。
     * 自动模式下几家一起查，同一个字幕常常被两三家都收着，不去重就是一屏看着像很多、点开全是同一份。
     */
    private void addItems(int source, List<SubtitleSearchItem> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        for (SubtitleSearchItem exist : adapter.getItems()) seen.add(dedupKey(exist));
        List<SubtitleSearchItem> fresh = new ArrayList<>();
        for (SubtitleSearchItem item : items) {
            if (item == null) continue;
            if (seen.add(dedupKey(item))) fresh.add(item.source(source));
        }
        adapter.addAll(fresh);
    }

    /** 同名（忽略大小写与标点差异）+ 同语言算同一份 */
    private static String dedupKey(SubtitleSearchItem item) {
        String name = item.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
        return name + "|" + item.getLang().toLowerCase(Locale.ROOT);
    }

    private void onSearchError(int source, Exception e) {
        if (binding == null || !pending.remove(source)) return;
        exhausted.add(source);
        broken.add(source);
        lastError = e;
        if (pending.isEmpty()) onBatchDone(); // 一家挂了不算数，等其它家
    }

    private void onBatchDone() {
        if (binding == null) return;
        scroller.endLoading(hasMoreSources());
        if (adapter.getItemCount() == 0) {
            if (hasMoreSources()) loadMore();
            else onError(lastError == null ? new IOException(ResUtil.getString(R.string.error_empty)) : lastError);
        } else {
            showSearchResults();
            RecyclerView recycler = binding.recycler;
            recycler.post(() -> scroller.checkMore(recycler)); // 第一页没占满一屏就自动接着加载
        }
    }

    private void showSearchResults() {
        showResults(false);
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

    /** 几家源都停：搜索请求、下载，一个都别留着在后台跑 */
    private void cancelAll() {
        SubtitleApi.cancel();
        OpenSubApi.cancel();
        SubdlApi.cancel();
    }

    private void saveState() {
        states.push(new State(adapter.getItems(), keyword));
    }

    private boolean restoreState() {
        if (canCancelRequest()) {
            cancelAll();
            pending.clear();
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
        pending.clear();
        exhausted.clear();
        cursors.clear();
        adapter.setItems(state.items());
        scroller.endLoading(false);
        setKeyword(keyword = state.keyword());
        showResults(adapter.getItemCount() == 0);
    }

    private void updateBackCallback() {
        setBackCallbackEnabled(!states.isEmpty() || canCancelRequest());
    }

    private boolean canCancelRequest() {
        return !pending.isEmpty() || (binding.progress.getVisibility() == VISIBLE && adapter.getItemCount() > 0);
    }

    @Override
    public void onDestroyView() {
        states.clear();
        binding = null;
        cancelAll();
        super.onDestroyView();
    }

    private record State(List<SubtitleSearchItem> items, String keyword) {
    }
}

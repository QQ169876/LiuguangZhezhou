package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.DebugLog;

import java.util.ArrayList;
import java.util.List;

class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;

    VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
    }

    void playbackError() {
        fallbackToNextLineOrSource();
    }

    void emptyFlag() {
        fallbackToNextLineOrSource();
    }

    void emptyDetail() {
        fallbackToNextSource(false);
    }

    void manualSwitchSource() {
        fallbackToNextSource(true);
    }

    void search(String keyword, boolean autoFallback) {
        controller.interruptBinge(); // 换源要搜了，刷剧先停手
        String word = keyword == null ? "" : keyword.trim();
        if (autoFallback && !credibleName(word)) {
            // 片名丢了/只剩个集数号（马先生 2026-11-04：《终极名单》换源换成了电影《3》）。
            // 拿「3」这种词去全站搜索，名字一字不差能对上的就是别的片——干脆不搜，宁可不换也不换错。
            DebugLog.d("Vod", "自动换源的片名不可靠（" + word + "），不搜了，免得换到别的片");
            state.setSearchKeyword(word);
            state.setAutoFallback(true);
            state.setSelectFirstSource(false);
            host.onSourceExhausted();
            return;
        }
        DebugLog.d("Vod", "换源搜索「" + word + "」 auto=" + autoFallback);
        state.setSearchKeyword(word);
        state.setAutoFallback(autoFallback);
        state.setSelectFirstSource(autoFallback);
        host.onSearchStarted(word);
        host.requestSearch(getSearchableSites(), word);
    }

    void onSearchResult(Result result) {
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        state.setSources(items);
        host.renderSources(state.getSources());
        if (state.isSelectFirstSource()) nextSource();
        if (items.isEmpty()) {
            if (state.isAutoFallback()) host.onSourceExhausted();
            return;
        }
        host.onSearchResult();
    }

    private void fallbackToNextLineOrSource() {
        if (host.isHostFinishing()) return; // 播放页都退了就别再换
        if (!host.isSiteChangeable()) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource(false);
    }

    private boolean fallbackToNextLine() {
        int position = state.getFlagPosition() + 1;
        if (position >= state.getFlags().size()) return false;
        Flag flag = state.getFlags().get(position);
        host.showSwitchLine(flag);
        controller.selectFlag(flag);
        return true;
    }

    private void fallbackToNextSource(boolean force) {
        if (!state.hasSources()) search(host.getVodName(), true);
        else if (state.isAutoFallback() || force) nextSource();
    }

    private void nextSource() {
        if (host.isHostFinishing()) return;
        while (state.hasSources()) {
            Vod item = state.removeFirstSource();
            host.renderSources(state.getSources());
            // 自动换源只许换「同一部片」：名字对不上搜索词的（早前手动搜索残留的结果、站点串台的）一律跳过
            if (state.isAutoFallback() && !sameName(item.getName(), state.getSearchKeyword())) {
                DebugLog.d("Vod", "跳过不同名的源 " + nameOf(item) + "（关键词「" + state.getSearchKeyword() + "」）");
                continue;
            }
            host.showSwitchSource(item);
            state.addFailedId(host.getVodId());
            state.setSelectFirstSource(false);
            DebugLog.d("Vod", "换源到「" + nameOf(item) + "」（" + item.getSiteName() + "）关键词「" + state.getSearchKeyword() + "」");
            controller.fallbackSource(item);
            return;
        }
        if (state.isAutoFallback()) host.onSourceExhausted(); // 挑了一圈没有同名的：按源用尽处理
    }

    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isPass(site)) sites.add(site);
        return sites;
    }

    private boolean isPass(Site item) {
        if (state.isAutoFallback() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private boolean mismatch(Vod item) {
        if (host.getVodId().equals(item.getId())) return true;
        if (state.hasFailedId(item.getId())) return true;
        if (state.isAutoFallback()) return !sameName(item.getName(), state.getSearchKeyword());
        return !flat(item.getName()).contains(flat(state.getSearchKeyword()));
    }

    /**
     * 自动换源要的是「同一部片」：名字一字不差才算（前后空格、中间空白、大小写不算差异）。
     * 历史记录里的片名常常带尾随空格，源那边又不带，不归一化的话同名片搜出来也被当成不是一部。
     */
    private boolean sameName(String name, String keyword) {
        return flat(name).equals(flat(keyword));
    }

    static String flat(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    private String nameOf(Vod item) {
        String name = item.getName();
        return name == null ? "" : name;
    }

    /**
     * 片名可不可信：空的、一个字的、一两位纯数字或整个名字就是「第N集/3集」这种的当不了片名——
     * 多半是详情丢了名字只剩集数号。三位以上纯数字放行（《1917》《300》是真片名），
     * 「终极名单 第一季」这种带正经片名前缀的更不能拦。
     * 自动换源、覆盖观看记录片名之前都先问这一句，坏名字一个字都不许往外传。
     */
    static boolean credibleName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.length() < 2) return false;
        if (text.matches("[0-9\\-]+")) return text.length() >= 3;
        return !text.matches("第?\\d+[集季部话]?");
    }
}

package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.setting.BingeSetting;
import com.fongmi.android.tv.utils.AiGuard;
import com.fongmi.android.tv.utils.AiJudge;
import com.fongmi.android.tv.utils.DebugLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 刷剧：一部短剧播完后，跨站搜同题材的下一部，一部接一部往下播 */
class VodBingePolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;

    private final List<Vod> pool = new ArrayList<>();
    private final Set<String> tried = new HashSet<>();
    private final Runnable timeoutTask = this::onTimeout;

    private List<String> keywords = new ArrayList<>();
    private String region = "";
    private int keywordIndex;
    private int serial;
    private int failures;
    private boolean active;
    private boolean waitingDetail; // 已选中一部、正在等详情回来校验

    VodBingePolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
    }

    boolean isActive() {
        return active;
    }

    /** 刷剧还在管事（搜索中或等详情）：搜索结果只能归刷剧，绝不许流进换源 */
    boolean isHandling() {
        return active || waitingDetail;
    }

    /** 换源搜索要开始了，刷剧先停，免得两边抢结果 */
    void interrupt() {
        active = false;
        App.removeCallbacks(timeoutTask);
    }

    /** 没有下一集了：刷剧开着的又是短剧，就去找下一部；返回 true 表示接管了 */
    boolean onNoNext(boolean reversed) {
        if (reversed || !BingeSetting.isEnabled()) return false;
        if (AiGuard.blocked(host.getVodName())) return false; // 碰线的剧不联想、不搜索
        if (!isShortDrama()) return false;
        if (serial >= BingeSetting.MAX_SERIAL) {
            host.onBingeEnd();
            return true; // 接管掉，别再叠一个「没有下一集」的提示
        }
        begin();
        return true;
    }

    /** 当前这部能不能一部接一部刷下去，决定「下一部」按钮要不要显示 */
    boolean canBingeNow() {
        if (!BingeSetting.isEnabled()) return false;
        if (AiGuard.blocked(host.getVodName())) return false; // 碰线的剧不联想、不搜索
        if (state.getHistory() != null && state.getHistory().isRevPlay()) return false; // 倒序看就别刷
        if (!state.hasFlags()) return false;
        int episodes = state.getFlag().getEpisodes().size();
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        String name = host.getVodName();
        if (episodes >= BingeTags.MIN_PLAYABLE) return BingeTags.canBinge(episodes, duration, name);
        // 集数很少，看看是不是整部短剧压成一个视频的合集版
        int verdict = BingeTags.compileOf(name, "", episodes, duration);
        if (verdict == BingeTags.COMPILE_YES) return true;
        if (verdict == BingeTags.COMPILE_ASK) {
            String key = BingeTags.compileKey(name, "", episodes, duration);
            Boolean hit = AiJudge.peek(key);
            if (hit != null) return hit; // 模型给过结论就按模型的来
            if (AiSetting.isEnabled()) AiJudge.ask(key, name, "", episodes, duration, host.getVodMark(), null); // 先问着，播完就有结论了
            return BingeTags.compileFallback(name, "", episodes, duration); // 模型没结论，本地评分兜底
        }
        return false;
    }

    private boolean isShortDrama() {
        return canBingeNow();
    }

    /** 手动点「下一部」：不等播完，立刻换一部 */
    void forceNext() {
        if (!BingeSetting.isEnabled()) return;
        if (AiGuard.blocked(host.getVodName())) return; // 碰线的剧不联想、不搜索
        if (serial >= BingeSetting.MAX_SERIAL) {
            host.onBingeEnd();
            return;
        }
        App.removeCallbacks(timeoutTask);
        pool.clear();
        active = false;
        waitingDetail = false;
        begin();
    }

    private void begin() {
        region = BingeTags.regionOf(host.getVodName()); // 当前是哪一国的，后面就一路哪一国
        keywords = BingeTags.keywords(host.getVodName(), "", region);
        DebugLog.d("Binge", "开始刷剧，产地=" + region + "，关键词=" + keywords + "，当前=" + host.getVodName());
        active = true;
        keywordIndex = 0;
        tried.add(host.getVodName());
        search();
    }

    private void search() {
        App.removeCallbacks(timeoutTask);
        if (host.isHostFinishing()) return; // 页面都退了别再发搜索
        if (keywordIndex >= keywords.size()) {
            end();
            return;
        }
        host.requestBingeSearch(sites(), keywords.get(keywordIndex++));
        App.post(timeoutTask, BingeSetting.SEARCH_TIMEOUT);
    }

    private List<Site> sites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (site.isSearchable()) sites.add(site);
        return sites;
    }

    /** 搜索是逐站回来的，每次都往池子里补，凑到第一个就给提示 */
    void onSearchResult(Result result) {
        if (!active || result == null) return;
        List<Vod> items = filter(result.getList());
        if (items.isEmpty()) return;
        pool.addAll(items);
        pick();
    }

    private List<Vod> filter(List<Vod> list) {
        List<Vod> items = new ArrayList<>();
        if (list == null || list.isEmpty()) return items;
        String current = host.getVodName();
        for (Vod item : list) {
            String name = item.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.equals(current) || tried.contains(name)) continue;
            if (contains(pool, name) || state.hasFailedId(item.getId())) continue;
            if (BingeTags.isBanned(name)) continue;
            if (!BingeTags.sameRegion(region, name)) continue; // 别跳到别的国籍去
            int episodes = BingeTags.parseEpisodes(item.getRemarks());
            if (episodes > 0 && episodes < BingeTags.MIN_PLAYABLE) {
                // 集数很少，可能是整部压成一个视频的合集版：是就收，拿不准的先问大模型
                int verdict = BingeTags.compileOf(name, item.getRemarks(), episodes, 0);
                if (verdict == BingeTags.COMPILE_NO) continue;
                if (verdict == BingeTags.COMPILE_ASK && !ask(item, episodes) && !BingeTags.compileFallback(name, item.getRemarks(), episodes, 0)) continue;
            }
            if (watched(name)) continue;
            items.add(item);
        }
        return items;
    }

    /**
     * 拿不准是不是合集的条目，去问大模型。有缓存结论的直接回；问完回来如果还轮得到它，就进池子。
     * 返回 false 表示这次先放下（没结论），不是判它死刑——下一轮搜到还会再问一次，但只问一次就记住了。
     */
    private boolean ask(Vod item, int episodes) {
        if (!AiSetting.isEnabled()) return false;
        String name = item.getName();
        String key = BingeTags.compileKey(name, item.getRemarks(), episodes, 0);
        Boolean hit = AiJudge.peek(key);
        if (hit != null) return hit; // 有缓存：同步返回，filter 自己把它放进候选，不走回调
        // 没缓存才发起询问，回调只会异步回来，不会在 filter 迭代中途动 pool
        AiJudge.ask(key, name, item.getRemarks(), episodes, 0, item.getSiteName(), ok -> {
            if (!ok || !isHandling()) return;
            if (tried.contains(name) || contains(pool, name)) return;
            DebugLog.d("Binge", "大模型认下合集 " + name);
            pool.add(item);
            pick();
        });
        return false;
    }

    private boolean contains(List<Vod> list, String name) {
        for (Vod item : list) if (name.equals(item.getName())) return true;
        return false;
    }

    private boolean watched(String name) {
        try {
            return !History.findByName(name).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 选中就换过去，不等确认：不想看了用户自己按返回 */
    private void pick() {
        if (!active || pool.isEmpty()) return; // 已经选中一部在等详情了，不许再选，防止双跳
        pool.sort((a, b) -> Integer.compare(BingeTags.score(b.getName(), keywords), BingeTags.score(a.getName(), keywords)));
        Vod item = pool.remove(0);
        DebugLog.d("Binge", "接下一部 " + item.getName() + " 来自 " + item.getSiteName());
        host.showBingeNext(item);
        tried.add(item.getName());
        serial++;
        waitingDetail = true;
        active = false;
        App.removeCallbacks(timeoutTask);
        controller.selectSource(item);
    }

    /** 换过去的详情回来了：外剧、电影/单集这类接不下去的，换下一部 */
    void onDetailLoaded(Vod item) {
        if (!waitingDetail) return;
        waitingDetail = false;
        if (item != null) {
            String info = String.valueOf(item.getArea()) + item.getTypeName() + item.getName();
            if (!BingeTags.sameRegion(region, info)) { // 详情里写明是别的国籍，换下一部
                DebugLog.d("Binge", "跳过 " + item.getName() + "，产地不符（当前" + region + "）");
                tried.add(item.getName());
                next();
                return;
            }
            String next = BingeTags.regionOf(info); // 站里给了准确地区就拿来当新基准
            if (!next.isEmpty()) region = next;
        }
        if (state.hasFlags() && state.getFlag().getEpisodes().size() >= BingeTags.MIN_PLAYABLE) {
            failures = 0; // 接上了，失败计数清零，重新计连续失败
            return;
        }
        if (state.hasFlags() && isCompiled()) {
            failures = 0; // 只有一集但那是整部压成的合集，也算接上了
            return;
        }
        tried.add(host.getVodName());
        next();
    }

    /** 当前这部是不是「整部短剧压成一个视频」的合集版：本地判得准就本地定，拿不准看大模型有没有结论 */
    private boolean isCompiled() {
        String name = host.getVodName();
        int episodes = state.getFlag().getEpisodes().size();
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        int verdict = BingeTags.compileOf(name, "", episodes, duration);
        if (verdict == BingeTags.COMPILE_YES) return true;
        if (verdict == BingeTags.COMPILE_ASK) {
            Boolean hit = AiJudge.peek(BingeTags.compileKey(name, "", episodes, duration));
            if (hit != null) return hit; // 模型给过结论就按模型的来
            return BingeTags.compileFallback(name, "", episodes, duration); // 模型没结论/不可用，本地评分兜底
        }
        return false;
    }

    /** 这一部接不下去，接着找下一部 */
    private void next() {
        if (++failures >= BingeSetting.MAX_FAILURE) {
            end();
            return;
        }
        active = true;
        if (!pool.isEmpty()) pick();
        else if (keywordIndex >= keywords.size()) end();
        else search();
    }

    private void onTimeout() {
        if (!active || host.isHostFinishing()) return;
        if (keywordIndex >= keywords.size()) end();
        else search();
    }

    private void end() {
        DebugLog.d("Binge", "刷剧结束，没有更多候选");
        active = false;
        App.removeCallbacks(timeoutTask);
        host.onBingeEnd();
    }

    void reset() {
        App.removeCallbacks(timeoutTask);
        pool.clear();
        tried.clear();
        keywords.clear();
        region = "";
        keywordIndex = 0;
        serial = 0;
        failures = 0;
        active = false;
        waitingDetail = false;
    }
}

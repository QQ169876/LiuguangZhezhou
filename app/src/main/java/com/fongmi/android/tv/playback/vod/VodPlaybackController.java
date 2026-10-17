package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.setting.BingeSetting;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.webdav.SyncManager;

import java.util.Collections;
import java.util.List;

public class VodPlaybackController {

    private static final String PUSH_PREFIX = "push://";
    private static final String SEARCH_PREFIX = "msearch:";

    private final VodPlaybackHost host;
    private final VodPlaybackState state;
    private final VodHistoryPolicy historyPolicy;
    private final VodFallbackPolicy fallbackPolicy;
    private final VodBingePolicy bingePolicy;
    private History lastHistory;

    public VodPlaybackController(VodPlaybackHost host, VodPlaybackState state) {
        this.host = host;
        this.state = state;
        this.historyPolicy = new VodHistoryPolicy();
        this.fallbackPolicy = new VodFallbackPolicy(this, state, host);
        this.bingePolicy = new VodBingePolicy(this, state, host);
    }

    public void reset() {
        clearPreload();
        state.reset();
        bingePolicy.reset();
    }

    public void checkId() {
        String id = resolveVodId();
        if (id.isEmpty() || id.startsWith(SEARCH_PREFIX)) detailEmpty(false);
        else if (!state.isDetailRequested(host.getVodKey(), id)) requestDetail();
    }

    private String resolveVodId() {
        String id = host.getVodId();
        if (!id.startsWith(PUSH_PREFIX)) return id;
        host.usePushId(id.substring(PUSH_PREFIX.length()));
        return host.getVodId();
    }

    public void requestDetail() {
        String key = host.getVodKey();
        String id = host.getVodId();
        state.setDetailRequest(key, id);
        host.requestDetail(key, id);
    }

    public void onDetailResult(VodDetailResult detail) {
        if (late()) return; // 播放页都退了，迟到的详情不许再起播
        if (detail == null || !detail.matches(host.getVodKey(), host.getVodId())) return;
        Result result = detail.result();
        if (result.getList().isEmpty()) detailEmpty(result.hasMsg());
        else detailLoaded(result.getVod());
        host.showDetailMessage(result.getMsg());
    }

    public void updateVod(Vod item) {
        History history = state.getHistory();
        replaceVodId(history, item.getId());
        mergeFlags(item.getFlags());
        updateHistory(history, item);
        host.renderVodUpdate(item);
        publishActivePlaybackMetadata();
    }

    private void updateHistory(History history, Vod item) {
        String pic = item.getPic();
        String name = item.getName();
        boolean hasPic = !pic.isEmpty();
        boolean hasName = !name.isEmpty();
        if (hasPic) history.setVodPic(pic);
        if (hasName) history.setVodName(name);
        if (hasName || hasPic) historyPolicy.saveCurrent(history);
    }

    private void publishActivePlaybackMetadata() {
        Episode episode = findEpisode(state.getActiveRequest());
        if (episode != null) publishPlaybackMetadata(episode);
    }

    private void replaceVodId(History history, String id) {
        if (id.isEmpty() || id.equals(host.getVodId())) return;
        String oldKey = host.getHistoryKey();
        host.setVodId(id);
        String newKey = host.getHistoryKey();
        history.replace(newKey);
        Keep.replace(oldKey, newKey);
    }

    public void onPlaybackResult(PlaybackResult<VodPlayRequest> playback) {
        if (late()) return;
        if (playback == null || cannotApply(playback)) return;
        applyPlaybackResult(playback.result(), playback.request());
    }

    private void applyPlaybackResult(Result result, VodPlayRequest request) {
        Episode episode = findEpisode(request);
        if (episode == null) return;
        applyPlaybackState(result, request);
        renderPlaybackResult(result);
        updatePlaybackPosition(result);
        host.loadDanmaku(result, state.getHistory(), episode);
        startPlayback(result, startPositionMs(), episode);
        updatePreload(result);
    }

    private void applyPlaybackState(Result result, VodPlayRequest request) {
        state.setQuality(result);
        state.setPlayingRequest(request);
        state.setUseParse(result.isUseParse());
        result.getUrl().set(state.getQualityPosition());
    }

    private void renderPlaybackResult(Result result) {
        host.renderUseParse(state.isUseParse());
        host.renderQuality(result, result.getUrl().isMulti());
        if (result.hasDesc()) host.renderDescription(result.getDesc());
        if (result.hasArtwork()) host.renderArtwork(result.getArtwork());
    }

    private void updatePlaybackPosition(Result result) {
        if (result.hasPosition()) state.getHistory().setPosition(result.getPosition());
    }

    private void updatePreload(Result result) {
        if (result.needParse() || state.isUseParse()) clearPreload();
        else preloadNextEpisode();
    }

    private void startPlayback(Result result, long startPositionMs, Episode episode) {
        MediaMetadata metadata = publishPlaybackMetadata(episode);
        host.startPlayback(result, state.isUseParse(), startPositionMs, metadata);
    }

    /** 手动点「下一部」按钮 */
    public void bingeNext() {
        bingePolicy.forceNext();
    }

    /**
     * 当前播的是不是这部剧的第一集。
     * 倒序播放的（从后往前看）按「倒着数的第一集」算，也就是列表里最后一集。
     * position 可能是 -1（刚进来还没选定播哪集），拿不准的时候一律说不是，免得误伤。
     */
    public boolean isFirstEpisode() {
        if (!state.hasEpisode()) return false;
        Flag flag = state.getFlag();
        List<Episode> episodes = flag.getEpisodes();
        if (episodes == null || episodes.isEmpty()) return false;
        boolean rev = state.getHistory() != null && state.getHistory().isRevPlay();
        int position = flag.getPosition();
        return rev ? position == episodes.size() - 1 : position == 0;
    }

    /** 「下一部」按钮要不要显示 */
    public boolean canBinge() {
        return bingePolicy.canBingeNow();
    }

    public void onSearchResult(Result result) {
        if (late()) return; // 退出后才回来的搜索结果，不许再自动换源
        if (bingePolicy.isHandling()) bingePolicy.onSearchResult(result); // 刷剧搜出的只归刷剧，丢了也不许进换源
        else fallbackPolicy.onSearchResult(result);
    }

    /** 换源搜索开始前先让刷剧停手，两边不抢结果 */
    public void interruptBinge() {
        bingePolicy.interrupt();
    }

    public void selectFlag(Flag item) {
        selectFlag(item, false);
    }

    private void selectFlag(Flag item, boolean force) {
        if (!state.hasFlags()) return;
        Flag selected = resolveFlag(item);
        if (!force && selected.isSelected()) return;
        clearPreload();
        for (Flag flag : state.getFlags()) flag.setSelected(selected);
        host.renderFlagSelection(selected);
        host.renderEpisodes(selected.getEpisodes());
        host.renderQualityVisible(false);
        seamless(selected);
    }

    public void selectEpisode(Episode item) {
        if (!state.hasFlags()) return;
        saveCurrentHistory();
        Flag selected = state.getFlag();
        for (Flag flag : state.getFlags()) flag.toggle(flag == selected, item);
        historyPolicy.updateEpisode(state.getHistory(), state.getFlag(), item);
        host.renderEpisodeSelection(item);
        if (host.isFullscreenForPlayback()) host.showEpisodeReady(item);
        playEpisode(item);
        maybePrepareBinge(); // 还剩没几集就提前备下一部，播完秒接
    }

    /** 刷剧预加载：还剩 PREPARE_AHEAD 集时就提前把下一部搜好备着，播完直接接上 */
    private void maybePrepareBinge() {
        if (!state.hasFlags()) return;
        History history = state.getHistory();
        if (history != null && history.isRevPlay()) return; // 倒着看别提前备，方向是反的
        Flag flag = state.getFlag();
        int remaining = flag.getEpisodes().size() - flag.getPosition() - 1;
        if (remaining <= BingeSetting.PREPARE_AHEAD) bingePolicy.prepare();
    }

    private void playEpisode(Episode item) {
        Result result = state.consumePreload(host.getVodKey(), state.getFlag(), item);
        if (result == null) {
            clearPreload();
            host.stopPlaybackForRefresh();
            requestSelectedEpisode();
        } else {
            host.stopPlaybackForRefresh();
            applyPlaybackResult(result, VodPlayRequest.create(host.getVodKey(), state.getFlag(), item));
        }
    }

    public void selectQuality(Result result) {
        if (!state.hasEpisode()) return;
        state.setQuality(result);
        state.setQualityPosition(result.getUrl().getPosition());
        clearPreload();
        startPlayback(result, host.getPlayerPosition(), state.getEpisode());
        preloadNextEpisode();
    }

    public void selectParse(Parse item) {
        VodConfig.get().setParse(item);
        refresh();
    }

    private void mergeFlags(List<Flag> items) {
        if (items.isEmpty()) return;
        if (state.hasFlags()) {
            Flag activated = state.getFlag();
            for (Flag item : items) mergeFlag(activated, item);
            host.renderFlags(state.getFlags());
        } else {
            state.setFlags(items);
            host.renderFlags(state.getFlags());
        }
    }

    public void selectSource(Vod item) {
        switchSource(item, false);
    }

    void fallbackSource(Vod item) {
        switchSource(item, true);
    }

    private void switchSource(Vod item, boolean autoFallback) {
        state.setAutoFallback(autoFallback);
        saveCurrentHistory();
        clearPreload();
        state.clearPlayRequest();
        host.prepareSource(item);
        requestDetail();
    }

    public void search(String keyword) {
        fallbackPolicy.search(keyword, false);
    }

    public void manualSwitchSource() {
        fallbackPolicy.manualSwitchSource();
    }

    public void playbackError(String msg) {
        if (late()) return; // 页面已退，播放器后续的报错一律不理
        clearPreload();
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    public void playbackEnded() {
        if (late()) return; // 退了就别再自动跳下一集
        nextEpisode(true);
    }

    public void replay() {
        if (state.getHistory() != null) state.getHistory().setPosition(C.TIME_UNSET);
        if (host.isPlayerEmpty()) refresh();
        else host.replay(startPositionMs());
    }

    public void refresh() {
        saveCurrentHistory();
        clearPreload();
        host.stopPlaybackForRefresh();
        restorePlaybackSelection(state.getPlayingRequest());
        requestSelectedEpisode();
    }

    public void onPlaybackServiceReady() {
        syncPlaybackMetadata();
        VodPlayRequest request = state.getPlayingRequest();
        if (request == null || host.hasPlaybackSession()) return;
        restorePlaybackSelection(request);
        requestSelectedEpisode();
    }

    private void syncPlaybackMetadata() {
        MediaMetadata metadata = state.getPlaybackMetadata();
        if (metadata != null) host.renderPlaybackMetadata(metadata);
    }

    private void requestSelectedEpisode() {
        if (state.hasEpisode()) requestPlayer(state.getFlag(), state.getEpisode());
    }

    public void nextEpisode(boolean notify) {
        if (state.getHistory() != null && state.getHistory().isRevPlay()) prevEpisode(notify, true);
        else nextEpisode(notify, false);
    }

    public void prevEpisode(boolean notify) {
        if (state.getHistory() != null && state.getHistory().isRevPlay()) nextEpisode(notify, true);
        else prevEpisode(notify, false);
    }

    private void nextEpisode(boolean notify, boolean reversed) {
        if (!state.hasEpisode()) return;
        Episode item = getRelativeEpisode(1);
        if (!item.isSelected()) selectEpisode(item);
        else if (!notify) return;
        else if (!bingePolicy.onNoNext(reversed)) host.showNoNext(reversed);
    }

    private void prevEpisode(boolean notify, boolean reversed) {
        if (!state.hasEpisode()) return;
        Episode item = getRelativeEpisode(-1);
        if (!item.isSelected()) selectEpisode(item);
        else if (notify) host.showNoPrev(reversed);
    }

    public void reverseEpisode(boolean scroll) {
        if (!state.hasFlags()) return;
        for (Flag flag : state.getFlags()) reverseEpisodes(flag);
        host.renderReverseEpisodes(state.getFlag().getEpisodes(), scroll);
    }

    private void reverseEpisodes(Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        int position = flag.getPosition();
        Collections.reverse(episodes);
        if (position >= 0 && position < episodes.size()) flag.setPosition(episodes.size() - position - 1);
    }

    private void saveCurrentHistory() {
        if (state.getPlayingRequest() == null || !host.canTrackPlaybackProgress()) historyPolicy.save(currentHistory());
        else saveHistory(false, System.currentTimeMillis(), host.getPlayerPosition(), host.getPlayerDuration());
    }

    public void saveHistory(boolean exit, long time, long position, long duration) {
        History history = exit ? historyForExit() : currentHistory();
        if (host.isLivePlayback()) historyPolicy.saveVisit(history, exit, time);
        else historyPolicy.saveProgress(history, exit, time, position, duration);
    }

    public void onTimeChanged(long time, long position, long duration) {
        History history = currentHistory();
        historyPolicy.updateProgress(history, time, position, duration);
        if (duration > 0 && !host.isLivePlayback()) SyncManager.playback();
        if (history != null && history.getEnding() > 0 && history.getEnding() + position >= duration) nextEpisode(false);
    }

    public long startPositionMs() {
        return historyPolicy.startPositionMs(state.getHistory());
    }

    private History currentHistory() {
        History history = state.getHistory();
        if (history != null) lastHistory = history;
        return history;
    }

    private History historyForExit() {
        History history = currentHistory();
        return history == null ? lastHistory : history;
    }

    public void onPreloadResult(PlaybackResult<VodPlayRequest> preload) {
        if (!isPendingPreload(preload)) return;
        applyPreload(preload.request(), preload.result());
    }

    private boolean isPendingPreload(PlaybackResult<VodPlayRequest> preload) {
        return preload != null && isPreloading(preload.request()) && state.getPreloadResult() == null;
    }

    private void applyPreload(VodPlayRequest request, Result result) {
        Episode episode = findEpisode(request);
        if (isPreloadable(request, result, episode)) startPreload(result, episode);
        else clearPreload();
    }

    /**
     * 预读结果到手。分两层用：
     * 1. 播放地址先存下来——不管什么内核，切集时都能直接拿它开播，省掉那趟解析等待；
     * 2. 能预取流数据的内核（Exo）再顺手把开头一段取下来，切过去连缓冲都省了。
     * 以前只有第 2 层，且做不成第 2 层就把第 1 层也一起扔了，MPV 用户等于完全没有预读。
     */
    private void startPreload(Result result, Episode episode) {
        result.getUrl().set(state.getQualityPosition());
        long startPositionMs = getPreloadStartPositionMs(result);
        MediaMetadata metadata = VodPlaybackMedia.metadata(state.getHistory(), episode);
        state.completePreload(result);
        if (host.preloadPlayback(result, startPositionMs, metadata)) return;
        host.clearPreload(); // 只清播放器那边的流数据预取，state 里的地址留着切集时用
    }

    public void setOpening(long opening) {
        if (state.getHistory() != null) state.getHistory().setOpening(opening);
    }

    public void setEnding(long ending) {
        if (state.getHistory() != null) state.getHistory().setEnding(ending);
    }

    public void setScale(int scale) {
        if (state.getHistory() != null) state.getHistory().setScale(scale);
    }

    public void setRevSort(boolean revSort) {
        if (state.getHistory() != null) state.getHistory().setRevSort(revSort);
    }

    public void setRevPlay(boolean revPlay) {
        if (state.getHistory() != null) state.getHistory().setRevPlay(revPlay);
    }

    private void detailEmpty(boolean shouldFinish) {
        if (host.isFromCollect() || shouldFinish) host.finishVod();
        else showDetailFallback();
    }

    private void showDetailFallback() {
        String name = host.getVodName();
        if (name.isEmpty()) host.renderEmptyDetail();
        else fallbackDetail(name);
    }

    private void fallbackDetail(String name) {
        host.renderFallbackName(name);
        host.onDetailFallbackScheduled();
        fallbackPolicy.emptyDetail();
    }

    private void detailLoaded(Vod item) {
        VodPlayRequest activeRequest = state.getActiveRequest();
        History history = applyDetail(item);
        if (state.hasFlags()) restoreDetail(activeRequest, history);
        else fallbackPolicy.emptyFlag();
    }

    private History applyDetail(Vod item) {
        item.checkPic(host.getVodPic());
        item.checkName(host.getVodName());
        List<Flag> flags = item.getFlags();
        state.setFlags(flags);
        History history = historyPolicy.findOrCreate(host.getHistoryKey(), host.getVodMark(), item);
        state.setHistory(history);
        lastHistory = history;
        host.renderDetail(item, history);
        host.renderFlags(flags);
        host.renderHistory(history);
        host.onDetailFallbackCancelled();
        bingePolicy.onDetailLoaded(item);
        return history;
    }

    private void restoreDetail(VodPlayRequest activeRequest, History history) {
        restoreDetailSelection(activeRequest, history);
        restoreEpisodeOrder(history);
        resumePlaybackIfNeeded();
    }

    private void restoreDetailSelection(VodPlayRequest activeRequest, History history) {
        if (restorePlaybackSelection(activeRequest)) {
            publishPlaybackMetadata(state.getEpisode());
        } else {
            Flag resume = resumeFlag(history);
            selectFlag(resume == null ? history.getFlag() : resume, true);
        }
    }

    private void restoreEpisodeOrder(History history) {
        if (history.isRevSort()) reverseEpisode(true);
    }

    private void resumePlaybackIfNeeded() {
        if (state.getPlayingRequest() != null && !host.hasPlaybackSession()) requestSelectedEpisode();
    }

    private boolean restorePlaybackSelection(VodPlayRequest request) {
        Flag flag = findFlag(request);
        Episode episode = findEpisode(flag, request);
        if (flag == null || episode == null) return false;
        for (Flag item : state.getFlags()) item.setSelected(flag);
        for (Flag item : state.getFlags()) item.toggle(item == flag, episode);
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        host.renderFlagSelection(flag);
        host.renderEpisodes(flag.getEpisodes());
        host.renderEpisodeSelection(episode);
        host.renderQualityVisible(state.getQuality().getUrl().isMulti());
        return true;
    }

    private void requestPlayer(Flag flag, Episode episode) {
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), flag, episode);
        state.setPendingRequest(request);
        publishPlaybackMetadata(episode);
        host.requestPlayer(request);
    }

    private void preloadNextEpisode() {
        Episode episode = findNextEpisodeToPreload();
        if (episode == null) clearPreload();
        else requestPreload(episode);
    }

    private void requestPreload(Episode episode) {
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), state.getFlag(), episode);
        state.beginPreload(request);
        host.requestPreload(request);
    }

    private Episode findNextEpisodeToPreload() {
        if (!state.hasEpisode() || !host.canPrefetchNext()) return null;
        History history = state.getHistory();
        int offset = history != null && history.isRevPlay() ? -1 : 1;
        Episode episode = getRelativeEpisode(offset);
        return episode.isSelected() ? null : episode;
    }

    private boolean isPreloading(VodPlayRequest request) {
        VodPlayRequest pending = state.getPreloadRequest();
        return pending != null && pending.matches(request);
    }

    private boolean isPreloadable(VodPlayRequest request, Result result, Episode episode) {
        return episode != null && host.canPrefetchNext() && request.accepts(result) && !result.hasMsg() && !result.needParse() && !result.isUseParse() && result.getDrm() == null && !result.getRealUrl().isEmpty();
    }

    private long getPreloadStartPositionMs(Result result) {
        History history = state.getHistory();
        long opening = history == null ? C.TIME_UNSET : history.getOpening();
        long position = result.hasPosition() ? result.getPosition() : C.TIME_UNSET;
        return Math.max(0, Math.max(opening, position));
    }

    private Episode findEpisode(VodPlayRequest request) {
        if (request == null) return null;
        for (Flag flag : state.getFlags()) {
            Episode episode = findEpisode(flag, request);
            if (episode != null) return episode;
        }
        return null;
    }

    private Episode findEpisode(Flag flag, VodPlayRequest request) {
        if (flag == null || request == null) return null;
        for (Episode episode : flag.getEpisodes()) if (request.matches(host.getVodKey(), flag, episode)) return episode;
        return null;
    }

    private Flag findFlag(VodPlayRequest request) {
        if (request == null) return null;
        for (Flag flag : state.getFlags()) if (findEpisode(flag, request) != null) return flag;
        return null;
    }

    private void clearPreload() {
        state.clearPreload();
        host.clearPreload();
    }

    private MediaMetadata publishPlaybackMetadata(Episode episode) {
        MediaMetadata metadata = VodPlaybackMedia.metadata(state.getHistory(), episode);
        state.setPlaybackMetadata(metadata);
        host.renderPlaybackMetadata(metadata);
        return metadata;
    }

    private void seamless(Flag flag) {
        History history = state.getHistory();
        Episode episode = history == null ? null : findResumeEpisode(flag, history);
        host.renderQualityVisible(episode != null && episode.isSelected() && state.getQuality().getUrl().isMulti());
        if (episode == null || episode.isSelected()) return;
        history.setVodRemarks(episode.getName());
        selectEpisode(episode);
    }

    /**
     * 从播放记录进来时，上次看的是哪一集：先看存过的播放地址，再看集名（写法变了也能对上）。
     * 两个都认不出来（换过源、集数整个变了）时：记录里没有进度就跟新剧一样从头播第一集；
     * 有进度却认不出是哪集，就别乱播 —— 播错一集会把那条记录的进度冲掉，留着让用户自己点。
     */
    private Episode findResumeEpisode(Flag flag, History history) {
        Episode episode = flag.findByUrl(history.getEpisodeUrl());
        if (episode == null) episode = flag.find(history.getVodRemarks(), host.getVodMark().isEmpty());
        if (episode != null) return episode;
        if (flag.getEpisodes().isEmpty()) return null;
        if (history.getPosition() > 0 && history.getDuration() > 0) {
            DebugLog.d("Vod", "认不出上次看的集：" + history.getVodRemarks() + " / " + history.getEpisodeUrl() + "，等用户自己点");
            return null;
        }
        return flag.getEpisodes().get(0);
    }

    /**
     * 上次看的是哪条线路。同步下来的记录常常没记线路名，这时候按存过的集（地址优先、其次集名）
     * 在各条线路里找，别一头撞进第一条线路，那部剧就有好几条线。
     */
    private Flag resumeFlag(History history) {
        if (history == null) return null;
        if (!TextUtils.isEmpty(history.getVodFlag()) && findFlag(history.getFlag()) != null) return null;
        String url = history.getEpisodeUrl();
        if (!url.isEmpty()) for (Flag flag : state.getFlags()) if (flag.findByUrl(url) != null) return flag;
        String remarks = history.getVodRemarks();
        if (!TextUtils.isEmpty(remarks)) for (Flag flag : state.getFlags()) if (flag.find(remarks, true) != null) return flag;
        return null;
    }

    private void mergeFlag(Flag activated, Flag item) {
        Flag target = findFlag(item);
        if (target == null) {
            state.getFlags().add(item);
        } else {
            target.mergeEpisodes(item.getEpisodes(), state.getHistory() != null && state.getHistory().isRevSort());
            if (target.equals(activated)) host.renderEpisodes(target.getEpisodes());
        }
    }

    private Flag resolveFlag(Flag item) {
        Flag flag = findFlag(item);
        if (flag != null) return flag;
        return state.getFlags().get(0);
    }

    private Flag findFlag(Flag item) {
        if (item != null) for (Flag flag : state.getFlags()) if (flag.equals(item)) return flag;
        return null;
    }

    /** 播放页是不是已经退了：迟到的网络回调（详情/搜索/播放结果）一律作废，别在首页偷偷换源起播 */
    private boolean late() {
        boolean gone = host.isHostFinishing();
        if (gone) DebugLog.d("Vod", "页面已退出，丢弃迟到的回调");
        return gone;
    }

    private boolean cannotApply(PlaybackResult<VodPlayRequest> playback) {
        VodPlayRequest pending = state.getPendingRequest();
        VodPlayRequest request = playback.request();
        if (host.isHostFinishing() || pending == null || request == null) return true;
        return !pending.matches(request) || findEpisode(request) == null || !request.accepts(playback.result());
    }

    private Episode getRelativeEpisode(int offset) {
        List<Episode> episodes = state.getFlag().getEpisodes();
        int current = state.getFlag().getPosition();
        int position = Math.clamp(current + offset, 0, episodes.size() - 1);
        return episodes.get(position);
    }
}

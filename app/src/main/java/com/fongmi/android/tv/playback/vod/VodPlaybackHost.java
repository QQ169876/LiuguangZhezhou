package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.List;

public interface VodPlaybackHost {

    String getVodKey();

    String getVodId();

    void setVodId(String id);

    String getVodName();

    String getVodPic();

    String getVodMark();

    String getHistoryKey();

    boolean isSiteChangeable();

    boolean isFromCollect();

    boolean isHostFinishing();

    boolean isPlayerEmpty();

    boolean hasPlaybackSession();

    boolean isFullscreenForPlayback();

    boolean isLivePlayback();

    boolean canTrackPlaybackProgress();

    boolean canPreloadNext();

    /** 能不能提前把下一集的播放地址问出来（不要求能预取流数据，任何内核都行） */
    boolean canPrefetchNext();

    long getPlayerPosition();

    long getPlayerDuration();

    void usePushId(String id);

    void requestDetail(String key, String id);

    void requestPlayer(VodPlayRequest request);

    void requestPreload(VodPlayRequest request);

    void requestSearch(List<Site> sites, String keyword);

    void requestBingeSearch(List<Site> sites, String keyword);

    void showBingeNext(Vod item);

    void onBingeEnd();

    /** 系列剧专用：接不下去的时候说「没有下一季」，别拿「没有同类剧集」糊弄 */
    default void onBingeEnd(boolean serial) {
        onBingeEnd();
    }

    void prepareSource(Vod item);

    void stopPlaybackForRefresh();

    void resetPlaybackForError(String msg);

    void replay(long position);

    void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata);

    boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata);

    void clearPreload();

    void loadDanmaku(Result result, History history, Episode episode);

    void renderDetail(Vod item, History history);

    void renderVodUpdate(Vod item);

    void renderEmptyDetail();

    void renderFallbackName(String name);

    void renderFlags(List<Flag> items);

    void renderEpisodes(List<Episode> items);

    void renderFlagSelection(Flag item);

    void renderEpisodeSelection(Episode item);

    void renderReverseEpisodes(List<Episode> items, boolean scroll);

    void renderQuality(Result result, boolean visible);

    void renderQualityVisible(boolean visible);

    void renderSources(List<Vod> items);

    void renderHistory(History history);

    void renderUseParse(boolean useParse);

    void renderArtwork(String url);

    void renderDescription(String desc);

    void renderPlaybackMetadata(MediaMetadata metadata);

    void onDetailFallbackScheduled();

    void onDetailFallbackCancelled();

    void onSearchStarted(String keyword);

    void onSearchResult();

    /** 自动换源时所有源都搜不到，告诉用户真的没戏了 */
    void onSourceExhausted();

    void showDetailMessage(String msg);

    void showSwitchLine(Flag flag);

    void showSwitchSource(Vod item);

    void showEpisodeReady(Episode item);

    void showNoNext(boolean reversed);

    void showNoPrev(boolean reversed);

    void finishVod();
}

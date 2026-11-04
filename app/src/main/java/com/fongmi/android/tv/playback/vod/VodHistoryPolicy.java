package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;

public class VodHistoryPolicy {

    /** 续播时往前让多少：3 秒 —— 够把上一集最后那点画面补回来，又不至于重看得烦 */
    private static final long REWIND_MS = 3000L;

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito()) history.delete();
        // 坏名字（空/一个字/纯集数号）不许覆盖片名：一旦把「终极名单」写成「3」，
        // 以后每次自动换源都拿「3」去全站搜，换回来的永远是那部叫《3》的电影（马先生 2026-11-04）
        if (VodFallbackPolicy.credibleName(item.getName())) history.setVodName(item.getName());
        return history;
    }

    private History create(String key, Vod item) {
        History history = new History();
        history.setKey(key);
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (history == null || !history.canSave() || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.merge().save();
            if (exit) RefreshEvent.history();
        });
    }

    public void saveProgress(History history, boolean exit, long time, long position, long duration) {
        applyProgress(history, time, position, duration);
        save(history, exit);
    }

    public void saveVisit(History history, boolean exit, long time) {
        if (history == null || Setting.isIncognito()) return;
        history.setCreateTime(time);
        history.setPosition(C.TIME_UNSET);
        history.setDuration(C.TIME_UNSET);
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.save();
            if (exit) RefreshEvent.history();
        });
    }

    public void saveCurrent(History history) {
        if (history == null || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(copy::save);
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        if (history == null || flag == null || episode == null) return;
        boolean match = isSameEpisode(history, flag, episode);
        if (!match) history.setPosition(C.TIME_UNSET);
        if (!match) history.setDuration(C.TIME_UNSET);
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    /**
     * 现在要播的这集，是不是记录里上次看的那集。
     * 以前只认集名一字不差，源把「05」写成「第5集」就被当成换了一集，进度直接清掉，
     * 用户再打开只能从头看 —— 所以地址一样、集名一样、集号一样，都算同一集。
     */
    private boolean isSameEpisode(History history, Flag flag, Episode episode) {
        if (flag.getEpisodes().size() <= 1) return true; // 就一集（电影/单集），不用挑
        String url = history.getEpisodeUrl();
        if (!url.isEmpty() && url.equals(episode.getUrl())) return true;
        if (episode.matchesName(history.getEpisode())) return true;
        int number = Util.getNumber(history.getVodRemarks());
        return number != -1 && episode.getNumber() == number;
    }

    public void updateProgress(History history, long time, long position, long duration) {
        applyProgress(history, time, position, duration);
        if (history != null && history.canSave() && history.canScheduleSave()) saveCurrent(history);
    }

    private void applyProgress(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return;
        history.setCreateTime(time);
        history.setPosition(position);
        history.setDuration(duration);
    }

    private History copyForSave(History history) {
        history.markSaveScheduled();
        return history.copy();
    }

    /**
     * 起播位置：拿记录里的进度**往前让 REWIND_MS**（马先生 2026-10-30）。
     *
     * 为什么要回退：上次退出那一刻的画面其实已经看过了，从原位置起播等于那一小段
     * 直接跳过去，剧情接不上；往回拧几秒能把上下文再过一遍，看着就顺了。
     * 只回退不前进，贴着片头的就从头播。
     */
    public long startPositionMs(History history) {
        if (history == null) return C.TIME_UNSET;
        long base = Math.max(history.getOpening(), history.getPosition());
        if (base < 0) return C.TIME_UNSET; // 没记过进度（含 C.TIME_UNSET）
        return Math.max(0, base - REWIND_MS);
    }
}

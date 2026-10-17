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

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito()) history.delete();
        history.setVodName(item.getName());
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

    public long startPositionMs(History history) {
        return history == null ? C.TIME_UNSET : Math.max(history.getOpening(), history.getPosition());
    }
}

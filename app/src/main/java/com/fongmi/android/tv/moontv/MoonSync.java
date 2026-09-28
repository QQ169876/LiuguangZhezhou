package com.fongmi.android.tv.moontv;

import android.util.Log;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与 MoonTV / LunaTV 站点交换收藏与播放进度。
 * 站点侧的键是 source+id，App 侧的键是 siteKey@@@vodId@@@cid，
 * 因为站点的 tvbox 配置里每个源的 key 就是 source，所以两边可以直接对应。
 */
public class MoonSync {

    private static final String TAG = MoonSync.class.getSimpleName();
    private static final Pattern INDEX = Pattern.compile("(\\d+)");

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static ScheduledFuture<?> future;

    public interface Listener {
        void done(boolean success, String message);
    }

    public static void boot() {
        post(20);
    }

    private static synchronized void post(long delaySeconds) {
        cancel();
        if (!MoonSetting.isSyncable() || !MoonSetting.isAuto()) return;
        future = Task.scheduler().schedule(MoonSync::silent, delaySeconds, TimeUnit.SECONDS);
    }

    private static synchronized void cancel() {
        if (future != null) future.cancel(false);
        future = null;
    }

    private static void silent() {
        if (System.currentTimeMillis() - MoonSetting.getLast() < TimeUnit.MINUTES.toMillis(2)) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            doPull();
        } catch (Throwable e) {
            Log.w(TAG, "Auto pull failed", e);
        } finally {
            busy.set(false);
        }
    }

    public static void pull(Listener listener) {
        Task.execute(() -> notify(listener, MoonSync::doPull));
    }

    public static void push(Listener listener) {
        Task.execute(() -> notify(listener, MoonSync::doPush));
    }

    public static void sync(Listener listener) {
        Task.execute(() -> notify(listener, MoonSync::doSync));
    }

    private static void notify(Listener listener, Action action) {
        if (!busy.compareAndSet(false, true)) {
            App.post(() -> listener.done(false, ResUtil.getString(R.string.moontv_busy)));
            return;
        }
        String message;
        try {
            message = action.run();
        } catch (Throwable e) {
            Log.w(TAG, "MoonTV action failed", e);
            String error = ResUtil.getString(R.string.moontv_fail) + " " + describe(e);
            App.post(() -> listener.done(false, error));
            return;
        } finally {
            busy.set(false);
        }
        String result = message;
        App.post(() -> listener.done(true, result));
    }

    private interface Action {
        String run() throws Exception;
    }

    private static class Entry {

        private final String source;
        private final String id;
        private final JSONObject item;

        private Entry(String source, String id, JSONObject item) {
            this.source = source;
            this.id = id;
            this.item = item;
        }

        private long time() {
            return item.optLong("save_time", System.currentTimeMillis());
        }
    }

    private static String describe(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    private static void check() throws Exception {
        if (!MoonSetting.isValid()) throw new Exception(ResUtil.getString(R.string.moontv_empty));
    }

    /* ---------- 拉取：站点 -> 本机 ---------- */

    private static String doPull() throws Exception {
        check();
        int cid = VodConfig.getCid();
        int[] records = pullRecords(MoonApi.playRecords(), cid);
        int[] favorites = pullFavorites(MoonApi.favorites(), cid);
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return summary(records, favorites);
    }

    /**
     * 站点里的记录可能很旧，而本机「最近观看」只显示 60 天内的，
     * 这里把过老的时间压进窗口里（并保持原来的先后顺序），免得拉完看不见。
     */
    private static long visible(long time, int order) {
        long floor = System.currentTimeMillis() - Constant.HISTORY_TIME + TimeUnit.DAYS.toMillis(1);
        return time < floor ? floor + order * 1000L : time;
    }

    private static List<Entry> sort(JSONObject remote) {
        List<Entry> items = new ArrayList<>();
        for (Iterator<String> it = keys(remote); it.hasNext(); ) {
            String name = it.next();
            String[] pair = split(name);
            if (pair == null) continue;
            JSONObject item = remote.optJSONObject(name);
            if (item == null) continue;
            items.add(new Entry(pair[0], pair[1], item));
        }
        Collections.sort(items, (a, b) -> Long.compare(a.time(), b.time()));
        return items;
    }

    private static int[] pullRecords(JSONObject remote, int cid) {
        int add = 0, update = 0;
        List<Entry> items = sort(remote);
        for (int i = 0; i < items.size(); i++) {
            Entry entry = items.get(i);
            JSONObject item = entry.item;
            long saveTime = visible(entry.time(), i);
            long duration = Math.round(item.optDouble("total_time", 0) * 1000);
            long position = Math.round(item.optDouble("play_time", 0) * 1000);
            if (duration <= 0) {
                position = 0;
                duration = 0;
            } else {
                position = Math.min(position, duration);
            }
            History target = new History();
            target.setKey(entry.source.concat(AppDatabase.SYMBOL).concat(entry.id));
            target.cid(cid);
            target.setVodName(item.optString("title"));
            target.setVodPic(item.optString("cover"));
            target.setVodFlag("");
            target.setVodRemarks(mark(item.optInt("index", 1), item.optInt("total_episodes", 1)));
            target.setEpisodeUrl("");
            target.setPosition(position);
            target.setDuration(duration);
            target.setCreateTime(saveTime);
            History old = AppDatabase.get().getHistoryDao().find(cid, target.getKey());
            if (old == null) {
                AppDatabase.get().getHistoryDao().insertOrUpdate(target);
                ++add;
            } else if (saveTime > old.getCreateTime()) {
                old.setVodName(target.getVodName());
                old.setVodPic(target.getVodPic());
                old.setVodRemarks(target.getVodRemarks());
                old.setPosition(position);
                old.setDuration(duration);
                old.setCreateTime(saveTime);
                AppDatabase.get().getHistoryDao().insertOrUpdate(old);
                ++update;
            }
        }
        return new int[]{add, update};
    }

    private static int[] pullFavorites(JSONObject remote, int cid) {
        int add = 0, update = 0;
        for (Entry entry : sort(remote)) {
            JSONObject item = entry.item;
            long saveTime = entry.time();
            Keep target = new Keep();
            target.setKey(entry.source.concat(AppDatabase.SYMBOL).concat(entry.id));
            target.setSiteName(siteName(entry.source, item.optString("source_name")));
            target.setVodName(item.optString("title"));
            target.setVodPic(item.optString("cover"));
            target.setCreateTime(saveTime);
            target.setType(0);
            String key = target.getKey().concat(AppDatabase.SYMBOL) + cid;
            Keep old = AppDatabase.get().getKeepDao().find(cid, key);
            if (old == null) {
                target.save(cid);
                ++add;
            } else if (saveTime > old.getCreateTime()) {
                old.setVodName(target.getVodName());
                old.setVodPic(target.getVodPic());
                old.setCreateTime(saveTime);
                AppDatabase.get().getKeepDao().insertOrUpdate(old);
                ++update;
            }
        }
        return new int[]{add, update};
    }

    /* ---------- 推送：本机 -> 站点 ---------- */

    private static String doPush() throws Exception {
        check();
        int[] records = pushRecords(MoonApi.playRecords());
        int[] favorites = pushFavorites(MoonApi.favorites());
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return summary(records, favorites);
    }

    private static String doSync() throws Exception {
        check();
        JSONObject remoteRecords = MoonApi.playRecords();
        JSONObject remoteFavorites = MoonApi.favorites();
        int cid = VodConfig.getCid();
        int[] down = pullRecords(remoteRecords, cid);
        int[] keepDown = pullFavorites(remoteFavorites, cid);
        int[] up = pushRecords(remoteRecords);
        int[] keepUp = pushFavorites(remoteFavorites);
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return join(down, keepDown, up, keepUp);
    }

    private static int[] pushRecords(JSONObject remote) {
        int add = 0, update = 0;
        for (History item : AppDatabase.get().getHistoryDao().findAll()) {
            String source = item.getSiteKey();
            String id = item.getVodId();
            if (source.isEmpty() || id.isEmpty()) continue;
            String name = source.concat("+").concat(id);
            long saveTime = item.getCreateTime();
            JSONObject old = remote.optJSONObject(name);
            try {
                JSONObject record = new JSONObject();
                record.put("title", item.getVodName());
                record.put("source_name", siteName(source, ""));
                record.put("cover", item.getVodPic());
                record.put("index", index(item.getVodRemarks()));
                record.put("total_episodes", old == null ? 1 : old.optInt("total_episodes", 1));
                record.put("play_time", Math.max(item.getPosition(), 0) / 1000);
                record.put("total_time", Math.max(item.getDuration(), 0) / 1000);
                record.put("save_time", saveTime);
                record.put("search_title", "");
                record.put("remarks", item.getVodRemarks());
                MoonApi.saveRecord(name, record);
                if (old == null) ++add;
                else ++update;
            } catch (Exception e) {
                Log.w(TAG, "Push record failed " + name, e);
            }
        }
        return new int[]{add, update};
    }

    private static int[] pushFavorites(JSONObject remote) {
        int add = 0, update = 0;
        for (Keep item : AppDatabase.get().getKeepDao().getVod()) {
            String source = item.getSiteKey();
            String id = item.getVodId();
            if (source.isEmpty() || id.isEmpty()) continue;
            String name = source.concat("+").concat(id);
            JSONObject old = remote.optJSONObject(name);
            try {
                JSONObject favorite = new JSONObject();
                favorite.put("source_name", safe(siteName(source, item.getSiteName())));
                favorite.put("total_episodes", old == null ? 1 : old.optInt("total_episodes", 1));
                favorite.put("title", item.getVodName());
                favorite.put("year", "");
                favorite.put("cover", item.getVodPic());
                favorite.put("save_time", item.getCreateTime());
                favorite.put("search_title", "");
                favorite.put("origin", "vod");
                MoonApi.saveFavorite(name, favorite);
                if (old == null) ++add;
                else ++update;
            } catch (Exception e) {
                Log.w(TAG, "Push favorite failed " + name, e);
            }
        }
        return new int[]{add, update};
    }

    /* ---------- 工具 ---------- */

    private static Iterator<String> keys(JSONObject object) {
        return object == null ? Collections.emptyIterator() : object.keys();
    }

    private static String[] split(String value) {
        if (value == null) return null;
        int index = value.indexOf('+');
        if (index <= 0 || index == value.length() - 1) return null;
        return new String[]{value.substring(0, index), value.substring(index + 1)};
    }

    private static String mark(int index, int total) {
        return total > 1 && index > 0 ? "第" + index + "集" : "";
    }

    private static int index(String text) {
        if (text == null) return 1;
        Matcher matcher = INDEX.matcher(text);
        return matcher.find() ? Math.max(Integer.parseInt(matcher.group(1)), 1) : 1;
    }

    private static String siteName(String key, String fallback) {
        String name = VodConfig.get().getSite(key).getName();
        if (name == null || name.isEmpty()) name = fallback;
        return name == null || name.isEmpty() ? key : name;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String summary(int[] records, int[] favorites) {
        return ResUtil.getString(R.string.moontv_summary, records[0] + records[1], favorites[0] + favorites[1], records[0] + favorites[0], records[1] + favorites[1]);
    }

    private static String join(int[] downRecords, int[] downFavorites, int[] upRecords, int[] upFavorites) {
        return ResUtil.getString(R.string.moontv_summary_sync,
                downRecords[0] + downRecords[1] + downFavorites[0] + downFavorites[1],
                upRecords[0] + upRecords[1] + upFavorites[0] + upFavorites[1]);
    }

    private static void refresh() {
        App.post(() -> {
            RefreshEvent.history();
            RefreshEvent.keep();
        });
    }
}

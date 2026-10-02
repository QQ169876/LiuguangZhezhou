package com.fongmi.android.tv.moontv;

import android.util.Log;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Constant;
import com.github.catvod.utils.Prefers;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SyncStatus;
import com.fongmi.android.tv.webdav.SyncManager;
import com.fongmi.android.tv.sync.Owner;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final String BASE_RECORD = "moontv_base_record";
    private static final String BASE_FAVORITE = "moontv_base_favorite";
    private static final String TOMB_RECORD = "moontv_tomb_record";
    private static final String TOMB_FAVORITE = "moontv_tomb_favorite";

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static volatile boolean writing = false;
    private static final AtomicInteger failures = new AtomicInteger();
    private static final int MAX_FAIL = 3;
    private static ScheduledFuture<?> future;

    public interface Listener {
        void done(boolean success, String message);
    }

    public static void boot() {
        post(20);
    }

    /**
     * 本机数据有变动（播放进度、收藏）时调一下，稍后自动做一次双向同步。
     * 同步过程中自己写库不会再触发，避免来回打转。
     */
    public static void touch() {
        if (writing || SyncManager.isBusy()) return; // 别和 WebDAV 同步互相打架
        post(20);
    }

    public static boolean isWriting() {
        return writing;
    }

    private static synchronized void post(long delaySeconds) {
        cancel();
        if (!MoonSetting.isSyncable() || !MoonSetting.isAuto()) return;
        if (MoonSetting.isSwitch()) return; // 目标换过还没选方向，先不动数据
        future = Task.scheduler().schedule(MoonSync::silent, delaySeconds, TimeUnit.SECONDS);
    }

    private static synchronized void cancel() {
        if (future != null) future.cancel(false);
        future = null;
    }

    private static void silent() {
        if (!MoonSetting.isSyncable() || !MoonSetting.isAuto()) return;
        if (System.currentTimeMillis() - MoonSetting.getLast() < TimeUnit.MINUTES.toMillis(2)) {
            post(60); // 刚同步过，改到一分钟后补一次，别把这次的改动丢掉
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            post(30);
            return;
        }
        SyncStatus.begin(R.string.sync_status_moontv); // 让用户在右下角看见这一趟在跑
        DebugLog.d("Sync", "影视站同步开始");
        try {
            guarded(MoonSync::doSync);
            failures.set(0);
            DebugLog.d("Sync", "影视站同步完成");
            SyncStatus.finish(R.string.sync_status_moontv, true);
        } catch (Throwable e) {
            Log.w(TAG, "Auto sync failed", e);
            DebugLog.d("Sync", "影视站同步失败 " + e);
            onFail();
            SyncStatus.finish(R.string.sync_status_moontv, false);
        } finally {
            busy.set(false);
        }
    }

    /**
     * 连续连不上就别再闷头重试：关掉自动同步并提示一声，
     * 让用户自己对一下站点地址、账号，确认没问题再手动开。
     */
    private static synchronized void onFail() {
        if (failures.incrementAndGet() < MAX_FAIL) return;
        failures.set(0);
        if (!MoonSetting.isAuto()) return;
        MoonSetting.putAuto(false);
        cancel();
        App.post(() -> Notify.show(R.string.moontv_auto_stop));
    }

    private static String guarded(Action action) throws Exception {
        writing = true;
        try {
            return action.run();
        } finally {
            writing = false;
        }
    }

    /**
     * 换了站点或账号：把这次目标留下的基线和墓碑作废，
     * 免得拿旧站点的记录去判断新站点上谁删了谁、谁新谁旧。
     * 每个账号各有一份账本，这里只动当前这个账号的，别的账号不受影响。
     */
    public static void resetBase() {
        Prefers.put(baseRecord(), "");
        Prefers.put(baseFavorite(), "");
        saveTomb(tombRecord(), new JSONObject());
        saveTomb(tombFavorite(), new JSONObject());
    }

    /* ---------- 账号归属：这条数据是哪个账号的 ---------- */

    /**
     * 登录过多个账号时，本机库里可能混着不同账号拉下来的记录。
     * 归属账本记着每条数据属于哪个账号（详见 sync/Owner）：
     * 推的时候只推归当前账号的，拉下来的归当前账号；
     * 站点上删掉的也只删归当前账号的那几条，别的账号的留在本地不动。
     */
    private static String me() {
        return Owner.scope(Owner.MOON);
    }

    private static JSONObject ownerRecord() {
        return Owner.load(Owner.MOON, Owner.RECORD);
    }

    private static JSONObject ownerFavorite() {
        return Owner.load(Owner.MOON, Owner.FAVORITE);
    }

    private static String baseRecord() {
        return Owner.pref(Owner.MOON_BASE[Owner.RECORD], Owner.MOON);
    }

    private static String baseFavorite() {
        return Owner.pref(Owner.MOON_BASE[Owner.FAVORITE], Owner.MOON);
    }

    private static String tombRecord() {
        return Owner.pref(Owner.MOON_TOMB[Owner.RECORD], Owner.MOON);
    }

    private static String tombFavorite() {
        return Owner.pref(Owner.MOON_TOMB[Owner.FAVORITE], Owner.MOON);
    }

    /** 墓碑要记在这条数据所属账号的账本上：别人的账号现在没登录，等到再登上去那天照着账本删 */
    private static String tombRecord(String scope) {
        return TOMB_RECORD.concat(".").concat(Owner.hash(scope));
    }

    private static String tombFavorite(String scope) {
        return TOMB_FAVORITE.concat(".").concat(Owner.hash(scope));
    }

    /** 这条数据在影视站属于哪个账号；空 = 本机自己看的，还没归到哪个账号 */
    private static String ownerOf(JSONObject owner, String key) {
        return owner.optString(key, "");
    }

    /** 别的账号的数据：当前账号不该碰 */
    private static boolean foreign(String who) {
        return Owner.foreign(Owner.MOON, who);
    }

    private static boolean foreign(JSONObject owner, String key) {
        return foreign(ownerOf(owner, key));
    }

    /** 登记归属：这条数据在影视站属于当前账号 */
    private static void mark(JSONObject owner, String key) {
        Owner.mark(Owner.MOON, owner, key);
    }

    public static void pull(Listener listener) {
        DebugLog.d("Sync", "影视站手动拉取(云端覆盖本机)");
        Task.execute(() -> notify(listener, MoonSync::doPullOverwrite));
    }

    public static void push(Listener listener) {
        DebugLog.d("Sync", "影视站手动上传(本机覆盖云端)");
        Task.execute(() -> notify(listener, MoonSync::doPushOverwrite));
    }

    /** 退出应用时调一次，把这次的改动带上去 */
    public static void exit() {
        if (!MoonSetting.isSyncable() || !MoonSetting.isAuto()) return;
        if (MoonSetting.isSwitch()) return; // 目标换过还没选方向，退出时也别动数据
        Task.execute(() -> {
            if (!busy.compareAndSet(false, true)) return;
            try {
                guarded(MoonSync::doSync);
            } catch (Throwable e) {
                Log.w(TAG, "Exit sync failed", e);
            } finally {
                busy.set(false);
            }
        });
    }

    public static void sync(Listener listener) {
        DebugLog.d("Sync", "影视站手动同步(双向合并)");
        Task.execute(() -> notify(listener, MoonSync::doSync));
    }

    private static void notify(Listener listener, Action action) {
        if (!busy.compareAndSet(false, true)) {
            done(listener, false, ResUtil.getString(R.string.moontv_busy));
            return;
        }
        String message;
        try {
            message = guarded(action);
        } catch (Throwable e) {
            Log.w(TAG, "MoonTV action failed", e);
            String error = ResUtil.getString(R.string.moontv_fail) + " " + describe(e);
            onFail();
            done(listener, false, error);
            return;
        } finally {
            busy.set(false);
        }
        failures.set(0);
        String result = message;
        done(listener, true, result);
    }


    /** 回调兜一层：拿到结果的那一刻监听器可能已经随页面没了，别让这里把整个 App 带崩 */
    private static void done(Listener listener, boolean success, String message) {
        if (listener == null) return;
        App.post(() -> listener.done(success, message));
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

    /**
     * 拉取 = 「仅使用站点数据」：本机观看记录与收藏先清空，再用站点数据整体覆盖。
     */
    private static String doPullOverwrite() throws Exception {
        check();
        Owner.touch();
        int cid = VodConfig.getCid();
        JSONObject records = MoonApi.playRecords();
        JSONObject favorites = MoonApi.favorites();
        DebugLog.d("Sync", "影视站数据已下载 rec=" + records.length() + " fav=" + favorites.length());
        JSONObject ownerRecord = ownerRecord();
        JSONObject ownerFavorite = ownerFavorite();
        pruneTomb();
        dropAlive();
        applyTomb(records, favorites);
        Map<String, long[]> skip = skipSnapshot();
        AppDatabase.get().getHistoryDao().delete(cid);
        AppDatabase.get().getKeepDao().delete(cid); // 只清当前源的收藏，别的源不受影响
        int[] gotRecords = pullRecords(records, cid, skip, ownerRecord);
        int[] gotFavorites = pullFavorites(favorites, cid, ownerFavorite);
        DebugLog.d("Sync", "影视站本地库写入完成");
        Owner.save(Owner.MOON, Owner.RECORD, ownerRecord); // 拉下来的都归当前账号
        Owner.save(Owner.MOON, Owner.FAVORITE, ownerFavorite);
        saveBase(records, favorites, null, null);
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return ResUtil.getString(R.string.moontv_summary_pull, gotRecords[0] + gotRecords[1], gotFavorites[0] + gotFavorites[1]);
    }

    /**
     * 上传 = 「用本机数据覆盖站点」：站点上有、本机没有的条目会在站点侧删掉。
     */
    private static String doPushOverwrite() throws Exception {
        check();
        Owner.touch();
        JSONObject records = MoonApi.playRecords();
        JSONObject favorites = MoonApi.favorites();
        DebugLog.d("Sync", "影视站数据已下载 rec=" + records.length() + " fav=" + favorites.length());
        dedupe();
        dropAlive();
        applyTomb(records, favorites);
        Set<String> localRecords = localRecordKeys();
        Set<String> localFavorites = localKeepKeys();
        int delRecords = deleteRemote(diff(scopedRemote(records, localRecords), localRecords), false);
        int delFavorites = deleteRemote(diff(scopedRemote(favorites, localFavorites), localFavorites), true);
        // 手动「上传」= 明确要求把本机这份搬到当前账号上，所以不看原来的归属，搬完统一归当前账号
        int[] upRecords = pushRecords(records, true);
        int[] upFavorites = pushFavorites(favorites, true);
        DebugLog.d("Sync", "影视站上传写库完成");
        saveBase(records, favorites, localRecords, localFavorites);
        // 墓碑留着：删过的东西不能再被别的设备补回来（站点条目时间比删除时刻新才会复活）
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return ResUtil.getString(R.string.moontv_summary_push, upRecords[0] + upRecords[1], upFavorites[0] + upFavorites[1], delRecords + delFavorites);
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

    private static int[] pullRecords(JSONObject remote, int cid, JSONObject owner) {
        return pullRecords(remote, cid, null, owner);
    }

    /**
     * @param skip 本机清空历史前抄下来的片头片尾（source+id → {片头, 片尾}），站点没存时用本机这份兜底
     * @param owner 拉下来的条目登记归属：这条数据是从当前账号来的
     */
    private static int[] pullRecords(JSONObject remote, int cid, Map<String, long[]> skip, JSONObject owner) {
        int add = 0, update = 0;
        List<Entry> items = sort(remote);
        JSONObject tomb = loadTomb(tombRecord());
        for (int i = 0; i < items.size(); i++) {
            Entry entry = items.get(i);
            JSONObject item = entry.item;
            String name = entry.source.concat("+").concat(entry.id);
            if (dead(tomb, name, entry.time())) continue; // 本机删过且站点没更新，别再拉回来
            mark(owner, name);
            long saveTime = visible(entry.time(), i);
            long duration = Math.round(item.optDouble("total_time", 0) * 1000);
            long position = Math.round(item.optDouble("play_time", 0) * 1000);
            long opening = Math.round(item.optDouble("opening", 0) * 1000);
            long ending = Math.round(item.optDouble("ending", 0) * 1000);
            long[] local = skip == null ? null : skip.get(entry.source.concat("+").concat(entry.id));
            if (opening <= 0 && local != null) opening = local[0];
            if (ending <= 0 && local != null) ending = local[1];
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
            if (opening > 0) target.setOpening(opening);
            if (ending > 0) target.setEnding(ending);
            History old = AppDatabase.get().getHistoryDao().find(cid, target.getKey());
            if (old == null) old = latest(AppDatabase.get().getHistoryDao().findByName(cid, target.getVodName()));
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
                if (opening > 0) old.setOpening(opening);
                if (ending > 0) old.setEnding(ending);
                AppDatabase.get().getHistoryDao().insertOrUpdate(old);
                ++update;
            }
        }
        return new int[]{add, update};
    }

    private static int[] pullFavorites(JSONObject remote, int cid, JSONObject owner) {
        int add = 0, update = 0;
        JSONObject tomb = loadTomb(tombFavorite());
        for (Entry entry : sort(remote)) {
            JSONObject item = entry.item;
            String name = entry.source.concat("+").concat(entry.id);
            if (dead(tomb, name, entry.time())) continue; // 本机删过且站点没更新，别再拉回来
            mark(owner, name);
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
            if (old == null) old = latest(AppDatabase.get().getKeepDao().findByVodName(target.getVodName()));
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

    /**
     * 双向 = 两边互相合并：谁的时间新用谁的；一端删掉的，另一端也跟着删。
     */
    private static String doSync() throws Exception {
        check();
        Owner.touch();
        JSONObject records = MoonApi.playRecords();
        JSONObject favorites = MoonApi.favorites();
        DebugLog.d("Sync", "影视站数据已下载 rec=" + records.length() + " fav=" + favorites.length());
        int cid = VodConfig.getCid();
        JSONObject ownerRecord = ownerRecord();
        JSONObject ownerFavorite = ownerFavorite();
        Set<String> remoteRecords = keysOf(records);
        Set<String> remoteFavorites = keysOf(favorites);
        int removed = 0, removedUp = 0;
        if (hasBase()) {
            removed += deleteLocalHistory(diff(loadSet(baseRecord()), remoteRecords), cid, ownerRecord);
            removed += deleteLocalKeep(diff(loadSet(baseFavorite()), remoteFavorites), ownerFavorite);
        }
        pruneTomb();
        dropAlive();
        int[] tombed = applyTomb(records, favorites);
        removedUp += tombed[0] + tombed[1];
        int[] down = pullRecords(records, cid, ownerRecord);
        int[] keepDown = pullFavorites(favorites, cid, ownerFavorite);
        DebugLog.d("Sync", "影视站下拉写库完成");
        Owner.save(Owner.MOON, Owner.RECORD, ownerRecord); // 站点拉下来的归当前账号
        Owner.save(Owner.MOON, Owner.FAVORITE, ownerFavorite);
        int[] clean = dedupe();
        // 基线只记当前账号这一份：别的账号的条目不该被算进「站点上少了什么」的判断里
        Set<String> localRecords = mineOnly(localRecordKeys(), ownerRecord);
        Set<String> localFavorites = mineOnly(localKeepKeys(), ownerFavorite);
        int[] up = pushRecords(records, false);
        int[] keepUp = pushFavorites(favorites, false);
        DebugLog.d("Sync", "影视站上传完成");
        saveBase(records, favorites, localRecords, localFavorites);
        pruneTomb();
        MoonSetting.putLast(System.currentTimeMillis());
        refresh();
        return join(down, keepDown, up, keepUp, clean, removed, removedUp);
    }

    /* ---------- 删除同步：记墓碑 + 比时间，谁在后听谁的 ---------- */

    /**
     * 本机删掉的历史 / 收藏记一笔墓碑：键是站点 key，值是删除时刻。
     * 墓碑立刻往站点发一次删除，不用等下一次自动同步，免得别的设备在这空档又补回来。
     */
    public static void markDeleted(String localKey, boolean favorite) {
        String siteKey = toSiteKey(localKey);
        if (siteKey == null) return;
        String who = ownerOf(favorite ? ownerFavorite() : ownerRecord(), siteKey);
        boolean mine = !foreign(who); // 没归属的就是本机自己看的，算当前账号的
        // 墓碑记在这条数据所属账号的账本上：别的账号现在没登录，先记账，等下次同步到那个账号时再删
        String pref = mine ? (favorite ? tombFavorite() : tombRecord()) : (favorite ? tombFavorite(who) : tombRecord(who));
        JSONObject map = loadTomb(pref);
        try {
            map.put(siteKey, System.currentTimeMillis());
        } catch (Exception ignored) {
            return;
        }
        saveTomb(pref, map);
        if (mine) flushTomb(siteKey, favorite);
    }

    /**
     * 整批清空（清观看记录与收藏、清除数据）前先记墓碑并通知站点删除。
     * 以前这种批量删除不写墓碑，站点上的旧记录原封不动，下一次同步就又被拉回来了。
     * 必须在真正删库之前调用，否则就取不到要删哪些了。
     */
    public static void tombstoneLocal() {
        if (!MoonSetting.isSyncable()) return;
        int cid = VodConfig.getCid();
        JSONObject ownerRecord = ownerRecord();
        JSONObject ownerFavorite = ownerFavorite();
        Map<String, List<String>> recordKeys = new HashMap<>();
        Map<String, List<String>> keepKeys = new HashMap<>();
        for (History item : AppDatabase.get().getHistoryDao().findByCid(cid)) {
            if (item.getSiteKey().isEmpty() || item.getVodId().isEmpty()) continue;
            group(recordKeys, ownerRecord, siteKey(item));
        }
        for (Keep item : AppDatabase.get().getKeepDao().getVodByCid(cid)) {
            if (item.getSiteKey().isEmpty() || item.getVodId().isEmpty()) continue;
            group(keepKeys, ownerFavorite, siteKey(item));
        }
        if (recordKeys.isEmpty() && keepKeys.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<String>> entry : recordKeys.entrySet()) {
            writeTomb(tombRecord(entry.getKey()), entry.getValue(), now);
        }
        for (Map.Entry<String, List<String>> entry : keepKeys.entrySet()) {
            writeTomb(tombFavorite(entry.getKey()), entry.getValue(), now);
        }
        List<String> mineRecords = recordKeys.get(me());
        List<String> mineFavorites = keepKeys.get(me());
        if (mineRecords == null) mineRecords = new ArrayList<>();
        if (mineFavorites == null) mineFavorites = new ArrayList<>();
        List<String> upRecords = mineRecords;
        List<String> upFavorites = mineFavorites;
        Task.execute(() -> { // 只有当前账号的才现在删；别的账号的等登录那个账号时按墓碑删
            int count = 0;
            for (String key : upRecords) {
                if (count++ >= 300) break;
                try {
                    deleteOne(key, false);
                } catch (Throwable ignored) {
                }
            }
            count = 0;
            for (String key : upFavorites) {
                if (count++ >= 300) break;
                try {
                    deleteOne(key, true);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 按归属账号分组：清空时要分清楚哪些是当前账号站点上的，哪些是别的账号的 */
    private static void group(Map<String, List<String>> map, JSONObject owner, String key) {
        String who = ownerOf(owner, key);
        if (who.isEmpty()) who = me();
        List<String> list = map.get(who);
        if (list == null) {
            list = new ArrayList<>();
            map.put(who, list);
        }
        list.add(key);
    }

    private static void writeTomb(String pref, List<String> keys, long time) {
        if (keys.isEmpty()) return;
        JSONObject tomb = loadTomb(pref);
        for (String key : keys) {
            try {
                tomb.put(key, time);
            } catch (Exception ignored) {
            }
        }
        saveTomb(pref, tomb);
    }

    private static void flushTomb(String siteKey, boolean favorite) {
        if (!MoonSetting.isSyncable()) return;
        Task.execute(() -> {
            try {
                purgeLocal(siteKey, favorite); // 本机同 key 的残留先清掉，免得下次同步又被推回站点
                deleteOne(siteKey, favorite);
                if (stillThere(siteKey, favorite)) { // 站点写库有延迟，复查一次再补一刀
                    Thread.sleep(1500);
                    deleteOne(siteKey, favorite);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Flush delete failed " + siteKey, e);
            }
        });
    }

    /** 同一部片在本机可能还留着别的条目（重复项、换过点播配置留下的旧 cid 记录），一起清掉 */
    private static void purgeLocal(String siteKey, boolean favorite) {
        if (favorite) {
            for (Keep item : AppDatabase.get().getKeepDao().findAll()) {
                if (item.getType() != 0) continue;
                if (!siteKey.equals(siteKey(item))) continue;
                AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
            }
        } else {
            for (History item : AppDatabase.get().getHistoryDao().findAll()) {
                if (!siteKey.equals(siteKey(item))) continue;
                AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
                AppDatabase.get().getTrackDao().delete(item.getKey());
            }
        }
    }

    private static boolean stillThere(String siteKey, boolean favorite) {
        try {
            return favorite ? MoonApi.hasFavorite(siteKey) : MoonApi.hasRecord(siteKey);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void deleteOne(String key, boolean favorite) throws Exception {
        if (favorite) MoonApi.deleteFavorite(key);
        else MoonApi.deleteRecord(key);
    }

    /**
     * 把墓碑用到这次拉下来的站点数据上：
     * 站点那条的时间不比删除时刻新 → 说明是「删在前、记录在后面补上来的」，站点删掉、本机也不拉回；
     * 站点那条比删除时刻新 → 说明删除之后又有人看过，撤掉墓碑，按正常记录拉回来。
     *
     * @return {删掉的观看记录数, 删掉的收藏数}
     */
    private static int[] applyTomb(JSONObject records, JSONObject favorites) {
        return new int[]{applyTomb(records, tombRecord(), false), applyTomb(favorites, tombFavorite(), true)};
    }

    private static int applyTomb(JSONObject remote, String pref, boolean favorite) {
        JSONObject tomb = loadTomb(pref);
        if (tomb.length() == 0) return 0;
        JSONObject next = new JSONObject();
        int removed = 0;
        for (String name : keyList(tomb)) {
            long dead = tomb.optLong(name, 0);
            JSONObject item = remote == null ? null : remote.optJSONObject(name);
            if (item != null && item.optLong("save_time", 0) > dead) continue; // 站点更新，撤墓碑
            if (item != null) {
                try {
                    deleteOne(name, favorite);
                    remote.remove(name);
                    ++removed;
                } catch (Throwable e) {
                    Log.w(TAG, "Tomb delete failed " + name, e);
                }
            }
            try {
                next.put(name, dead); // 站点已经没有的，墓碑先留着防别的设备再补回来
            } catch (Exception ignored) {
            }
        }
        saveTomb(pref, next);
        return removed;
    }

    /** 本机又看了 / 又收藏了这部（时间比墓碑新），墓碑作废 */
    private static void dropAlive() {
        dropAlive(tombRecord(), false);
        dropAlive(tombFavorite(), true);
    }

    private static void dropAlive(String pref, boolean favorite) {
        JSONObject tomb = loadTomb(pref);
        if (tomb.length() == 0) return;
        JSONObject owner = favorite ? ownerFavorite() : ownerRecord();
        List<String> alive = new ArrayList<>();
        if (favorite) {
            for (Keep item : AppDatabase.get().getKeepDao().findAll()) {
                if (item.getType() != 0) continue;
                if (foreign(owner, siteKey(item))) continue; // 别的账号的记录不算「又看/又收藏了」
                if (alive(tomb, siteKey(item), item.getCreateTime())) alive.add(siteKey(item));
            }
        } else {
            for (History item : AppDatabase.get().getHistoryDao().findAll()) {
                if (foreign(owner, siteKey(item))) continue;
                if (alive(tomb, siteKey(item), item.getCreateTime())) alive.add(siteKey(item));
            }
        }
        for (String key : alive) tomb.remove(key);
        if (!alive.isEmpty()) saveTomb(pref, tomb);
    }

    /** 站点这条记录的时间不比删除时刻新 → 已经被本机删过，不该再拉回来 */
    private static boolean dead(JSONObject tomb, String key, long time) {
        long dead = tomb.optLong(key, 0);
        return dead > 0 && time <= dead;
    }

    private static boolean alive(JSONObject tomb, String key, long time) {
        long dead = tomb.optLong(key, 0);
        return dead > 0 && time > dead;
    }

    /** 墓碑留 90 天：删过的记录得压得住，别的设备才补不回来 */
    private static void pruneTomb() {
        pruneTomb(tombRecord());
        pruneTomb(tombFavorite());
    }

    private static void pruneTomb(String pref) {
        JSONObject tomb = loadTomb(pref);
        if (tomb.length() == 0) return;
        long expire = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90);
        JSONObject next = new JSONObject();
        for (String name : keyList(tomb)) {
            long dead = tomb.optLong(name, 0);
            if (dead >= expire) {
                try {
                    next.put(name, dead);
                } catch (Exception ignored) {
                }
            }
        }
        if (next.length() != tomb.length()) saveTomb(pref, next);
    }

    private static JSONObject loadTomb(String pref) {
        String text = Prefers.getString(pref);
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
            JSONObject result = new JSONObject();
            try { // 老版本存的是数组，按「刚删的」处理
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    String key = array.optString(i, "");
                    if (!key.isEmpty()) result.put(key, System.currentTimeMillis());
                }
            } catch (Exception e) {
                return new JSONObject();
            }
            return result;
        }
    }

    private static void saveTomb(String pref, JSONObject map) {
        Prefers.put(pref, map.toString());
    }

    private static List<String> keyList(JSONObject object) {
        List<String> result = new ArrayList<>();
        for (Iterator<String> it = keys(object); it.hasNext(); ) result.add(it.next());
        return result;
    }

    private static int deleteRemote(Set<String> siteKeys, boolean favorite) {
        int count = 0;
        for (String key : siteKeys) {
            try {
                if (favorite) MoonApi.deleteFavorite(key);
                else MoonApi.deleteRecord(key);
                ++count;
            } catch (Throwable e) {
                Log.w(TAG, "Remote delete failed " + key, e);
            }
        }
        return count;
    }

    private static int deleteLocalHistory(Set<String> siteKeys, int cid, JSONObject owner) {
        if (siteKeys.isEmpty()) return 0;
        int count = 0;
        for (History item : AppDatabase.get().getHistoryDao().findAll()) {
            if (item.getCid() != cid) continue;
            String name = siteKey(item);
            if (!siteKeys.contains(name)) continue;
            if (foreign(owner, name)) continue; // 站点上没了的是当前账号的，别的账号那份留在本地
            AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
            AppDatabase.get().getTrackDao().delete(item.getKey());
            ++count;
        }
        return count;
    }

    private static int deleteLocalKeep(Set<String> siteKeys, JSONObject owner) {
        if (siteKeys.isEmpty()) return 0;
        int count = 0;
        for (Keep item : AppDatabase.get().getKeepDao().getVodByCid(VodConfig.getCid())) {
            String name = siteKey(item);
            if (!siteKeys.contains(name)) continue;
            if (foreign(owner, name)) continue;
            AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
            ++count;
        }
        return count;
    }

    private static String siteKey(History item) {
        return item.getSiteKey().concat("+").concat(item.getVodId());
    }

    private static String siteKey(Keep item) {
        return item.getSiteKey().concat("+").concat(item.getVodId());
    }

    private static String toSiteKey(String localKey) {
        if (localKey == null) return null;
        String[] parts = localKey.split(AppDatabase.SYMBOL);
        return parts.length < 2 ? null : parts[0].concat("+").concat(parts[1]);
    }

    /* ---------- 基线：记住上次同步时站点上有哪些条目 ---------- */

    private static boolean hasBase() {
        return !Prefers.getString(baseRecord()).isEmpty() || !Prefers.getString(baseFavorite()).isEmpty();
    }

    private static Set<String> keysOf(JSONObject remote) {
        Set<String> set = new HashSet<>();
        for (Iterator<String> it = keys(remote); it.hasNext(); ) set.add(it.next());
        return set;
    }

    private static Set<String> localRecordKeys() {
        return localRecordKeys(VodConfig.getCid());
    }

    private static Set<String> localRecordKeys(int cid) {
        Set<String> set = new HashSet<>();
        for (History item : AppDatabase.get().getHistoryDao().findByCid(cid)) {
            if (item.getSiteKey().isEmpty() || item.getVodId().isEmpty()) continue;
            set.add(siteKey(item));
        }
        return set;
    }

    private static Set<String> localKeepKeys() {
        return localKeepKeys(VodConfig.getCid());
    }

    private static Set<String> localKeepKeys(int cid) {
        Set<String> set = new HashSet<>();
        for (Keep item : AppDatabase.get().getKeepDao().getVodByCid(cid)) {
            if (item.getSiteKey().isEmpty() || item.getVodId().isEmpty()) continue;
            set.add(siteKey(item));
        }
        return set;
    }

    /** 只留归当前账号的那些（本机自己看的没归属，也算当前账号的） */
    private static Set<String> mineOnly(Set<String> keys, JSONObject owner) {
        Set<String> set = new HashSet<>();
        for (String key : keys) if (!foreign(owner, key)) set.add(key);
        return set;
    }

    /**
     * 站点上「属于本次同步范围」的条目：只看本机当前源里出现过的那些站点 key。
     * 别的源 / 别的账号留下的记录不在这个范围里，同步不碰它们，免得被当成多余数据删掉。
     */
    private static Set<String> scopedRemote(JSONObject remote, Set<String> local) {
        Set<String> sources = new HashSet<>();
        for (String key : local) {
            String[] pair = split(key);
            if (pair != null) sources.add(pair[0]);
        }
        Set<String> set = new HashSet<>();
        for (String key : keysOf(remote)) {
            String[] pair = split(key);
            if (pair == null) continue;
            if (!sources.contains(pair[0])) continue;
            set.add(key);
        }
        return set;
    }

    private static Set<String> diff(Set<String> from, Set<String> remove) {
        Set<String> set = new HashSet<>(from);
        set.removeAll(remove);
        return set;
    }

    private static void saveBase(JSONObject records, JSONObject favorites, Set<String> localRecords, Set<String> localFavorites) {
        Set<String> baseRecords = keysOf(records);
        if (localRecords != null) baseRecords.addAll(localRecords);
        Set<String> baseFavorites = keysOf(favorites);
        if (localFavorites != null) baseFavorites.addAll(localFavorites);
        saveSet(baseRecord(), baseRecords);
        saveSet(baseFavorite(), baseFavorites);
    }

    private static void clearTomb() {
        saveTomb(tombRecord(), new JSONObject());
        saveTomb(tombFavorite(), new JSONObject());
    }

    private static Set<String> loadSet(String pref) {
        Set<String> set = new HashSet<>();
        String text = Prefers.getString(pref);
        if (text.isEmpty()) return set;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) set.add(array.optString(i, ""));
        } catch (Exception ignored) {
        }
        return set;
    }

    private static void saveSet(String pref, Set<String> set) {
        JSONArray array = new JSONArray();
        for (String key : set) if (!key.isEmpty()) array.put(key);
        Prefers.put(pref, array.toString());
    }

    /**
     * 同名去重：站点上同一部片可能分布在多个源（source+id 不同），
     * 拉下来就成了几条片名一样的记录。这里按名字收成一条，只留最近的那条。
     */
    private static int[] dedupe() {
        return new int[]{dedupeHistory(), dedupeKeep()};
    }

    private static int dedupeHistory() {
        Map<String, List<History>> groups = new LinkedHashMap<>();
        for (History item : AppDatabase.get().getHistoryDao().findAll()) {
            String name = norm(item.getVodName());
            if (name.isEmpty()) continue;
            groups.computeIfAbsent(item.getCid() + "|" + name, key -> new ArrayList<>()).add(item);
        }
        int removed = 0;
        for (List<History> group : groups.values()) {
            if (group.size() < 2) continue;
            group.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
            for (int i = 1; i < group.size(); i++) {
                History dup = group.get(i);
                AppDatabase.get().getHistoryDao().delete(dup.getCid(), dup.getKey());
                AppDatabase.get().getTrackDao().delete(dup.getKey());
                ++removed;
            }
        }
        return removed;
    }

    private static int dedupeKeep() {
        Map<String, List<Keep>> groups = new LinkedHashMap<>();
        for (Keep item : AppDatabase.get().getKeepDao().getVodByCid(VodConfig.getCid())) {
            String name = norm(item.getVodName());
            if (name.isEmpty()) continue;
            groups.computeIfAbsent(item.getCid() + "|" + name, key -> new ArrayList<>()).add(item);
        }
        int removed = 0;
        for (List<Keep> group : groups.values()) {
            if (group.size() < 2) continue;
            group.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
            for (int i = 1; i < group.size(); i++) {
                Keep dup = group.get(i);
                AppDatabase.get().getKeepDao().delete(dup.getCid(), dup.getKey());
                ++removed;
            }
        }
        return removed;
    }

    /**
     * 拉取会用站点数据整体覆盖本机，先把本机的片头片尾抄一份，
     * 站点那边没存这个字段时不至于把设置弄丢。
     */
    private static Map<String, long[]> skipSnapshot() {
        Map<String, long[]> result = new HashMap<>();
        for (History item : AppDatabase.get().getHistoryDao().findByCid(VodConfig.getCid())) {
            String source = item.getSiteKey();
            String id = item.getVodId();
            if (source.isEmpty() || id.isEmpty()) continue;
            if (item.getOpening() <= 0 && item.getEnding() <= 0) continue;
            result.put(source.concat("+").concat(id), new long[]{Math.max(item.getOpening(), 0), Math.max(item.getEnding(), 0)});
        }
        return result;
    }

    private static String norm(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    private static int[] pushRecords(JSONObject remote, boolean takeover) {
        int add = 0, update = 0;
        JSONObject tomb = loadTomb(tombRecord());
        JSONObject owner = ownerRecord();
        boolean dirty = false;
        for (History item : AppDatabase.get().getHistoryDao().findByCid(VodConfig.getCid())) {
            String source = item.getSiteKey();
            String id = item.getVodId();
            if (source.isEmpty() || id.isEmpty()) continue;
            String name = source.concat("+").concat(id);
            long saveTime = item.getCreateTime();
            if (dead(tomb, name, saveTime)) continue; // 本机删过的，别再推回站点
            if (!takeover && foreign(owner, name)) continue; // 别的账号的记录，不混进当前账号
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
                record.put("opening", Math.max(item.getOpening(), 0) / 1000);
                record.put("ending", Math.max(item.getEnding(), 0) / 1000);
                record.put("save_time", saveTime);
                record.put("search_title", "");
                record.put("remarks", item.getVodRemarks());
                MoonApi.saveRecord(name, record);
                if (!me().equals(ownerOf(owner, name))) mark(owner, name);
                dirty = true;
                if (old == null) ++add;
                else ++update;
            } catch (Exception e) {
                Log.w(TAG, "Push record failed " + name, e);
            }
        }
        if (dirty) Owner.save(Owner.MOON, Owner.RECORD, owner);
        return new int[]{add, update};
    }

    private static int[] pushFavorites(JSONObject remote, boolean takeover) {
        int add = 0, update = 0;
        JSONObject tomb = loadTomb(tombFavorite());
        JSONObject owner = ownerFavorite();
        boolean dirty = false;
        for (Keep item : AppDatabase.get().getKeepDao().getVodByCid(VodConfig.getCid())) {
            String source = item.getSiteKey();
            String id = item.getVodId();
            if (source.isEmpty() || id.isEmpty()) continue;
            String name = source.concat("+").concat(id);
            if (dead(tomb, name, item.getCreateTime())) continue; // 本机删过的，别再推回站点
            if (!takeover && foreign(owner, name)) continue; // 别的账号的记录，不混进当前账号
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
                if (!me().equals(ownerOf(owner, name))) mark(owner, name);
                dirty = true;
                if (old == null) ++add;
                else ++update;
            } catch (Exception e) {
                Log.w(TAG, "Push favorite failed " + name, e);
            }
        }
        if (dirty) Owner.save(Owner.MOON, Owner.FAVORITE, owner);
        return new int[]{add, update};
    }

    /* ---------- 工具 ---------- */

    private static <T> T latest(List<T> items) {
        return items == null || items.isEmpty() ? null : items.get(0);
    }

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

    private static String summary(int[] records, int[] favorites, int[] clean) {
        String text = ResUtil.getString(R.string.moontv_summary, records[0] + records[1], favorites[0] + favorites[1], records[0] + favorites[0], records[1] + favorites[1]);
        return clean[0] + clean[1] == 0 ? text : text + "，" + ResUtil.getString(R.string.moontv_dedupe, clean[0] + clean[1]);
    }

    private static String join(int[] downRecords, int[] downFavorites, int[] upRecords, int[] upFavorites, int[] clean, int removed, int removedUp) {
        String text = ResUtil.getString(R.string.moontv_summary_sync,
                downRecords[0] + downRecords[1] + downFavorites[0] + downFavorites[1],
                upRecords[0] + upRecords[1] + upFavorites[0] + upFavorites[1]);
        if (clean[0] + clean[1] > 0) text = text + "，" + ResUtil.getString(R.string.moontv_dedupe, clean[0] + clean[1]);
        if (removed + removedUp > 0) text = text + "，" + ResUtil.getString(R.string.moontv_removed, removed + removedUp);
        return text;
    }

    private static void refresh() {
        App.post(() -> {
            RefreshEvent.history();
            RefreshEvent.keep();
        });
    }
}

package com.fongmi.android.tv.webdav;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.moontv.MoonSync;
import com.fongmi.android.tv.sync.Owner;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.ConfigCache;
import com.fongmi.android.tv.utils.CookieStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SpiderVault;
import com.fongmi.android.tv.utils.SyncStatus;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.json.JSONObject;

public class SyncManager {

    private static final String TAG = SyncManager.class.getSimpleName();
    private static final String BASELINE = "webdav-baseline.json";
    private static final int MAX_SIZE = 6 * 1024 * 1024;

    private static final int PLAY_PERIOD = 30;
    private static final long PLAY_IDLE = 60 * 1000L;

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static final AtomicInteger failures = new AtomicInteger();
    private static final AtomicLong playMark = new AtomicLong();
    private static final int MAX_FAIL = 3;
    private static ScheduledFuture<?> future;
    private static ScheduledFuture<?> playTask;

    public interface Listener {
        void done(boolean success, String message);
    }

    /**
     * 基线按「同步标识」分开存。
     * 以前全设备共用一个 webdav-baseline.json：换了个同步标识（或换服务器、换目录），
     * 三向合并拿的还是上一个标识的账，本机旧数据就被当成「云端少了」或者「本机多了」，
     * 一同步就把另一个人的记录糊进来。现在一个身份一份基线，各算各的。
     * 老文件（不带身份后缀）升级上来时自动认领为当前身份那份。
     */
    private static File getBaseline() {
        File current = Owner.baseline();
        File legacy = new File(App.get().getFilesDir(), BASELINE.concat(".json"));
        if (!current.exists() && legacy.exists() && legacy.renameTo(current)) {
            Log.i(TAG, "Baseline migrated to current identity");
        }
        return current;
    }

    public static void boot() {
        post(15);
    }

    public static void touch() {
        if (MoonSync.isWriting()) return; // 站点同步写库时不反向触发
        dirty.set(true);
        post(20);
    }

    /**
     * 播放器每秒调一次：说明还在看着，就开一个 30 秒一轮的后台同步，
     * 让播放进度跟着往前走，别的设备接着看能从这儿续上。
     * 一分钟没再跳时间就当是看完了，自己收摊。
     */
    public static synchronized void playback() {
        if (!playable()) return;
        playMark.set(System.currentTimeMillis());
        if (playTask != null && !playTask.isDone()) return;
        playTask = Task.scheduler().scheduleAtFixedRate(SyncManager::playTick, PLAY_PERIOD, PLAY_PERIOD, TimeUnit.SECONDS);
    }

    private static void playTick() {
        if (!playable()) {
            stopPlayback();
            return;
        }
        if (System.currentTimeMillis() - playMark.get() > PLAY_IDLE) {
            stopPlayback();
            return;
        }
        if (!dirty.get()) return; // 这半分钟没动静就别白跑一趟
        silent();
    }

    private static synchronized void stopPlayback() {
        if (playTask != null) playTask.cancel(false);
        playTask = null;
    }

    public static boolean isBusy() {
        return busy.get();
    }

    private static boolean playable() {
        if (!WebDavSetting.isSyncable() || !WebDavSetting.isAuto()) return false;
        return !WebDavSetting.isSwitch(); // 目标换过还没选方向，先不动数据
    }

    private static synchronized void post(long delaySeconds) {
        if (!playable()) return;
        cancel();
        future = Task.scheduler().schedule(SyncManager::silent, delaySeconds, TimeUnit.SECONDS);
    }

    private static synchronized void cancel() {
        if (future != null) future.cancel(false);
        future = null;
    }

    private static void silent() {
        if (!busy.compareAndSet(false, true)) return;
        SyncStatus.begin(R.string.sync_status_webdav); // 让用户在右下角看见这一趟在跑
        DebugLog.d("Sync", "WebDAV 同步开始");
        try {
            doSync();
            failures.set(0);
            dirty.set(false);
            DebugLog.d("Sync", "WebDAV 同步完成");
            SyncStatus.finish(R.string.sync_status_webdav, true);
        } catch (Throwable e) {
            Log.w(TAG, "Auto sync failed", e);
            DebugLog.d("Sync", "WebDAV 同步失败 " + e);
            onFail();
            SyncStatus.finish(R.string.sync_status_webdav, false);
        } finally {
            busy.set(false);
        }
    }

    /**
     * 连续连不上就别再闷头重试了：关掉自动同步并提示一声，
     * 让用户自己去设置里核对地址、账号，确认没问题再手动开。
     */
    private static synchronized void onFail() {
        if (failures.incrementAndGet() < MAX_FAIL) return;
        failures.set(0);
        if (!WebDavSetting.isAuto()) return;
        WebDavSetting.putAuto(false);
        cancel();
        stopPlayback();
        App.post(() -> Notify.show(R.string.webdav_auto_stop));
    }

    /**
     * 换了同步地址、目录或账号：作废旧基线，
     * 下次同步按本机与云端两边最新的数据重新合并。
     */
    public static void resetBase() {
        File file = getBaseline();
        if (file.exists()) file.delete();
    }

    public static void sync(Listener listener) {
        Task.execute(() -> notify(listener, () -> doSync()));
    }

    public static void push(Listener listener) {
        Task.execute(() -> notify(listener, () -> doPush()));
    }

    public static void pull(Listener listener) {
        Task.execute(() -> notify(listener, () -> doPull()));
    }

    private static void notify(Listener listener, Action action) {
        if (!busy.compareAndSet(false, true)) {
            done(listener, false, ResUtil.getString(R.string.webdav_busy));
            return;
        }
        String message;
        try {
            message = action.run();
        } catch (Throwable e) {
            Log.w(TAG, "WebDAV action failed", e);
            String error = ResUtil.getString(R.string.webdav_fail) + " " + describe(e);
            onFail();
            done(listener, false, error);
            return;
        } finally {
            busy.set(false);
        }
        failures.set(0);
        dirty.set(false);
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

    private static String describe(Throwable e) {
        Throwable cause = WebDav.root(e);
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    /* ---------- actions ---------- */

    /**
     * 本机这份要参与本次同步的数据：只带上「属于当前同步标识」的
     * （没打过归属标记的本机自己看的也算），别的同步标识的一条都不带，
     * 也不让它们出现在后面那个「本机有、云端没有就删掉」的比较里。
     *
     * @param takeover true = 明确的「用本机覆盖云端」，整份搬过去不看旧归属
     */
    private static WebDavData participate(boolean takeover, JSONObject bookRecord, JSONObject bookFavorite) {
        WebDavData data = WebDavData.create();
        Backup backup = data.getData();
        List<History> history = new ArrayList<>();
        for (History item : backup.getHistory()) {
            String name = Owner.key(item.getKey());
            if (name == null) {
                history.add(item);
            } else if (takeover || !Owner.foreign(Owner.DAV, bookRecord, name)) {
                history.add(item);
            }
        }
        List<Keep> keep = new ArrayList<>();
        for (Keep item : backup.getKeep()) {
            String name = Owner.key(item.getKey());
            if (name == null) {
                keep.add(item);
            } else if (takeover || !Owner.foreign(Owner.DAV, bookFavorite, name)) {
                keep.add(item);
            }
        }
        backup.setHistory(history);
        backup.setKeep(keep);
        return data;
    }

    /** 同步完这批数据都算进了当前同步标识的名下，记一笔 */
    private static void absorb(WebDavData data, JSONObject bookRecord, JSONObject bookFavorite) {
        for (History item : data.getData().getHistory()) {
            String name = Owner.key(item.getKey());
            if (name != null) Owner.mark(Owner.DAV, bookRecord, name);
        }
        for (Keep item : data.getData().getKeep()) {
            String name = Owner.key(item.getKey());
            if (name != null) Owner.mark(Owner.DAV, bookFavorite, name);
        }
    }

    /** 本机里属于别的同步标识的那些，同步时不碰（既不上传也不删除） */
    private static List<History> foreignHistory(JSONObject book) {
        List<History> result = new ArrayList<>();
        for (History item : AppDatabase.get().getHistoryDao().findAll()) {
            String name = Owner.key(item.getKey());
            if (name != null && Owner.foreign(Owner.DAV, book, name)) result.add(item);
        }
        return result;
    }

    private static List<Keep> foreignKeep(JSONObject book) {
        List<Keep> result = new ArrayList<>();
        for (Keep item : AppDatabase.get().getKeepDao().findAll()) {
            String name = Owner.key(item.getKey());
            if (name != null && Owner.foreign(Owner.DAV, book, name)) result.add(item);
        }
        return result;
    }

    private static String doSync() throws Exception {
        check();
        Owner.touch();
        String url = WebDavSetting.getFileUrl();
        JSONObject bookRecord = Owner.load(Owner.DAV, Owner.RECORD);
        JSONObject bookFavorite = Owner.load(Owner.DAV, Owner.FAVORITE);
        WebDavData remoteData = WebDavData.from(WebDav.get(url));
        WebDavData baseData = readBaseline();
        WebDavData localData = participate(false, bookRecord, bookFavorite);
        if (remoteData == null) remoteData = baseData == null ? WebDavData.empty() : WebDavData.from(baseData.toJson());
        WebDavData merged = merge(baseData, localData, remoteData);
        absorb(merged, bookRecord, bookFavorite); // 拉下来/推上去的都归当前同步标识
        boolean changed = apply(merged, localData);
        merged.setTime(System.currentTimeMillis());
        save(url, merged);
        Owner.save(Owner.DAV, Owner.RECORD, bookRecord);
        Owner.save(Owner.DAV, Owner.FAVORITE, bookFavorite);
        notifyDataChanged();
        if (changed) reload();
        return getSummary(merged);
    }

    private static String doPush() throws Exception {
        check();
        Owner.touch();
        JSONObject bookRecord = Owner.load(Owner.DAV, Owner.RECORD);
        JSONObject bookFavorite = Owner.load(Owner.DAV, Owner.FAVORITE);
        // 手动「上传」= 明确要求把本机这份搬到当前同步标识下，所以不看原来归属，搬完统一改归属
        WebDavData localData = participate(true, bookRecord, bookFavorite);
        absorb(localData, bookRecord, bookFavorite);
        localData.setTime(System.currentTimeMillis());
        save(WebDavSetting.getFileUrl(), localData);
        Owner.save(Owner.DAV, Owner.RECORD, bookRecord);
        Owner.save(Owner.DAV, Owner.FAVORITE, bookFavorite);
        return getSummary(localData);
    }

    /**
     * 用云端覆盖本机：只覆盖属于当前同步标识的那部分，
     * 别的同步标识留在本机的数据原样放着（删了它们等于替别人清库，也会在下一次被推回去）。
     */
    private static String doPull() throws Exception {
        check();
        Owner.touch();
        String url = WebDavSetting.getFileUrl();
        WebDavData remoteData = WebDavData.from(WebDav.get(url));
        if (remoteData == null) throw new Exception("Remote file not found");
        JSONObject bookRecord = Owner.load(Owner.DAV, Owner.RECORD);
        JSONObject bookFavorite = Owner.load(Owner.DAV, Owner.FAVORITE);
        WebDavData mine = participate(false, bookRecord, bookFavorite);
        replaceLocal(mine);
        List<History> leftHistory = keepKeys(foreignHistory(bookRecord), remoteData.getData().getHistory());
        List<Keep> leftKeep = keepKeys(foreignKeep(bookFavorite), remoteData.getData().getKeep());
        insertAll(remoteData);
        if (!leftHistory.isEmpty()) AppDatabase.get().getHistoryDao().insertOrUpdate(leftHistory);
        if (!leftKeep.isEmpty()) AppDatabase.get().getKeepDao().insertOrUpdate(leftKeep);
        ConfigCache.apply(remoteData.getCache());
        absorb(remoteData, bookRecord, bookFavorite); // 云端这份就是当前同步标识的
        Owner.save(Owner.DAV, Owner.RECORD, bookRecord);
        Owner.save(Owner.DAV, Owner.FAVORITE, bookFavorite);
        writeBaseline(remoteData);
        remoteData.setTime(System.currentTimeMillis());
        save(url, remoteData);
        notifyDataChanged();
        reload();
        return getSummary(remoteData);
    }

    /** 清掉本机参与本次同步的那部分（连同播放轨道），并重建配置/站点/直播 */
    private static void replaceLocal(WebDavData mine) {
        for (History item : mine.getData().getHistory()) {
            AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
            AppDatabase.get().getTrackDao().delete(item.getKey());
        }
        for (Keep item : mine.getData().getKeep()) {
            AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
        }
        AppDatabase.get().getConfigDao().delete();
        AppDatabase.get().getSiteDao().delete();
        AppDatabase.get().getLiveDao().delete();
        ConfigCache.apply(new HashMap<>());
    }

    /** 留下来不删的那些里，云端也有的就算了（主键相同会互盖），只保留云端没有的 */
    private static <T> List<T> keepKeys(List<T> left, List<?> remote) {
        Set<String> remoteKeys = new HashSet<>();
        for (Object item : remote) {
            if (item instanceof History) remoteKeys.add(((History) item).getKey());
            else if (item instanceof Keep) remoteKeys.add(baseKey(((Keep) item).getKey()));
        }
        List<T> result = new ArrayList<>();
        for (T item : left) {
            String key = item instanceof History ? ((History) item).getKey() : baseKey(((Keep) item).getKey());
            if (!remoteKeys.contains(key)) result.add(item);
        }
        return result;
    }

    private static void check() throws Exception {
        if (!WebDavSetting.isValid()) throw new Exception(ResUtil.getString(R.string.webdav_empty));
        WebDav.createFolders();
    }

    private static void save(String url, WebDavData data) throws Exception {
        String json = trim(data);
        WebDav.put(url, json);
        writeBaseline(WebDavData.from(json));
        WebDavSetting.putLast(System.currentTimeMillis());
    }

    private static String trim(WebDavData data) {
        String json = data.toJson();
        if (json.length() <= MAX_SIZE) return json;
        for (Config config : data.getData().getConfig()) config.setJson("");
        json = data.toJson();
        if (json.length() <= MAX_SIZE) return json;
        data.setCache(new HashMap<>());
        return data.toJson();
    }

    private static String getSummary(WebDavData data) {
        Backup backup = data.getData();
        return ResUtil.getString(R.string.webdav_success) + " (" + (backup.getHistory().size() + backup.getKeep().size()) + ")";
    }

    /* ---------- LAN push ---------- */

    /**
     * 只合并对方推送过来的部分（不做删除），没勾选的字段保持本机原样。
     */
    public static void applyPush(Backup data) {
        Backup local = Backup.create();
        boolean configChanged = false;
        Map<String, History> localHistory = map(local.getHistory(), SyncManager::key);
        Map<String, Keep> localKeep = map(local.getKeep(), SyncManager::key);
        Map<String, Config> localConfig = map(local.getConfig(), SyncManager::key);
        if (!data.getHistory().isEmpty()) {
            for (History item : data.getHistory()) {
                History old = localHistory.get(key(item));
                if (old != null && same(old, item)) continue;
                if (old != null) {
                    item.setCid(old.getCid());
                    History.keepSkip(old, item);
                }
                AppDatabase.get().getHistoryDao().insertOrUpdate(item);
            }
        }
        if (!data.getKeep().isEmpty()) {
            for (Keep item : data.getKeep()) {
                Keep old = localKeep.get(key(item));
                if (old != null && same(old, item)) continue;
                if (old != null) {
                    item.setKey(old.getKey());
                    item.setCid(old.getCid());
                } else if (item.getType() == 0) {
                    AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
                }
                AppDatabase.get().getKeepDao().insertOrUpdate(item);
            }
        }
        if (!data.getConfig().isEmpty()) {
            for (Config item : data.getConfig()) {
                Config old = localConfig.get(key(item));
                if (old != null && same(old, item)) continue;
                if (old != null && item.getId() == 0) item.setId(old.getId());
                AppDatabase.get().getConfigDao().insertOrUpdate(item);
                configChanged = true;
            }
        }
        if (!data.getPrefers().isEmpty()) applyPrefers(data.getPrefers(), local.getPrefers());
        if (!data.getCookies().isEmpty()) CookieStore.apply(data.getCookies());
        SpiderVault.apply(data.getSpider());
        if (configChanged) reload();
        notifyDataChanged();
    }

    /* ---------- baseline ---------- */

    private static WebDavData readBaseline() {
        File file = getBaseline();
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            return WebDavData.from(new String(buffer, 0, Math.max(read, 0), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeBaseline(WebDavData data) {
        try (FileOutputStream out = new FileOutputStream(getBaseline())) {
            out.write(data.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Unable to write baseline", e);
        }
    }

    /* ---------- merge ---------- */

    private static Backup backup(WebDavData data) {
        return data == null ? new Backup() : data.getData();
    }

    private static WebDavData merge(WebDavData base, WebDavData local, WebDavData remote) {
        WebDavData merged = WebDavData.empty();
        merged.setData(new Backup());
        merged.getData().setHistory(mergeList(backup(base).getHistory(), backup(local).getHistory(), backup(remote).getHistory(), SyncManager::key, SyncManager::newerHistory));
        merged.getData().setKeep(mergeList(backup(base).getKeep(), backup(local).getKeep(), backup(remote).getKeep(), SyncManager::key, SyncManager::newerKeep));
        merged.getData().setConfig(mergeList(backup(base).getConfig(), backup(local).getConfig(), backup(remote).getConfig(), SyncManager::key, SyncManager::newerConfig));
        merged.getData().setSite(mergeList(backup(base).getSite(), backup(local).getSite(), backup(remote).getSite(), SyncManager::key, (a, b) -> a));
        merged.getData().setLive(mergeList(backup(base).getLive(), backup(local).getLive(), backup(remote).getLive(), SyncManager::key, (a, b) -> a));
        merged.setPrefers(mergeMap(backup(base).getPrefers(), backup(local).getPrefers(), backup(remote).getPrefers()));
        merged.getData().setCookies(mergeCookies(backup(base).getCookies(), backup(local).getCookies(), backup(remote).getCookies()));
        Map<String, String> remoteCache = remote == null ? null : remote.getCache();
        Map<String, String> localCache = local == null ? null : local.getCache();
        if (remoteCache != null && !remoteCache.isEmpty()) merged.setCache(remoteCache);
        else if (localCache != null && !localCache.isEmpty()) merged.setCache(localCache);
        return merged;
    }

    private static <T> List<T> mergeList(List<T> base, List<T> local, List<T> remote, Function<T, String> key, BiFunction<T, T, T> tie) {
        Map<String, T> baseMap = map(base, key);
        Map<String, T> localMap = map(local, key);
        Map<String, T> remoteMap = map(remote, key);
        Set<String> keys = new HashSet<>(localMap.keySet());
        keys.addAll(remoteMap.keySet());
        keys.addAll(baseMap.keySet());
        List<T> result = new ArrayList<>();
        for (String name : keys) {
            T b = baseMap.get(name);
            T l = localMap.get(name);
            T r = remoteMap.get(name);
            if (l == null && r == null) continue;
            boolean localChanged = l != null && !same(b, l);
            boolean remoteChanged = r != null && !same(b, r);
            if (l == null) {
                if (b == null) result.add(r);
            } else if (r == null) {
                if (b == null) result.add(l);
            } else if (!localChanged && !remoteChanged) {
                result.add(l);
            } else if (localChanged && !remoteChanged) {
                result.add(l);
            } else if (!localChanged && remoteChanged) {
                result.add(r);
            } else {
                result.add(tie.apply(l, r));
            }
        }
        return result;
    }

    private static Map<String, ?> mergeMap(Map<String, ?> base, Map<String, ?> local, Map<String, ?> remote) {
        Set<String> keys = new HashSet<>(local.keySet());
        keys.addAll(remote.keySet());
        keys.addAll(base.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : keys) {
            if (WebDavData.localOnly(name)) continue;
            Object b = base.get(name);
            Object l = local.get(name);
            Object r = remote.get(name);
            if (l == null && r == null) continue;
            boolean localChanged = l != null && !Objects.equals(l, b);
            boolean remoteChanged = r != null && !Objects.equals(r, b);
            if (l == null) {
                if (b == null) result.put(name, r);
            } else if (r == null) {
                if (b == null) result.put(name, l);
            } else if (remoteChanged && !localChanged) {
                result.put(name, r);
            } else {
                result.put(name, l);
            }
        }
        return result;
    }

    /**
     * 扫码登录的 Cookie：谁变过就听谁的，两边都变过以本机为准。
     * 本机没扫过码（压根没有这个域名）而云端有，就直接拿来用。
     */
    private static Map<String, String> mergeCookies(Map<String, String> base, Map<String, String> local, Map<String, String> remote) {
        Set<String> keys = new HashSet<>(local.keySet());
        keys.addAll(remote.keySet());
        keys.addAll(base.keySet());
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : keys) {
            String b = base.get(name);
            String l = local.get(name);
            String r = remote.get(name);
            if (l == null && r == null) continue;
            boolean localChanged = l != null && !Objects.equals(l, b);
            boolean remoteChanged = r != null && !Objects.equals(r, b);
            if (l == null) {
                if (b == null) result.put(name, r);
            } else if (r == null) {
                if (b == null) result.put(name, l);
            } else if (remoteChanged && !localChanged) {
                result.put(name, r);
            } else {
                result.put(name, l);
            }
        }
        return result;
    }

    private static <T> Map<String, T> map(List<T> items, Function<T, String> key) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T item : items) result.put(key.apply(item), item);
        return result;
    }

    private static String key(History item) {
        return item.getKey();
    }

    private static String key(Keep item) {
        return baseKey(item.getKey());
    }

    private static String key(Config item) {
        return item.getType() + "@" + item.getUrl();
    }

    private static String key(Site item) {
        return item.getKey();
    }

    private static String key(Live item) {
        return item.getName();
    }

    private static String baseKey(String value) {
        if (value == null) return "";
        String[] parts = value.split(AppDatabase.SYMBOL);
        return parts.length >= 2 ? parts[0].concat(AppDatabase.SYMBOL).concat(parts[1]) : value;
    }

    private static History newerHistory(History a, History b) {
        History win;
        if (b.getCreateTime() != a.getCreateTime()) win = b.getCreateTime() > a.getCreateTime() ? b : a;
        else win = Math.max(b.getPosition(), 0) > Math.max(a.getPosition(), 0) ? b : a;
        History.keepSkip(win == a ? b : a, win);
        return win;
    }

    private static Keep newerKeep(Keep a, Keep b) {
        return b.getCreateTime() > a.getCreateTime() ? b : a;
    }

    private static Config newerConfig(Config a, Config b) {
        return b.getTime() > a.getTime() ? b : a;
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a instanceof History o && b instanceof History i) return same(o, i);
        if (a instanceof Keep o && b instanceof Keep i) return same(o, i);
        return json(a).equals(json(b));
    }

    private static boolean same(History a, History b) {
        int cid = b.getCid();
        try {
            b.setCid(a.getCid());
            return json(a).equals(json(b));
        } finally {
            b.setCid(cid);
        }
    }

    private static boolean same(Keep a, Keep b) {
        int cid = b.getCid();
        try {
            b.setCid(a.getCid());
            return json(a).equals(json(b));
        } finally {
            b.setCid(cid);
        }
    }

    private static String json(Object item) {
        return App.gson().toJson(item);
    }

    /* ---------- apply ---------- */

    private static boolean apply(WebDavData merged, WebDavData local) {
        Backup m = merged.getData();
        Backup o = backup(local);
        boolean configChanged = false;
        Map<String, History> localHistory = map(o.getHistory(), SyncManager::key);
        Map<String, Keep> localKeep = map(o.getKeep(), SyncManager::key);
        Map<String, Config> localConfig = map(o.getConfig(), SyncManager::key);
        Map<String, Site> localSite = map(o.getSite(), SyncManager::key);
        Map<String, Live> localLive = map(o.getLive(), SyncManager::key);
        Set<String> historyKeys = keys(m.getHistory(), SyncManager::key);
        Set<String> keepKeys = keys(m.getKeep(), SyncManager::key);
        Set<String> configKeys = keys(m.getConfig(), SyncManager::key);

        for (History item : o.getHistory()) {
            if (historyKeys.contains(key(item))) continue;
            AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
            AppDatabase.get().getTrackDao().delete(item.getKey());
        }
        for (Keep item : o.getKeep()) {
            if (keepKeys.contains(key(item))) continue;
            AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
        }
        for (Config item : o.getConfig()) {
            if (configKeys.contains(key(item))) continue;
            AppDatabase.get().getConfigDao().delete(item.getUrl(), item.getType());
            configChanged = true;
        }

        for (History item : m.getHistory()) {
            History old = localHistory.get(key(item));
            if (old != null && same(old, item)) continue;
            if (old != null) {
                item.setCid(old.getCid());
                History.keepSkip(old, item);
            }
            AppDatabase.get().getHistoryDao().insertOrUpdate(item);
        }
        for (Keep item : m.getKeep()) {
            Keep old = localKeep.get(key(item));
            if (old != null && same(old, item)) continue;
            if (old != null) {
                item.setKey(old.getKey());
                item.setCid(old.getCid());
            } else if (item.getType() == 0) {
                AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
            }
            AppDatabase.get().getKeepDao().insertOrUpdate(item);
        }
        for (Config item : m.getConfig()) {
            Config old = localConfig.get(key(item));
            if (old != null && same(old, item)) continue;
            if (old != null && item.getId() == 0) item.setId(old.getId());
            AppDatabase.get().getConfigDao().insertOrUpdate(item);
            configChanged = true;
        }
        for (Site item : m.getSite()) {
            Site old = localSite.get(key(item));
            if (old != null && same(old, item)) continue;
            AppDatabase.get().getSiteDao().insertOrUpdate(item);
        }
        for (Live item : m.getLive()) {
            Live old = localLive.get(key(item));
            if (old != null && same(old, item)) continue;
            AppDatabase.get().getLiveDao().insertOrUpdate(item);
        }
        applyPrefers(merged.getPrefers(), backup(local).getPrefers());
        CookieStore.apply(merged.getData().getCookies());
        SpiderVault.apply(merged.getData().getSpider());
        ConfigCache.apply(merged.getCache());
        return configChanged;
    }

    private static void insertAll(WebDavData data) {
        Backup backup = data.getData();
        AppDatabase.get().getSiteDao().insertOrUpdate(backup.getSite());
        AppDatabase.get().getLiveDao().insertOrUpdate(backup.getLive());
        AppDatabase.get().getConfigDao().insertOrUpdate(backup.getConfig());
        AppDatabase.get().getKeepDao().insertOrUpdate(backup.getKeep());
        AppDatabase.get().getHistoryDao().insertOrUpdate(backup.getHistory());
        for (Map.Entry<String, ?> entry : backup.getPrefers().entrySet()) {
            // 跟本机网络环境绑定的设置（route 系列等）不随云端落盘，见 WebDavData.localOnly
            if (WebDavData.localOnly(entry.getKey())) continue;
            Prefers.put(entry.getKey(), entry.getValue());
        }
        CookieStore.apply(backup.getCookies());
        SpiderVault.apply(backup.getSpider());
    }

    private static <T> Set<String> keys(List<T> items, Function<T, String> key) {
        return new HashSet<>(map(items, key).keySet());
    }

    private static void applyPrefers(Map<String, ?> target, Map<String, ?> current) {
        if (target.isEmpty()) return;
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : target.entrySet()) {
            String name = entry.getKey();
            if (WebDavData.localOnly(name)) continue;
            if (Objects.equals(current.get(name), entry.getValue())) continue;
            values.put(name, entry.getValue());
        }
        if (values.isEmpty()) return;
        for (Map.Entry<String, Object> entry : values.entrySet()) Prefers.put(entry.getKey(), entry.getValue());
    }

    /**
     * 同步把历史 / 收藏写进数据库后，通知开着的页面立刻重查。
     * 之前只有局域网推送（applyPush）发这个事件，WebDAV 同步（doSync/doPull）写完库就完了，
     * 首页的最近观看和收藏页一直显示旧数据，要重启 App 才变 —— 马先生在小米盒子上踩到（2026-10-05）。
     */
    private static void notifyDataChanged() {
        App.post(() -> {
            RefreshEvent.history();
            RefreshEvent.keep();
        });
    }

    private static void reload() {
        DebugLog.d("Sync", "同步改了配置，开始重载点播/直播/壁纸");
        App.post(() -> {
            DebugLog.d("Sync", "重载执行中");
            VodConfig.get().init().load(new Callback() {
                @Override
                public void error(String msg) {
                }
            });
            LiveConfig.get().init().load();
            WallConfig.get().init().load();
        });
    }
}

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
import com.fongmi.android.tv.utils.ConfigCache;
import com.fongmi.android.tv.utils.ResUtil;
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
import java.util.function.BiFunction;
import java.util.function.Function;

public class SyncManager {

    private static final String TAG = SyncManager.class.getSimpleName();
    private static final String BASELINE = "webdav-baseline.json";
    private static final int MAX_SIZE = 6 * 1024 * 1024;

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static ScheduledFuture<?> future;

    public interface Listener {
        void done(boolean success, String message);
    }

    private static File getBaseline() {
        return new File(App.get().getFilesDir(), BASELINE);
    }

    public static void boot() {
        post(15);
    }

    public static void touch() {
        post(20);
    }

    private static synchronized void post(long delaySeconds) {
        if (!WebDavSetting.isSyncable() || !WebDavSetting.isAuto()) return;
        cancel();
        future = Task.scheduler().schedule(SyncManager::silent, delaySeconds, TimeUnit.SECONDS);
    }

    private static synchronized void cancel() {
        if (future != null) future.cancel(false);
        future = null;
    }

    private static void silent() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            doSync();
        } catch (Throwable e) {
            Log.w(TAG, "Auto sync failed", e);
        } finally {
            busy.set(false);
        }
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
            App.post(() -> listener.done(false, ResUtil.getString(R.string.webdav_busy)));
            return;
        }
        String message;
        try {
            message = action.run();
        } catch (Throwable e) {
            Log.w(TAG, "WebDAV action failed", e);
            String error = ResUtil.getString(R.string.webdav_fail) + " " + describe(e);
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

    private static String describe(Throwable e) {
        Throwable cause = WebDav.root(e);
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    /* ---------- actions ---------- */

    private static String doSync() throws Exception {
        check();
        String url = WebDavSetting.getFileUrl();
        WebDavData remoteData = WebDavData.from(WebDav.get(url));
        WebDavData baseData = readBaseline();
        WebDavData localData = WebDavData.create();
        if (remoteData == null) remoteData = baseData == null ? WebDavData.empty() : WebDavData.from(baseData.toJson());
        WebDavData merged = merge(baseData, localData, remoteData);
        boolean changed = apply(merged, localData);
        merged.setTime(System.currentTimeMillis());
        save(url, merged);
        if (changed) reload();
        return getSummary(merged);
    }

    private static String doPush() throws Exception {
        check();
        WebDavData localData = WebDavData.create();
        localData.setTime(System.currentTimeMillis());
        save(WebDavSetting.getFileUrl(), localData);
        return getSummary(localData);
    }

    private static String doPull() throws Exception {
        check();
        String url = WebDavSetting.getFileUrl();
        WebDavData remoteData = WebDavData.from(WebDav.get(url));
        if (remoteData == null) throw new Exception("Remote file not found");
        AppDatabase.get().clearAllTables();
        insertAll(remoteData);
        ConfigCache.apply(remoteData.getCache());
        writeBaseline(remoteData);
        remoteData.setTime(System.currentTimeMillis());
        save(url, remoteData);
        reload();
        return getSummary(remoteData);
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
                if (old != null) item.setCid(old.getCid());
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
        if (configChanged) reload();
        App.post(() -> {
            RefreshEvent.history();
            RefreshEvent.keep();
        });
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
            if (name.startsWith("webdav_")) continue;
            if (name.startsWith("moontv_")) continue;
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
        if (b.getCreateTime() != a.getCreateTime()) return b.getCreateTime() > a.getCreateTime() ? b : a;
        return Math.max(b.getPosition(), 0) > Math.max(a.getPosition(), 0) ? b : a;
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
            if (old != null) item.setCid(old.getCid());
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
        for (Map.Entry<String, ?> entry : backup.getPrefers().entrySet()) Prefers.put(entry.getKey(), entry.getValue());
    }

    private static <T> Set<String> keys(List<T> items, Function<T, String> key) {
        return new HashSet<>(map(items, key).keySet());
    }

    private static void applyPrefers(Map<String, ?> target, Map<String, ?> current) {
        if (target.isEmpty()) return;
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : target.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.startsWith("webdav_")) continue;
            if (name.startsWith("moontv_")) continue;
            if (Objects.equals(current.get(name), entry.getValue())) continue;
            values.put(name, entry.getValue());
        }
        if (values.isEmpty()) return;
        for (Map.Entry<String, Object> entry : values.entrySet()) Prefers.put(entry.getKey(), entry.getValue());
    }

    private static void reload() {
        App.post(() -> {
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

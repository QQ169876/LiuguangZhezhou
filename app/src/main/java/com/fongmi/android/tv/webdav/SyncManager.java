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
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.moontv.MoonSync;
import com.fongmi.android.tv.sync.Owner;
import com.fongmi.android.tv.sync.SyncGuard;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;

public class SyncManager {

    private static final String TAG = SyncManager.class.getSimpleName();
    private static final String BASELINE = "webdav-baseline.json";
    private static final int MAX_SIZE = 6 * 1024 * 1024;

    private static final int PLAY_PERIOD = 30;
    private static final long PLAY_IDLE = 60 * 1000L;
    /** 等用户对「批量删除确认」弹窗表态的最长时间（秒）：超时按误删处理，删除不生效 */
    private static final long ANSWER_TIMEOUT = 60;

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
        try {
            doSync();
            failures.set(0);
            dirty.set(false);
            SyncStatus.finish(R.string.sync_status_webdav, true);
        } catch (Throwable e) {
            Log.w(TAG, "Auto sync failed", e);
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


    /**
     * 回调兜一层：拿到结果的那一刻监听器可能已经随页面没了，别让这里把整个 App 带崩。
     *
     * ⚠️ 2026-10-14 线上崩过一次：TV 上关掉同步对话框后弹的「有操作风险 → 从云端覆盖本机」，
     * 同步跑完往回回调时对话框 Fragment 已经分离，回调里 getString() 直接抛
     * IllegalStateException("Fragment not attached to a context")。根因在对话框那边已经修好，
     * 这里再加一道：同步结果的回调永远不许把 App 带崩，最多丢一句提示。
     */
    private static void done(Listener listener, boolean success, String message) {
        if (listener == null) return;
        App.post(() -> {
            try {
                listener.done(success, message);
            } catch (Throwable e) {
                Log.w(TAG, "Sync listener crashed", e);
            }
        });
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
            String name = Owner.key(item.getKey(), Owner.RECORD);
            if (name == null) {
                history.add(item);
            } else if (takeover || !Owner.foreign(Owner.DAV, bookRecord, name)) {
                history.add(item);
            }
        }
        List<Keep> keep = new ArrayList<>();
        for (Keep item : backup.getKeep()) {
            String name = Owner.key(item.getKey(), Owner.FAVORITE);
            if (name == null) {
                keep.add(item);
            } else if (takeover || !Owner.foreign(Owner.DAV, bookFavorite, name)) {
                keep.add(item);
            }
        }
        List<History> mergedHistory = accountHistory(history);
        List<Keep> mergedKeep = accountKeep(keep);
        backup.setHistory(mergedHistory);
        backup.setKeep(mergedKeep);
        DebugLog.d("Sync", "本机参与：记录 " + history.size() + " 条收成 " + mergedHistory.size() + " 条，收藏 " + keep.size() + " 条收成 " + mergedKeep.size() + " 条（当前源 cid=" + currentCid() + "）");
        // 源地址 Config 不上传：点播源/直播源/壁纸不参与同步，每台设备各配各的
        backup.setConfig(Backup.withoutSources(backup.getConfig()));
        return data;
    }

    /**
     * 观看记录跟着账号走，不跟点播源走（马先生 2026-11-02 的口径）：
     * 本机同一部片可能在几个点播源（cid）底下各留了一条（换源之前留下来的），
     * 这里按「站点 key + 视频 id」收成一条（留最新的那条），并挂到当前源名下——
     * 换源之后记录还是这一条，跟着账号走，不会变成另一条、也不会看不见。
     */
    private static List<History> accountHistory(List<History> items) {
        if (items == null) return new ArrayList<>();
        int cid = currentCid();
        Map<String, History> map = new LinkedHashMap<>();
        for (History item : items) {
            if (item == null) continue;
            if (cid > 0) item.cid(cid);
            String name = key(item);
            History old = map.get(name);
            map.put(name, old == null ? item : newerHistory(old, item));
        }
        return new ArrayList<>(map.values());
    }

    /** 收藏同理：跟着账号走，同一部片只留一条，挂在当前源名下 */
    private static List<Keep> accountKeep(List<Keep> items) {
        if (items == null) return new ArrayList<>();
        int cid = currentCid();
        Map<String, Keep> map = new LinkedHashMap<>();
        for (Keep item : items) {
            if (item == null) continue;
            if (cid > 0 && item.getType() == 0) item.cid(cid); // 直播收藏(type=1)的键不是「站点@@@视频」三段，别动
            String name = key(item);
            Keep old = map.get(name);
            map.put(name, old == null ? item : newerKeep(old, item));
        }
        return new ArrayList<>(map.values());
    }

    /** 当前点播源；配置还没加载出来时是 0，那一刻不动 cid，免得把记录全挂到空源上 */
    private static int currentCid() {
        try {
            return VodConfig.getCid();
        } catch (Throwable e) {
            return 0;
        }
    }


    /** 同步完这批数据都算进了当前同步标识的名下，记一笔 */
    private static void absorb(WebDavData data, JSONObject bookRecord, JSONObject bookFavorite) {
        for (History item : data.getData().getHistory()) {
            String name = Owner.key(item.getKey(), Owner.RECORD);
            if (name != null) Owner.mark(Owner.DAV, bookRecord, name);
        }
        for (Keep item : data.getData().getKeep()) {
            String name = Owner.key(item.getKey(), Owner.FAVORITE);
            if (name != null) Owner.mark(Owner.DAV, bookFavorite, name);
        }
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
        guardLocalLoss(merged, localData, baseData, bookRecord, bookFavorite, () -> sync(null));
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
        // 手动「上传」= 以账号为基准，把本机这份**整份覆盖**到云端（马先生 2026-11-08）：
        // participate(true) 不看原来归属，本机参与同步的设置/记录/收藏全带；save() 是整份写文件，
        // 云端原来那套（含别的账号留下的）会被整个换掉，不是把本机数据并进去。
        // 三项源照旧不上传（participate 里 withoutSources），本机正在用的源不受影响。
        WebDavData localData = participate(true, bookRecord, bookFavorite);
        // 唯一例外：本机比上次基线少了大批记录时先问一句「是不是你删的」，防误删把云端清空；
        // 用户确认（或数量不大）就照常整份覆盖，这条不是合并，是安全确认。
        guardLocalLoss(localData, localData, readBaseline(), bookRecord, bookFavorite, () -> push(null));
        absorb(localData, bookRecord, bookFavorite);
        localData.setTime(System.currentTimeMillis());
        DebugLog.d("Sync", "本机覆盖云端：整份写回，设置 " + localData.getPrefers().size() + " 项、记录 " + localData.getData().getHistory().size() + " 条、收藏 " + localData.getData().getKeep().size() + " 条");
        save(WebDavSetting.getFileUrl(), localData);
        Owner.save(Owner.DAV, Owner.RECORD, bookRecord);
        Owner.save(Owner.DAV, Owner.FAVORITE, bookFavorite);
        return getSummary(localData);
    }

    /**
     * 用云端覆盖本机 = 以账号为基准整份换一套（马先生 2026-11-08 定）：
     * 先把本机参与同步的设置、观看记录、收藏、关注的演员、Cookie 等**全部清空**，
     * 再把云端那份整份填进来，最后刷新。本机（含别的账号留下）的旧数据一条都不留着合并。
     * 点播源 / 直播源 / 壁纸三项及其偏好键永远不参与：清空前后都原样留在本机。
     */
    private static String doPull() throws Exception {
        check();
        Owner.touch();
        String url = WebDavSetting.getFileUrl();
        WebDavData remoteData = WebDavData.from(WebDav.get(url));
        if (remoteData == null) throw new Exception("Remote file not found");
        // 记录/收藏跟着账号走：云端那份先按「站点key+视频id」收成一条，挂到当前源名下
        remoteData.getData().setHistory(accountHistory(remoteData.getData().getHistory()));
        remoteData.getData().setKeep(accountKeep(remoteData.getData().getKeep()));
        JSONObject bookRecord = Owner.load(Owner.DAV, Owner.RECORD);
        JSONObject bookFavorite = Owner.load(Owner.DAV, Owner.FAVORITE);
        List<Config> sources = localSources(); // 三项源：全清之前抄下来，完事原样放回
        clearLocal();                          // 以账号为基准：本机可同步数据整库清空
        insertAll(remoteData);                 // 云端这份整份填进来
        AppDatabase.get().getConfigDao().insertOrUpdate(sources);
        Star.merge(remoteData.getData().getStar()); // 演员表已清空，这里就是整份替换（不是并集）
        // 云端这份里可能还留着旧版本上传的源数据，写回去时顺手清掉，别让它在云端反复来回传
        remoteData.getData().setConfig(Backup.withoutSources(remoteData.getData().getConfig()));
        remoteData.setPrefers(remoteData.getPrefers());
        // ConfigCache 不从云端落盘：源缓存跟本机当前源绑定，别台的缓存对本机无用
        absorb(remoteData, bookRecord, bookFavorite); // 云端这份就是当前同步标识的
        Owner.save(Owner.DAV, Owner.RECORD, bookRecord);
        Owner.save(Owner.DAV, Owner.FAVORITE, bookFavorite);
        writeBaseline(remoteData);
        remoteData.setTime(System.currentTimeMillis());
        save(url, remoteData);
        notifyDataChanged();
        reload();
        DebugLog.d("Sync", "云端覆盖本机：整库清空再填入，设置 " + remoteData.getPrefers().size() + " 项、记录 " + remoteData.getData().getHistory().size() + " 条、收藏 " + remoteData.getData().getKeep().size() + " 条");
        return getSummary(remoteData);
    }

    /** 本机正在用的源地址（点播源 / 直播源 / 壁纸）：doPull 覆盖前先抄下来，完事原样放回去 */
    private static List<Config> localSources() {
        List<Config> result = new ArrayList<>();
        for (Config item : AppDatabase.get().getConfigDao().findAll()) if (item.isSource()) result.add(item);
        return result;
    }

    /**
     * 以账号为基准整库清空（doPull 专用）：本机所有「参与同步」的数据一条不留，等云端那份填进来。
     * 不碰的只有三类：
     *   ① 点播源 / 直播源 / 壁纸三项（Config type 0/1/2 由调用方抄出来放回，config_ 偏好键跳过）；
     *   ② 本机独有键（webdav_/moontv_/owner_/debug_ 等：同步账号、凭据、调试标记，清了就把自己清掉了）；
     *   ③ 点播源 jar 的登录态（SpiderVault，跟本机当前源绑定，属于①的范畴）。
     * 以前这里只删 config/site/live，记录只删「当前账号」那部分、别的账号的原样留着，
     * 演员用 merge、设置键只增不删 —— 结果拉完还是两套房混在一起（马先生 2026-11-08）。
     */
    private static void clearLocal() {
        AppDatabase db = AppDatabase.get();
        db.getHistoryDao().delete();
        db.getTrackDao().delete();
        db.getKeepDao().delete();
        db.getSiteDao().delete();
        db.getLiveDao().delete();
        db.getConfigDao().delete();
        Star.deleteAll();
        // 偏好项：本机独有的和三项源的留下，其余全抹掉（不抹就是「云端没这项、本机还留着」的合并）
        for (String key : new ArrayList<>(Prefers.getPrefers().getAll().keySet())) {
            if (WebDavData.localOnly(key) || WebDavData.isSourcePref(key)) continue;
            Prefers.remove(key);
        }
        if (WebDavSetting.isCookie()) CookieStore.clear();
        ConfigCache.clear(0);
        ConfigCache.clear(1);
        DebugLog.d("Sync", "云端覆盖本机：本机可同步数据已整库清空（三项源与本机独有键保留）");
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
        // 本机配置全量查（含点播/直播源）：推送的源要在已有的那行上原地更新，
        // 不能当新行插入去撞 (url,type) 唯一索引（马先生 2026-11-09）。
        Map<String, Config> localConfig = map(AppDatabase.get().getConfigDao().findAll(), SyncManager::key);
        Star.merge(data.getStar());
        // 记录/收藏跟着账号走：对方推过来的先按「站点key+视频id」收成一条，挂到当前源名下
        List<History> history = accountHistory(data.getHistory());
        List<Keep> keep = accountKeep(data.getKeep());
        dropOtherCidHistory(history);
        dropOtherCidKeep(keep);
        if (!history.isEmpty()) {
            for (History item : history) {
                History old = localHistory.get(key(item));
                if (old != null && same(old, item)) continue;
                if (old != null) {
                    item.setCid(old.getCid());
                    History.keepSkip(old, item);
                }
                AppDatabase.get().getHistoryDao().insertOrUpdate(item);
            }
        }
        if (!keep.isEmpty()) {
            for (Keep item : keep) {
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
            // 局域网推送收下对方勾选推来的点播源(0)/直播源(1)：推送是明确动作，不受「三项不参与同步」限制；
            // 壁纸(2)不在推送可选项里，照旧不收。多机同步路径仍然走 withoutSources，一个源都不走云端。
            for (Config item : Backup.pushable(data.getConfig())) {
                Config old = localConfig.get(key(item));
                if (old != null && same(old, item)) continue;
                if (old != null) {
                    if (item.getId() == 0) item.setId(old.getId());
                    // 推送包超限时发送端会把 json 剥空：本机已有的源内容缓存别被清掉，选中时才按需重拉
                    if (item.getJson() == null || item.getJson().isEmpty()) item.setJson(old.getJson());
                }
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

    /**
     * 局域网推送来的大模型配置（马先生 2026-11-09）：自建模型列表按条合进本机
     * （增、改、删都带 utime，谁改得晚听谁的；删除靠墓碑标记传过去，不会「删不掉」），
     * 总开关跟着走；内部标记（快捷码自己人）不推——内置通道只认本机自己的标记。
     */
    public static void applyAiPush(String json) {
        try {
            JSONObject object = new JSONObject(json);
            AiSetting.applyRemote(object.optString(AiSetting.KEY_MODELS, ""));
            if (object.has("ai_judge")) AiSetting.putEnabled(object.optBoolean("ai_judge"));
        } catch (Exception ignored) {
        }
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
        // 记录/收藏跟着账号走：三端各自先按「站点key+视频id」收成一条，再合并
        merged.getData().setHistory(mergeList(accountHistory(backup(base).getHistory()), accountHistory(backup(local).getHistory()), accountHistory(backup(remote).getHistory()), SyncManager::key, SyncManager::newerHistory));
        merged.getData().setKeep(mergeList(accountKeep(backup(base).getKeep()), accountKeep(backup(local).getKeep()), accountKeep(backup(remote).getKeep()), SyncManager::key, SyncManager::newerKeep));
        // 关注的演员：三端合并，同一个演员取最后操作的那条（关注/取消关注都以最后一下为准）
        merged.getData().setStar(mergeList(backup(base).getStar(), backup(local).getStar(), backup(remote).getStar(), SyncManager::starKey, SyncManager::newerStar));
        // 源地址 Config 不参与同步：base/local/remote 都滤掉源，只合并非源 Config
        merged.getData().setConfig(mergeList(Backup.withoutSources(backup(base).getConfig()), Backup.withoutSources(backup(local).getConfig()), Backup.withoutSources(backup(remote).getConfig()), SyncManager::key, SyncManager::newerConfig));
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
            if (WebDavData.isSourcePref(name)) continue; // config_ 键不参与同步
            Object b = base.get(name);
            Object l = local.get(name);
            Object r = remote.get(name);
            if (l == null && r == null) continue;
            // 大模型模型列表（ai_models）是一整串 JSON，按字符串比谁新会整块互相盖掉——
            // 得**按每条模型的 id 合**：一边加的、一边删的（删除标记）都要留着，谁改得晚听谁的
            if (AiSetting.KEY_MODELS.equals(name)) {
                JSONArray merged = AiSetting.merge(array(b), array(l), array(r));
                if (merged.length() > 0) result.put(name, merged.toString());
                continue;
            }
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

    /**
     * 观看记录的合并键 = 站点 key + 视频 id（跟收藏一个口径）：
     * 记录跟着账号走，不看点播源，主键末段那个 cid 不参与比较。
     */
    private static String key(History item) {
        return baseKey(item.getKey());
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

    /** 关注的演员按名字归一（「张三」和「 张三 」算一个） */
    private static String starKey(Star item) {
        return Star.fix(item.getName());
    }

    /** 同一个演员在几台设备上各操作过：取最后操作的那条（关注、取消关注都算操作） */
    private static Star newerStar(Star a, Star b) {
        return b.getUpdateTime() > a.getUpdateTime() ? b : a;
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

    /**
     * 防的是「App 里误删 → 一同步网盘上的也没了」这一步（见 SyncGuard）。
     *
     * 判据：上次同步时本机有、这一趟本机没有了的那些（别的同步标识的、换了点播源的不算）。
     * 数量偏大就在同步的时候问一句，**问的时候这一趟先停下等答案**：
     *   · 说「是我删的」→ 这一轮就按删除走，云端和本机一起删掉；
     *   · 没界面弹 / 不表态 / 超时 / 说「不是」→ 一条都不删，那批救回这一轮的结果里 ——
     *     网盘原样留着，本机刚删的那批也跟着回来了，等于把误删救回来。
     */
    private static void guardLocalLoss(WebDavData merged, WebDavData local, WebDavData base, JSONObject bookRecord, JSONObject bookFavorite, Runnable again) {
        List<History> lostHistory = lostHistory(base, local, bookRecord);
        List<Keep> lostKeep = lostKeep(base, local, bookFavorite);
        int lost = lostHistory.size() + lostKeep.size();
        if (lost == 0) return;
        int total = local.getData().getHistory().size() + local.getData().getKeep().size() + lost;
        if (SyncGuard.allow(lost, total)) return;
        // 数量大就真等用户答完再往下走：确认 → 这一轮就让删除生效（云端和本机一起删）；
        // 拒绝 / 没界面 / 超时 → 按误删救回。
        // 改动前是「先无条件救回、同步照跑」，本机库当场被填回去，用户再点「仍然删除」重跑一趟
        // 时本机已经是满的、一条不缺 —— 删除永远落不了地，删多少回来多少（2026-11-03 马先生的坑）
        if (!waitAnswer(lostHistory.size(), lostKeep.size(), again)) restore(merged, lostHistory, lostKeep);
    }

    /**
     * 在后台同步线程上等用户对弹窗表态。等到的答案：
     *   true  = 确认删，调用方按删除继续走；
     *   false = 拒了 / 没弹出来 / 超时没答，按误删处理。
     * 超时后弹窗若还留在屏幕上，用户迟些点「仍然删除」时会补跑一整轮（again），删除不白点。
     * busy 闸保证同一时刻只有一趟 WebDAV 同步，这里阻塞不会跟别的同步打架。
     */
    private static boolean waitAnswer(int historyDel, int keepDel, Runnable again) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        SyncGuard.ask(historyDel, keepDel, () -> {
            approved.set(true);
            latch.countDown();
            if (finished.get()) again.run(); // 这趟已经按「不删」收尾了，迟到的确认补跑一轮
        }, latch::countDown);
        try {
            return latch.await(ANSWER_TIMEOUT, TimeUnit.SECONDS) && approved.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            finished.set(true);
        }
    }

    /** 上次同步时本机有、现在本机没有了的观看记录 */
    /**
     * 上次同步时本机有、现在本机没有了的观看记录。
     * 换点播源不算删：记录的键里带着 cid，另一个源的那批数据照样在本机库里，
     * 被排除在参与名单之外的只有「换了账号」的那部分，不在这儿。
     */
    private static List<History> lostHistory(WebDavData base, WebDavData local, JSONObject book) {
        List<History> lost = new ArrayList<>();
        if (base == null) return lost; // 没同步过，无从比较
        Set<String> keys = keys(local.getData().getHistory(), SyncManager::key);
        for (History item : base.getData().getHistory()) {
            String name = Owner.key(item.getKey(), Owner.RECORD);
            if (name != null && Owner.foreign(Owner.DAV, book, name)) continue; // 别的同步标识的，本来就不在本机
            if (!keys.contains(key(item))) lost.add(item);
        }
        return lost;
    }

    /** 上次同步时本机有、现在本机没有了的收藏（收藏跟账号走，哪个源的都算） */
    private static List<Keep> lostKeep(WebDavData base, WebDavData local, JSONObject book) {
        List<Keep> lost = new ArrayList<>();
        if (base == null) return lost;
        Set<String> keys = keys(local.getData().getKeep(), SyncManager::key);
        for (Keep item : base.getData().getKeep()) {
            String name = Owner.key(item.getKey(), Owner.FAVORITE);
            if (name != null && Owner.foreign(Owner.DAV, book, name)) continue;
            if (!keys.contains(key(item))) lost.add(item);
        }
        return lost;
    }

    /** 把「先不删」的那批补回这次上传的结果：网盘留着，本机也跟着恢复 */
    private static void restore(WebDavData merged, List<History> history, List<Keep> keep) {
        Set<String> historyKeys = keys(merged.getData().getHistory(), SyncManager::key);
        for (History item : history) if (!historyKeys.contains(key(item))) merged.getData().getHistory().add(item);
        Set<String> keepKeys = keys(merged.getData().getKeep(), SyncManager::key);
        for (Keep item : keep) if (!keepKeys.contains(key(item))) merged.getData().getKeep().add(item);
    }

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

        List<History> delHistory = new ArrayList<>();
        for (History item : o.getHistory()) if (!historyKeys.contains(key(item))) delHistory.add(item);
        List<Keep> delKeep = new ArrayList<>();
        for (Keep item : o.getKeep()) if (!keepKeys.contains(key(item))) delKeep.add(item);
        // 云端那份没了 → 本机跟着删，这是同步的正常结果，不问（SyncGuard 只管「本机删→云端删」）
        deleteHistoryKeep(delHistory, delKeep);
        DebugLog.d("Sync", "doSync 落库：记录 " + m.getHistory().size() + " 条、收藏 " + m.getKeep().size() + " 条；云端没了跟着删掉 " + delHistory.size() + " 条记录、" + delKeep.size() + " 条收藏");
        for (Config item : o.getConfig()) {
            if (configKeys.contains(key(item))) continue;
            if (item.isSource()) continue;
            AppDatabase.get().getConfigDao().delete(item.getUrl(), item.getType());
            configChanged = true;
        }

        dropOtherCidHistory(m.getHistory());
        dropOtherCidKeep(m.getKeep());
        // 关注的演员落库：「最后操作为准」，谁后动听谁的（取消关注那条也要落到本机）
        if (!m.getStar().isEmpty()) {
            Star.merge(m.getStar());
            DebugLog.d("Sync", "doSync 落库：关注演员 " + m.getStar().size() + " 条");
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
            if (item.isSource()) continue;
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
        // ConfigCache 不从同步数据落盘：源缓存跟本机当前源绑定，别台的缓存对本机无用
        return configChanged;
    }

    /**
     * 落库前把本机同一部片在别的点播源（cid）底下留的那一份清掉：
     * 记录／收藏跟着账号走，一部片就一条；换源之后旧的那条要是留着，
     * 在当前源里既看不见（列表按当前源取）又占着位子，等于记录丢了。
     */
    private static void dropOtherCidHistory(List<History> items) {
        if (items == null || items.isEmpty()) return;
        // 点播源还没加载出来（cid=0）时这份没归到当前源名下，拿它当尺子会把当前源那份当成「别的源」删掉
        if (currentCid() <= 0) return;
        Set<String> base = new HashSet<>();
        Set<String> hold = new HashSet<>();
        for (History item : items) {
            base.add(key(item));
            hold.add(item.getKey());
        }
        int removed = 0;
        for (History old : AppDatabase.get().getHistoryDao().findAll()) {
            if (!base.contains(key(old)) || hold.contains(old.getKey())) continue;
            AppDatabase.get().getHistoryDao().delete(old.getCid(), old.getKey());
            AppDatabase.get().getTrackDao().delete(old.getKey());
            ++removed;
        }
        if (removed > 0) DebugLog.d("Sync", "跨源归并：清掉别的源底下留的 " + removed + " 条重复记录");
    }

    /** 同上，收藏那份（直播收藏 type=1 的键不是「站点@@@视频」三段，不碰） */
    private static void dropOtherCidKeep(List<Keep> items) {
        if (items == null || items.isEmpty()) return;
        if (currentCid() <= 0) return; // 同上
        Set<String> base = new HashSet<>();
        Set<String> hold = new HashSet<>();
        for (Keep item : items) {
            base.add(key(item));
            hold.add(item.getKey());
        }
        int removed = 0;
        for (Keep old : AppDatabase.get().getKeepDao().findAll()) {
            if (old.getType() != 0) continue;
            if (!base.contains(key(old)) || hold.contains(old.getKey())) continue;
            AppDatabase.get().getKeepDao().delete(old.getCid(), old.getKey());
            ++removed;
        }
        if (removed > 0) DebugLog.d("Sync", "跨源归并：清掉别的源底下留的 " + removed + " 条重复收藏");
    }

    private static void deleteHistoryKeep(List<History> history, List<Keep> keep) {
        if (history.isEmpty() && keep.isEmpty()) return;
        for (History item : history) {
            AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
            AppDatabase.get().getTrackDao().delete(item.getKey());
        }
        for (Keep item : keep) AppDatabase.get().getKeepDao().delete(item.getCid(), item.getKey());
        notifyDataChanged();
    }

    private static void insertAll(WebDavData data) {
        Backup backup = data.getData();
        AppDatabase.get().getSiteDao().insertOrUpdate(backup.getSite());
        AppDatabase.get().getLiveDao().insertOrUpdate(backup.getLive());
        // 源地址 Config 跳过：每台设备配自己的源，不被云端数据覆盖
        AppDatabase.get().getConfigDao().insertOrUpdate(Backup.withoutSources(backup.getConfig()));
        dropOtherCidKeep(backup.getKeep());
        dropOtherCidHistory(backup.getHistory());
        AppDatabase.get().getKeepDao().insertOrUpdate(backup.getKeep());
        AppDatabase.get().getHistoryDao().insertOrUpdate(backup.getHistory());
        for (Map.Entry<String, ?> entry : backup.getPrefers().entrySet()) {
            if (WebDavData.localOnly(entry.getKey())) continue;
            if (WebDavData.isSourcePref(entry.getKey())) continue;
            Prefers.put(entry.getKey(), entry.getValue());
        }
        CookieStore.apply(backup.getCookies());
        // 点播源 jar 的登录态（SpiderVault）不从云端落盘：它跟本机当前点播源绑定，属于「三项不参与同步」，
        // 整份替换时也保持本机原样，不推来不收回（马先生 2026-11-08）
        // ConfigCache 同理：源缓存跟本机当前源绑定，别台的缓存对本机无用
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
            if (WebDavData.isSourcePref(name)) continue;
            // 老版本发送端的「系统设置」里还带着 ai_models 整串：按条合并，别整块覆盖掉本机的增删改
            if (AiSetting.KEY_MODELS.equals(name)) {
                AiSetting.applyRemote(entry.getValue() == null ? null : entry.getValue().toString());
                continue;
            }
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
            RefreshEvent.star(); // 整份替换时演员表也被清过重建，关注页跟着重查
            // 马先生 2026-11-09：下拉同步/推送完成后整份数据都换了，开着的页面一律原地重读，
            // 首页、分类页、设置显示都不留旧数据，不再靠重启 App 生效。
            RefreshEvent.home();     // 首页重新拉分类 + 重设标题
            RefreshEvent.category(); // 分类页原地重读
            // history() 已让首页重建最近观看，home() 已重拉分类，不必再发 size() 重复请求一遍
        });
    }

    /** 局域网推送刚把登录态/配置写进来：原地重载配置与各页面，不重启进程 */
    public static void reloadInPlace() {
        reload();
        notifyDataChanged();
    }

    /**
     * 同步改了非源配置（解析接口、规则等）后重新加载让变化生效。
     *
     * 关键：用当前内存里已经在用的 Config 重新加载，不调 init() 从数据库重新取——
     * init() 会 findOne(type) 按 time DESC 取一条，如果用户刚改了源但新源还在加载中
     * （config.update() 还没跑到、time 还是 0），findOne 就会返回旧源，
     * 把用户刚选的源给冲掉。源地址已经排除出同步，同步后没理由换源。
     */
    private static void reload() {
        App.post(() -> {
            VodConfig.get().load(VodConfig.get().getConfig(), new Callback() {
                @Override
                public void error(String msg) {
                }
            });
            LiveConfig.get().load(LiveConfig.get().getConfig(), new Callback() {
                @Override
                public void error(String msg) {
                }
            });
            WallConfig.get().load(WallConfig.get().getConfig(), new Callback() {
                @Override
                public void error(String msg) {
                }
            });
        });
    }

    /** 三份里的同一键取出来当 JSON 数组（大模型那串是数组，别的键用不到） */
    private static JSONArray array(Object value) {
        if (!(value instanceof String)) return new JSONArray();
        String text = ((String) value).trim();
        if (text.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(text);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}

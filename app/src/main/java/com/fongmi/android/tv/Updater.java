package com.fongmi.android.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.SelfHost;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.GhRoute;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本检测与下载。
 * 线路策略：直连优先 → 公益加速代理（自动测速挑最快） → 本地 SOCKS5 兜底。
 */
public class Updater implements Download.Callback, UpdateListener {

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int PROBE_TIMEOUT = 6000;
    /** 测速线路上限：多了既费流量又拖慢老设备 */
    private static final int SPEED_LIMIT = 3;
    private static final int SPEED_TIMEOUT = 8000;
    private static final int SPEED_BYTES = 384 * 1024;
    /** App 一启动就先探一次版本文件，探到的结果在这段时间内可以直接拿来用 */
    private static final long WARM_TTL = 60 * 1000;
    /** 探测总时限：线路有十条左右，不能让一条半死不活的把整体拖到十几秒 */
    private static final int PROBE_DEADLINE = 8000;
    /** 上一轮检查超过这么久还没收工，就当它卡死了，放行新一轮 */
    private static final long BUSY_TIMEOUT = 60 * 1000;
    /** 手动点「检查更新」时只挡这么久：上一轮多半是进首页的自动检查，用户等不了也该让他重来 */
    private static final long BUSY_TOL_FORCED = 3 * 1000;
    /** 刚确认过「没有新版本」，这段时间内自动检查别反复去问 */
    private static final long WARM_NONE_TTL = 5 * 60 * 1000;
    /** 网络一恢复就补探，但别被网络抖动反复触发 */
    private static final long WARM_RETRY_GAP = 30 * 1000;
    /** 弹框后给用户挑线路的最长等待：这段时间里每 1 秒看一眼好没好 */
    private static final int PREPARE_RETRY = 10;

    private static volatile List<Probe> warm; // 启动时预热探到的线路结果
    private static volatile long warmAt;
    private static volatile long warmNone; // 上次确认「没有新版本」的时刻
    private static volatile long warmTry;
    private static volatile boolean watched;
    private static volatile boolean busy;    // 正在检查标记：连点不会叠好几轮
    private static volatile long busyAt;

    private final List<Probe> backup = new ArrayList<>();

    private Download download;
    private UpdateDialog dialog;
    private boolean forced;
    private boolean retried;
    private Probe route;
    private String apk;
    private String tag;
    private long size;

    private Updater() {
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private static String getJson() {
        return Github.getJson(BuildConfig.FLAVOR_mode);
    }

    /**
     * 版本文件带个时间戳再取：公益代理会缓存 raw 内容，
     * 不带这个参数改了更新说明可能还拿到旧的。
     */
    private static String getJsonUrl() {
        String url = getJson();
        return url + (url.contains("?") ? "&" : "?") + "_t=" + System.currentTimeMillis();
    }

    /**
     * 版本检测提前：App 一启动就先探一次版本文件（走独立线程，不跟首页的请求挤），
     * 等首页或设置页真要检查更新时，直接拿这份新鲜结果用，弹框不用再干等网络。
     * 探不到也无妨，原来该怎么走还怎么走。
     */
    public static void prewarm() {
        if (!Setting.getUpdate()) return;
        if (warm != null && System.currentTimeMillis() - warmAt < WARM_TTL) return;
        new Thread(() -> {
            try {
                List<Probe> probes = probeAll(getRoutes(), getJsonUrl());
                Probe best = newest(probes);
                if (best == null) return; // 一条都没通，下次再试
                // 探到的是旧版本（多半是代理缓存），就别存：存了会让这一分钟内的检查一直拿旧结果
                if (best.code <= BuildConfig.VERSION_CODE) {
                    warmNone = System.currentTimeMillis();
                    return;
                }
                warm = probes;
                warmAt = System.currentTimeMillis();
            } catch (Throwable ignored) {
            }
        }, "update-warm").start();
    }

    /**
     * 从所有探测结果里挑版本号最新的那份。
     *
     * 关键：各条加速代理的缓存新旧不一，刚发版那一阵有的给新版、有的还攥着旧版。
     * 以前只看耗时最短的那条，它要是恰好缓存了旧版本，就判定"已是最新"——
     * 表现出来就是要点很多次，哪次最快那条是新鲜的才弹框。现在任何一条拿到新版都算数。
     */
    private static Probe newest(List<Probe> probes) {
        Probe best = null;
        if (probes == null) return null;
        for (Probe item : probes) {
            if (item == null || item.code <= 0) continue;
            if (best == null || item.code > best.code) best = item;
        }
        return best;
    }

    /**
     * 定用哪份：自建源和 GitHub 比版本号，谁新用谁；一样新就用自建源（下载快、不经代理）。
     * 自建源上没有这台机型对应的包（apk 为空）时，仍然走 GitHub。
     */
    private static Probe pick(List<Probe> probes, Probe self) {
        Probe best = newest(probes);
        if (self == null || self.apk == null || self.apk.isEmpty()) return best;
        if (best == null || self.code >= best.code) return self;
        return best;
    }

    /**
     * 问自建网盘要版本文件。这一步不走 GhRoute 的任何线路，直连自己的服务器；
     * 域名挂了会自动换备用域名（OkHttp 里那条兜底拦截器管这事），再不行就返回 null，
     * 后面完全按 GitHub 的老路走。
     */
    private static Probe selfProbe() {
        try {
            Request request = new Request.Builder().url(SelfHost.json())
                    .header("Authorization", SelfHost.auth())
                    .header("Cache-Control", "no-cache")
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get().build();
            try (Response res = OkHttp.client(PROBE_TIMEOUT).newCall(request).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                String body = res.body().string();
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code");
                if (code <= 0) return null;
                JSONObject map = json.optJSONObject("apk");
                String apk = map == null ? "" : map.optString(SelfHost.key());
                DebugLog.d("Update", "自建源 code=" + code + (apk.isEmpty() ? "（本机型的包不在网盘上，走 GitHub）" : " 有包"));
                return new Probe(SelfHost.ROUTE, 0, body, code, apk);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 两轮探测的结果合到一起（按线路去重，后一轮的为准） */
    private static List<Probe> merge(List<Probe> first, List<Probe> second) {
        List<Probe> all = new ArrayList<>();
        if (first != null) all.addAll(first);
        if (second == null) return all;
        for (Probe item : second) {
            if (item == null) continue;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) != null && all.get(i).route.equals(item.route)) all.remove(i--);
            }
            all.add(item);
        }
        return all;
    }

    /**
     * 网络恢复补探：刚开机那一刻 Wi-Fi 常常还没连上，App 启动时那次预热就是白跑的，
     * 结果只能等到进首页才查。这里盯着网络，一通上来就补探一次。
     * 注册完一直挂着，跟进程同寿，不用注销。
     */
    public static void watch() {
        if (watched) return;
        watched = true;
        try {
            ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return;
            NetworkRequest request = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            manager.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    retryWarm();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryWarm();
                }
            });
        } catch (Throwable ignored) {
            // 个别设备上这一步会甩 SecurityException，注册不上就还按老样子走，不值当崩
        }
    }

    private static void retryWarm() {
        if (!Setting.getUpdate()) return;
        long now = System.currentTimeMillis();
        if (now - warmTry < WARM_RETRY_GAP) return;
        warmTry = now;
        prewarm();
    }

    /** 预热结果还新鲜就拿过来用，省掉一次探测 */
    private static List<Probe> fresh() {
        if (warm == null) return null;
        if (System.currentTimeMillis() - warmAt > WARM_TTL) return null;
        return warm.isEmpty() ? null : warm;
    }

    private String getApk(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        return Github.getApk(tag, BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi);
    }

    public Updater force() {
        forced = true;
        warm = null; // 手动点的「检查更新」必须真去问一次，不能用启动时那份
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        if (!enter()) {
            // 上一轮还没跑完。手动点的时候说一声，别让人以为没点到又连着点
            if (forced) App.post(() -> Notify.show(R.string.update_checking));
            return;
        }
        // 走自己的线程：Task 那个池子首页也在用，挤上去可能排队排到几十秒后
        new Thread(() -> {
            try {
                doInBackground(activity);
            } finally {
                busy = false;
            }
        }, "update-check").start();
    }

    /** 一次只跑一轮检查；上一轮真卡死了也别把更新功能憋死。手动点只挡三秒，别让人干等 */
    private boolean enter() {
        long now = System.currentTimeMillis();
        long tolerate = forced ? BUSY_TOL_FORCED : BUSY_TIMEOUT;
        if (busy && now - busyAt < tolerate) return false;
        busy = true;
        busyAt = now;
        return true;
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            List<Probe> probes = fresh(); // 启动预热探到的那份还新鲜就直接用
            // 刚确认过没有新版本，自动检查就别反复去问了（手动点不受此限）
            if (probes == null && !forced && System.currentTimeMillis() - warmNone < WARM_NONE_TTL) return;
            if (probes == null) probes = probeAll(getRoutes(), getJsonUrl());
            // 手动点的，探测全挂就自动再试一轮，别让用户干等着以为没点到
            if (forced && (probes == null || probes.isEmpty())) probes = probeAll(getRoutes(), getJsonUrl());
            // 自建源只问一次：自己的网盘不经公益代理，不会被缓存，拿到的一定是最新的
            Probe self = selfProbe();
            Probe best = pick(probes, self);
            // 手动点：第一轮拿到的可能全是代理的旧缓存，换个时间戳再问一轮，哪条新用哪条
            if (forced && best != null && best.code <= BuildConfig.VERSION_CODE) {
                List<Probe> again = probeAll(getRoutes(), getJsonUrl());
                if (again != null && !again.isEmpty()) {
                    probes = merge(probes, again);
                    best = pick(probes, self);
                }
            }
            if (best == null) {
                fail(R.string.update_check_fail);
                return;
            }
            JSONObject object = new JSONObject(best.body);
            String name = object.optString("name");
            String desc = wrap(object.optString("desc"));
            tag = object.optString("tag");
            int code = object.optInt("code");
            if (code <= BuildConfig.VERSION_CODE) {
                if (code > 0) warmNone = System.currentTimeMillis();
                if (forced) App.post(() -> Notify.show(R.string.update_latest));
                return;
            }
            String url = best.apk == null || best.apk.isEmpty() ? getApk(tag) : best.apk;
            if (url.isEmpty()) {
                fail(R.string.update_check_fail);
                return;
            }
            apk = url;
            // 先弹框：确定有新版本就立刻告诉用户，别让他对着「正在检测更新…」干等
            App.post(() -> show(activity, name, desc));
            if (SelfHost.is(best.route)) {
                // 自建源就这一条路，不用再挑线路测速
                route = best;
                download = createDownload(SelfHost.ROUTE);
            } else {
                // 挑哪条线路下、整包多大，这些放后台接着做，用户看更新说明的工夫刚好干完
                prepare(probes);
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(R.string.update_check_fail);
        }
    }

    /** 手动检查失败了一定要出声，以前是静悄悄返回，只能再点一次碰运气 */
    private void fail(int resId) {
        if (!forced) return;
        App.post(() -> Notify.show(resId));
    }

    /** 弹框之后才做的活：选最快线路 + 量整包大小，做好了就把 download 挂上 */
    private void prepare(List<Probe> probes) {
        try {
            Probe pick = choose(probes, apk);
            if (pick == null) return;
            route = pick;
            download = createDownload(pick.route);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 更新说明的换行：json 里写成 \n（真换行）或 \\n（字面反斜杠 n）都能正常分行
     */
    private String wrap(String desc) {
        if (desc == null) return "";
        return desc.replace("\\n", "\n").replace("<br>", "\n").replace("<br/>", "\n");
    }

    /**
     * 参与探测的线路：手动锁定的那条排在最前，后面是所有候选
     */
    private static List<String> getRoutes() {
        List<String> routes = new ArrayList<>();
        String fixed = GhRoute.fixed();
        if (fixed != null) routes.add(fixed);
        for (String route : GhRoute.candidates()) {
            if (!routes.contains(route)) routes.add(route);
        }
        return routes;
    }

    /**
     * 换版本就丢掉上一版的半成品；同一个包记住大小，下次能接着下
     */
    private Download createDownload(String route) {
        if (!tag.equals(Prefers.getString("update_tag"))) {
            Path.clear(getFile());
            Prefers.put("update_tag", tag);
            Prefers.put("update_size", 0L);
            size = 0;
        }
        if (size <= 0) size = Prefers.getLong("update_size");
        if (size <= 0) size = probeSize(route);
        if (size > 0) Prefers.put("update_size", size);
        // 校验：装之前确认这是个真 APK（>20MB 且 zip 头），别把代理的报错页丢给安装器
        Download download = Download.create(url(route, apk), getFile()).expect(size).verify(20L * 1024 * 1024);
        // 自建源是直连自己的网盘：不走加速线路，但要带上认证
        return SelfHost.is(route) ? download.header("Authorization", SelfHost.auth()).client(OkHttp.client(TimeUnit.SECONDS.toMillis(60))) : download.client(GhRoute.stream(route));
    }

    /** 自建源的包地址本来就是完整的，不能再往前面拼加速前缀 */
    private static String url(String route, String apk) {
        return SelfHost.is(route) ? apk : GhRoute.wrap(route, apk);
    }

    /**
     * 只取 1 个字节，从 Content-Range 里拿到整包大小
     */
    private long probeSize(String route) {
        Request request = new Request.Builder().url(url(route, apk)).header("Range", "bytes=0-0").get().build();
        if (SelfHost.is(route)) request = request.newBuilder().header("Authorization", SelfHost.auth()).build();
        try (Response res = GhRoute.probe(route, 10000).newCall(request).execute()) {
            String range = res.header("Content-Range");
            if (range != null && range.contains("/")) return Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            String length = res.header("Content-Length");
            return res.code() == 200 && length != null ? Long.parseLong(length) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 并发探测各条线路能不能取到版本文件，按耗时排序
     */
    private static List<Probe> probeAll(List<String> routes, String url) {
        List<Probe> result = new ArrayList<>();
        if (routes.isEmpty()) return result;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(routes.size(), 8));
        List<Future<Probe>> futures = new ArrayList<>();
        for (String route : routes) futures.add(pool.submit(() -> probe(route, url)));
        // 整体设个 deadline：快的线路基本都在前几秒回来，剩下的慢的直接不等
        long deadline = System.currentTimeMillis() + PROBE_DEADLINE;
        for (Future<Probe> future : futures) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                Probe item = future.get(Math.min(left, PROBE_TIMEOUT), TimeUnit.MILLISECONDS);
                if (item != null) result.add(item);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        result.sort((a, b) -> Long.compare(a.cost, b.cost));
        return result;
    }

    private static Probe probe(String route, String url) {
        long start = System.currentTimeMillis();
        // 版本文件绝不能拿缓存的：时间戳参数（getJsonUrl）只挡得住按 URL 缓存的代理，
        // 这里再补两个 no-cache 头 + 强制走网络，挡住忽略查询串、只按路径缓存的那种代理
        Request request = new Request.Builder().url(GhRoute.wrap(route, url))
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get().build();
        try (Response res = GhRoute.probe(route, PROBE_TIMEOUT).newCall(request).execute()) {
            if (!res.isSuccessful() || res.body() == null) return null;
            String body = res.body().string();
            // 校验拿到的确实是版本文件，避免某些代理返回网页却给了 200
            int code = new JSONObject(body).optInt("code");
            if (code <= 0) return null;
            // 留痕：日后看点几十次才弹出这种事，一眼就能看出是哪条线路攥着旧缓存
            DebugLog.d("Update", host(route) + " 返回 code=" + code + " 用时 " + (System.currentTimeMillis() - start) + "ms");
            return new Probe(route, System.currentTimeMillis() - start, body, code);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 挑出真正下载用的线路：直连优先，别的线路明显更快才换过去
     */
    private Probe choose(List<Probe> probes, String apk) {
        String fixed = GhRoute.fixed();
        Probe direct = null;
        List<Probe> others = new ArrayList<>();
        for (Probe item : probes) {
            if (fixed != null && item.route.equals(fixed)) return keep(probes, item);
            if (GhRoute.isDirect(item.route)) direct = item;
            else others.add(item);
        }
        if (direct != null) measure(others, apk);
        direct = measure(direct == null ? null : direct, apk);
        Probe best = null;
        for (Probe item : others) {
            if (best == null || item.speed > best.speed) best = item;
        }
        if (direct == null) return keep(probes, best);
        if (best != null && best.speed > direct.speed * 2) return keep(probes, best);
        return keep(probes, direct);
    }

    private Probe keep(List<Probe> probes, Probe pick) {
        backup.clear();
        if (pick == null) return null;
        for (Probe item : probes) {
            if (item != pick) backup.add(item);
        }
        return pick;
    }

    /**
     * 并发测速：取整包前 384KB 的下载速度
     */
    private void measure(List<Probe> probes, String apk) {
        if (probes.isEmpty()) return;
        // 线路多了就只给探测最快的几条做测速，别让老盒子为十几条线路各下 384KB
        while (probes.size() > SPEED_LIMIT) probes.remove(probes.size() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(probes.size(), 6));
        List<Future<?>> futures = new ArrayList<>();
        for (Probe item : probes) futures.add(pool.submit(() -> item.speed = speed(item.route, apk)));
        for (Future<?> future : futures) {
            try {
                future.get(SPEED_TIMEOUT + 2000, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }

    private Probe measure(Probe probe, String apk) {
        if (probe != null) probe.speed = speed(probe.route, apk);
        return probe;
    }

    private long speed(String route, String url) {
        long start = System.currentTimeMillis();
        long total = 0;
        try (Response res = GhRoute.probe(route, SPEED_TIMEOUT).newCall(new Request.Builder().url(url(route, url)).header("Range", "bytes=0-" + SPEED_BYTES).get().build()).execute()) {
            if (!res.isSuccessful() || res.body() == null) return 0;
            try (InputStream is = res.body().byteStream()) {
                byte[] buffer = new byte[16384];
                while (total < SPEED_BYTES && System.currentTimeMillis() - start < SPEED_TIMEOUT) {
                    int len = is.read(buffer);
                    if (len <= 0) break;
                    total += len;
                }
            }
            long cost = Math.max(1, System.currentTimeMillis() - start);
            return total * 1000 / cost;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 弹更新框。探测是异步的，回来时页面可能已经切走甚至正在重建，
     * 这时候 show 会崩，所以状态不对就缓一下再弹，实在不行这次就不弹。
     */
    private void show(FragmentActivity activity, String version, String desc) {
        dismiss();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
        if (activity.getSupportFragmentManager().isStateSaved()) {
            if (!retried) {
                retried = true;
                App.post(() -> show(activity, version, desc), 1500);
            }
            return;
        }
        try {
            dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
        } catch (Exception ignored) {
            dialog = null;
        }
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        waitReady(view, 0);
    }

    /**
     * 更新框弹得早，线路可能还没挑完。最多等十秒，期间每秒看一眼好没好；
     * 好了直接开下，实在没准备好就把按钮还给人家再点一次，总好过点了没反应。
     */
    private void waitReady(View view, int retry) {
        if (download != null) {
            download.start(this);
            return;
        }
        if (retry >= PREPARE_RETRY) {
            view.setEnabled(true);
            Notify.show(R.string.update_prepare_fail);
            return;
        }
        App.post(() -> waitReady(view, retry + 1), 1000);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    @Override
    public void error(String msg) {
        if (!next()) {
            Notify.show(msg);
            dismiss();
        }
    }

    /**
     * 下载失败自动换下一条线路重来
     */
    private boolean next() {
        if (backup.isEmpty() || apk == null || apk.isEmpty()) return false;
        Probe item = backup.remove(0);
        route = item;
        size = 0; // 换线路重新量一次大小：记住的那个可能是上一条线路瞎报的
        Prefers.put("update_size", 0L);
        download = createDownload(item.route);
        download.start(this);
        return true;
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void success(File file) {
        install(file);
        dismiss();
    }

    /**
     * 拉起安装器。老机器（Android 6 那台投影）有的系统安装器不认 content://，
     * 直接抛 ActivityNotFoundException / SecurityException 会把 App 带崩，
     * 所以这里一层层退：content 地址 → file 地址 → 给个提示让你手动装。
     */
    private void install(File file) {
        if (!isInstallable(file)) {
            Path.clear(file); // 坏包留着下次续传会接着错，直接删掉重下
            Notify.show(R.string.update_bad_package);
            return;
        }
        try {
            FileUtil.openFile(file);
        } catch (Exception e) {
            if (!installByPath(file)) Notify.show(R.string.update_install_fail);
        }
    }

    /**
     * 交给安装器之前，先用系统把包解析一遍：下到一半的、被代理替换过的、压根不是 APK 的，
     * 这里就会返回空。以前什么都不查直接开装，才会出现「安装包是空的」。
     */
    private boolean isInstallable(File file) {
        if (file == null || !file.exists() || file.length() <= 0) return false;
        try {
            PackageInfo info = App.get().getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
            return info != null && info.packageName != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installByPath(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return false; // 7.0 以后 file:// 一律不给过
        try {
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(Uri.fromFile(file), APK_MIME);
            App.get().startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class Probe {

        final String route;
        final long cost;
        final String body;
        final int code; // 这条线路拿到的版本号
        final String apk; // 自建源给的安装包地址；GitHub 那份没有，为空
        long speed;

        Probe(String route, long cost, String body, int code) {
            this(route, cost, body, code, "");
        }

        Probe(String route, long cost, String body, int code, String apk) {
            this.route = route;
            this.cost = cost;
            this.body = body;
            this.code = code;
            this.apk = apk == null ? "" : apk;
        }
    }

    private static String host(String route) {
        try {
            return GhRoute.host(route).isEmpty() ? "直连" : GhRoute.host(route);
        } catch (Exception e) {
            return route == null ? "" : route;
        }
    }
}

package com.fongmi.android.tv;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.GhRoute;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
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

    private static volatile List<Probe> warm; // 启动时预热探到的线路结果
    private static volatile long warmAt;

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
                if (probes.isEmpty()) return;
                warm = probes;
                warmAt = System.currentTimeMillis();
            } catch (Throwable ignored) {
            }
        }, "update-warm").start();
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
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            List<Probe> probes = fresh(); // 启动预热探到的那份还新鲜就直接用
            if (probes == null) probes = probeAll(getRoutes(), getJsonUrl());
            if (probes.isEmpty()) return;
            JSONObject object = new JSONObject(probes.get(0).body);
            String name = object.optString("name");
            String desc = wrap(object.optString("desc"));
            tag = object.optString("tag");
            int code = object.optInt("code");
            if (code <= BuildConfig.VERSION_CODE) {
                if (forced) App.post(() -> Notify.show(R.string.update_latest));
                return;
            }
            apk = getApk(tag);
            if (apk.isEmpty()) return;
            route = choose(probes, apk);
            if (route == null) return;
            download = createDownload(route.route);
            App.post(() -> show(activity, name, desc));
        } catch (Exception e) {
            e.printStackTrace();
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
        return Download.create(GhRoute.wrap(route, apk), getFile()).client(GhRoute.stream(route)).expect(size).verify(20L * 1024 * 1024);
    }

    /**
     * 只取 1 个字节，从 Content-Range 里拿到整包大小
     */
    private long probeSize(String route) {
        try (Response res = GhRoute.probe(route, 10000).newCall(new Request.Builder().url(GhRoute.wrap(route, apk)).header("Range", "bytes=0-0").get().build()).execute()) {
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
        for (Future<Probe> future : futures) {
            try {
                Probe item = future.get(PROBE_TIMEOUT + 1000, TimeUnit.MILLISECONDS);
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
            if (new JSONObject(body).optInt("code") <= 0) return null;
            return new Probe(route, System.currentTimeMillis() - start, body);
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
        try (Response res = GhRoute.probe(route, SPEED_TIMEOUT).newCall(new Request.Builder().url(GhRoute.wrap(route, url)).header("Range", "bytes=0-" + SPEED_BYTES).get().build()).execute()) {
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
        if (download != null) download.start(this);
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
        long speed;

        Probe(String route, long cost, String body) {
            this.route = route;
            this.cost = cost;
            this.body = body;
        }
    }
}

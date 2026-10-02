package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.Gravity;
import android.widget.Toast;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 崩溃日志自动回传：App 启动后（延迟一小会儿，避开启动高峰）在后台线程把
 * files/crash/ 下 CrashGuard 落盘的堆栈传到归档 WebDAV 的「错误日志收集」目录，
 * 传成功就删本地文件，不攒垃圾。任何一步失败都静默——绝不因为这个功能再崩一次。
 *
 * 文件名：crash_<版本>_<设备型号>_<时间戳>.txt，一眼能看出哪台设备、哪个版本出的问题。
 */
public class CrashReporter {

    // App 专用的错误日志 WebDAV（只收日志，跟归档网盘分开）
    // https 在前；老设备（Android 6）信任库里没有 GTS 根，SSL 握手会失败，退到 http 继续传
    private static final String[] DAVS = {"https://www.12356.cool/dav", "http://www.12356.cool/dav"};
    private static final String USER = "error";
    private static final String PASS = "errorcode";
    private static final String FOLDER = "/错误日志收集/";
    private static final int MAX_FILES = 5;      // 一次最多补传 5 份，防积压时拖慢启动
    private static final int TIMEOUT = 15000;
    private static final long COOLDOWN = 10 * 60 * 1000L; // 页面恢复触发的冷却，别每次切页面都传
    private static final long RETENTION = 3 * 24 * 60 * 60 * 1000L; // 本地和云端日志都只留 3 天，超龄直接删

    // MKCOL 走 okhttp：HttpURLConnection 只认 8 个标准方法，MKCOL 会直接抛
    // "Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH] but was MKCOL"
    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .build();

    private static long last;

    private CrashReporter() {
    }

    /** 启动后调用：延迟 5 秒、后台线程执行；清超龄日志、传积压、传流水账、云端也只留 3 天 */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        Task.schedule(() -> {
            boolean crashed = hasRealCrash(app); // 上传前先记：传完文件就删了
            DebugLog.prune();   // 本地流水账只留 3 天
            pruneLocal(app);    // 本地崩溃日志只留 3 天
            uploadPending(app);
            uploadDebugAuto(app);
            ping(app); // 自检：证明这条上传链路是通的
            pruneCloud(); // 云端只留 3 天
            if (pending(app) > 0) Task.schedule(() -> uploadPending(app), 30, TimeUnit.SECONDS);
            if (crashed) hintDebug(app); // 崩过了：提醒一句开调试模式，下次崩才有流水账可查
        }, 5, TimeUnit.SECONDS);
    }

    /** 积压里有没有「真崩」的（escape 是 App 自己崩的；swallow 是蜘蛛 jar 被兜住，用户无感，不拿它打扰人） */
    private static boolean hasRealCrash(Context context) {
        try {
            File[] files = list(context);
            if (files == null) return false;
            for (File f : files) {
                byte[] head = readHead(f, 2048); // tag 行就在文件头
                if (head != null && new String(head, StandardCharsets.UTF_8).contains("tag: escape")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static byte[] readHead(File f, int max) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[max];
            int n = in.read(data);
            return n <= 0 ? null : (n == data.length ? data : Arrays.copyOf(data, n));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 崩过而调试模式没开：提示一声去设置里开，不然下次再崩还是两眼一抹黑 */
    private static void hintDebug(Context context) {
        if (DebugLog.isEnabled()) return;
        App.post(() -> Notify.show(R.string.debug_crash_hint));
    }

    /**
     * 自检心跳：每次启动往同一个目录写一份 ping_ 文件（同名覆盖，不攒垃圾）。
     * 用处是分辨两种「看不到日志」：
     *   目录里有 ping 却没有 crash → 上传是通的，说明真没留下 Java 堆栈（多半是内核 native 崩或被系统杀）；
     *   连 ping 都没有 → 上传链路本身不通（网络、证书、权限），先修这个。
     */
    private static void ping(Context context) {
        try {
            String model = (Build.MANUFACTURER + "_" + Build.MODEL).replaceAll("[^\\w\\-一-鿿]", "_");
            String head = "self-check: 上传链路自检，不是崩溃日志\n"
                    + "time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
                    + "device: " + Build.MANUFACTURER + " " + Build.MODEL + " api" + Build.VERSION.SDK_INT + "\n"
                    + "version: " + version(context) + "\n"
                    + "pending: " + pending(context) + " 份没传出去的日志\n";
            byte[] data = head.getBytes(StandardCharsets.UTF_8);
            for (String dav : DAVS) {
                try {
                    if (mkdirs(dav) < 0) continue;
                } catch (Throwable e) {
                    continue; // 这条线不通（老设备 https 握手失败），换下一条
                }
                int code = request("PUT", dav + UriEncoder.encode(FOLDER + "ping_" + version(context) + "_" + model + ".txt"), data);
                if (code >= 200 && code < 300) return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static String version(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static int pending(Context context) {
        File[] files = list(context);
        return files == null ? 0 : files.length;
    }

    /**
     * 设置页「上传日志」按钮：当场传一次，把结果（成功走的是 https 还是 http、传了几份）弹出来。
     * 专门用来验证某台设备（尤其 Android 6 老盒子）这条上传链路到底通不通——
     * 老设备信任库里没有 GTS 根，https 会静默失败，所以退到 http，结果里会写明走的哪条。
     */
    public static void manual(Context context) {
        Task.execute(() -> {
            String result = runManual(context.getApplicationContext());
            App.post(() -> Notify.show(result));
        });
    }

    private static String runManual(Context context) {
        String error = "";
        for (String dav : DAVS) {
            try {
                int code = mkdirs(dav);
                if (code < 0) {
                    error = "MKCOL " + code;
                    continue;
                }
                int crash = uploadCrash(context, dav);
                int debug = uploadDebug(context, dav);
                int ping = putText(context, dav, "manual_" + version(context) + "_" + model() + ".txt", manualBody(context));
                if (ping < 200 || ping >= 300) {
                    error = "PUT " + ping;
                    continue;
                }
                return ResUtil.getString(R.string.debug_upload_ok, scheme(dav), crash + debug);
            } catch (Throwable e) {
                error = String.valueOf(e.getMessage());
            }
        }
        return ResUtil.getString(R.string.debug_upload_fail, error);
    }

    private static String scheme(String dav) {
        return dav.startsWith("https") ? "https" : "http";
    }

    private static String model() {
        return (Build.MANUFACTURER + "_" + Build.MODEL).replaceAll("[^\\w\\-一-鿿]", "_");
    }

    private static String manualBody(Context context) {
        return "self-check: 手动上传测试，不是崩溃日志\n"
                + "time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
                + "device: " + Build.MANUFACTURER + " " + Build.MODEL + " api" + Build.VERSION.SDK_INT + " abi=" + Build.CPU_ABI + "\n"
                + "version: " + version(context) + "\n"
                + "debug_log: " + (DebugLog.isEnabled() ? DebugLog.size() + " 字节" : "未开启") + "\n"
                + "pending: " + pending(context) + " 份没传出去的日志\n";
    }

    /** 传调试日志（开着调试模式才有），返回传了几份 */
    private static int uploadDebug(Context context, String dav) {
        try {
            if (!DebugLog.isEnabled()) return 0;
            File f = DebugLog.file();
            if (f == null || !f.exists() || f.length() == 0) return 0;
            byte[] data = readTail(f, 1024 * 1024); // 太大就只传最后 1MB，最新那段才有用
            String name = "debug_" + version(context) + "_" + model() + "_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            int code = request("PUT", dav + UriEncoder.encode(FOLDER + name), data);
            return code >= 200 && code < 300 ? 1 : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 流水账自动回传：调试模式常开，日志有新内容（长度变了）才传，免得每次切页面都重传一遍。
     * 传成功在右下角亮一个小小的「R」，自家人知道日志上去了就行，不打扰用户。
     */
    private static void uploadDebugAuto(Context context) {
        try {
            File f = DebugLog.file();
            if (f == null || !f.exists() || f.length() == 0) return;
            if (f.length() == Prefers.getLong("debug_up_len", 0)) return; // 没新内容
            for (String dav : DAVS) {
                try {
                    if (mkdirs(dav) < 0) continue;
                    if (uploadDebug(context, dav) > 0) {
                        Prefers.put("debug_up_len", f.length());
                        hintR();
                        return;
                    }
                } catch (Throwable e) {
                    // 这条线不通，换下一条
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 右下角一闪而过的小「R」：日志已上传的内部暗号，不给用户任何说明 */
    private static void hintR() {
        try {
            App.post(() -> {
                try {
                    Toast toast = Toast.makeText(App.get(), "R", Toast.LENGTH_SHORT);
                    toast.setGravity(Gravity.BOTTOM | Gravity.END, 64, 64);
                    toast.show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 本地崩溃日志只留 3 天：传不出去的也别无限攒 */
    private static void pruneLocal(Context context) {
        try {
            File[] files = list(context);
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) if (now - f.lastModified() > RETENTION) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 云端只留 3 天：PROPFIND 列出「错误日志收集」目录，按 getlastmodified 把超龄的 DELETE 掉。
     * PROPFIND 不在 HttpURLConnection 的方法白名单里（跟 MKCOL 一样会被拦），走 okhttp。
     * 任何一步失败都静默——清不动就下次启动再清，绝不能因为这个崩。
     */
    private static void pruneCloud() {
        for (String dav : DAVS) {
            try {
                if (pruneCloud(dav)) return; // 这条线通了就收工
            } catch (Throwable e) {
                // 这条线不通，换下一条
            }
        }
    }

    private static boolean pruneCloud(String dav) throws IOException {
        byte[] body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><propfind xmlns=\"DAV:\"><prop><getlastmodified/></prop></propfind>".getBytes(StandardCharsets.UTF_8);
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(dav + UriEncoder.encode(FOLDER))
                .method("PROPFIND", okhttp3.RequestBody.create(null, body))
                .header("Authorization", basic())
                .header("Depth", "1")
                .header("Content-Type", "text/xml; charset=utf-8")
                .build();
        String xml;
        try (okhttp3.Response resp = CLIENT.newCall(req).execute()) {
            if (resp.code() < 200 || resp.code() >= 300 || resp.body() == null) return false;
            xml = resp.body().string();
        }
        List<String> hrefs = matchAll(xml, "<[^>]*:?href[^>]*>([^<]+)<");
        List<String> dates = matchAll(xml, "<[^>]*:?getlastmodified[^>]*>([^<]+)<");
        long now = System.currentTimeMillis();
        String origin = dav.substring(0, dav.indexOf('/', "https://".length())); // https://host
        for (int i = 0; i < hrefs.size() && i < dates.size(); i++) {
            try {
                String href = hrefs.get(i);
                if (!href.endsWith(".txt")) continue; // 只动日志文件，目录本身跳过
                Long modified = parseHttpDate(dates.get(i));
                if (modified == null || now - modified <= RETENTION) continue; // 没超龄或认不出日期的不动
                request("DELETE", origin + href, null);
            } catch (Throwable ignored) {
                // 删不动单个文件就跳过，别影响其他的
            }
        }
        return true;
    }

    private static List<String> matchAll(String xml, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /** WebDAV 的 getlastmodified 一般是 RFC1123（Wed, 01 Oct 2026 13:00:00 GMT），个别服务器给 ISO8601，两种都认 */
    private static Long parseHttpDate(String text) {
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss z", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
                return fmt.parse(text).getTime();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int uploadCrash(Context context, String dav) {
        File[] files = list(context);
        if (files == null || files.length == 0) return 0;
        int count = 0;
        for (File f : files) {
            if (count >= MAX_FILES) break;
            if (!upload(dav, f, remoteName(context, f))) break;
            f.delete();
            count++;
        }
        return count;
    }

    private static int putText(Context context, String dav, String name, String body) throws IOException {
        return request("PUT", dav + UriEncoder.encode(FOLDER + name), body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readTail(File f, int max) {
        try (InputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - max);
            if (skip > 0 && in.skip(skip) != skip) return null;
            int len = (int) Math.min(f.length(), max);
            byte[] data = new byte[len];
            int n = in.read(data);
            return n <= 0 ? null : (n == data.length ? data : Arrays.copyOf(data, n));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 崩溃刚被兜住、进程还活着时立刻传一次：不等下次启动，重启了可能就被卸/重装冲掉 */
    public static void flush() {
        try {
            Context app = com.fongmi.android.tv.App.get();
            Task.execute(() -> {
                uploadPending(app);
                uploadDebugAuto(app);
            });
        } catch (Throwable ignored) {
        }
    }

    /** 任意页面恢复时补一次：有些崩溃不会重启进程，只有这一趟能把它传出去 */
    public static synchronized void tick(Context context) {
        long now = System.currentTimeMillis();
        if (now - last < COOLDOWN) return;
        last = now;
        Context app = context.getApplicationContext();
        Task.execute(() -> {
            uploadPending(app);
            uploadDebugAuto(app); // 流水账也搭这趟车，有新内容才传
        });
    }

    private static void uploadPending(Context context) {
        try {
            File[] files = list(context);
            if (files == null || files.length == 0) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (String dav : DAVS) {
                if (tryBase(dav, context, files)) {
                    hintR(); // 有日志传出去了，右下角亮个「R」
                    return; // 这条线通了就收工
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 返回 true 表示这条线路可用，且已把能传的都传完 */
    private static boolean tryBase(String dav, Context context, File[] files) {
        try {
            if (mkdirs(dav) < 0) return false;
            int count = 0;
            for (File f : files) {
                if (count >= MAX_FILES) break;
                if (!upload(dav, f, remoteName(context, f))) return count > 0;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                count++;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static File[] list(Context context) {
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) base = context.getFilesDir();
            File dir = new File(base, "crash");
            if (!dir.isDirectory()) return null;
            return dir.listFiles((d, name) -> name.endsWith(".txt"));
        } catch (Throwable e) {
            return null;
        }
    }

    private static String remoteName(Context context, File f) {
        String version = "unknown";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = info.versionName;
        } catch (Throwable ignored) {
        }
        String model = (Build.MANUFACTURER + "_" + Build.MODEL).replaceAll("[^\\w\\-一-鿿]", "_");
        String time = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(f.lastModified()));
        return "crash_" + version + "_" + model + "_" + time + ".txt";
    }

    /** 建目录：已有会返回 405，也算通；抛异常表示这条线不可用（老设备 https 握手失败会在这里炸，交给外层换下一条） */
    private static int mkdirs(String dav) throws IOException {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(dav + UriEncoder.encode(FOLDER))
                .method("MKCOL", null)
                .header("Authorization", basic())
                .build();
        try (okhttp3.Response resp = CLIENT.newCall(req).execute()) {
            return resp.code();
        }
    }

    private static boolean upload(String dav, File file, String name) {
        try {
            byte[] data = read(file);
            if (data == null || data.length == 0) return true; // 空文件直接算传完，好删掉
            int code = request("PUT", dav + UriEncoder.encode(FOLDER + name), data);
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String basic() {
        return "Basic " + android.util.Base64.encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private static int request(String method, String url, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("Authorization", basic());
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 1024 * 1024)]; // 最多读 1MB
            int n = in.read(data);
            return n <= 0 ? null : (n == data.length ? data : Arrays.copyOf(data, n));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 路径段编码：中文目录名/文件名按 UTF-8 百分号编码，空格等也一并处理 */
    private static class UriEncoder {
        static String encode(String path) {
            StringBuilder sb = new StringBuilder();
            for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
                char c = (char) (b & 0xFF);
                if (isSafe(c)) sb.append(c);
                else sb.append('%').append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        }

        private static boolean isSafe(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/';
        }
    }
}

package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 崩溃日志自动回传：App 启动后（延迟一小会儿，避开启动高峰）在后台线程把
 * files/crash/ 下 CrashGuard 落盘的堆栈传到归档 WebDAV 的「错误日志收集」目录，
 * 传成功就删本地文件，不攒垃圾。任何一步失败都静默——绝不因为这个功能再崩一次。
 *
 * 文件名：crash_<版本>_<设备型号>_<时间戳>.txt，一眼能看出哪台设备、哪个版本出的问题。
 */
public class CrashReporter {

    // App 专用的错误日志 WebDAV（只收日志，跟归档网盘分开）
    private static final String DAV = "https://www.12356.cool/dav";
    private static final String USER = "error";
    private static final String PASS = "errorcode";
    private static final String FOLDER = "/错误日志收集/";
    private static final int MAX_FILES = 5;      // 一次最多补传 5 份，防积压时拖慢启动
    private static final int TIMEOUT = 15000;

    private CrashReporter() {
    }

    /** 启动后调用：延迟 20 秒、后台线程执行 */
    public static void schedule(Context context) {
        Task.schedule(() -> uploadPending(context.getApplicationContext()), 20, TimeUnit.SECONDS);
    }

    private static void uploadPending(Context context) {
        try {
            File[] files = list(context);
            if (files == null || files.length == 0) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            mkdirs();
            int count = 0;
            for (File f : files) {
                if (count >= MAX_FILES) break;
                if (upload(f, remoteName(context, f))) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    count++;
                } else {
                    break; // 网络不通就整体收工，下次启动再试
                }
            }
        } catch (Throwable ignored) {
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

    private static void mkdirs() throws IOException {
        // 已存在会返回 405，无所谓，只为确保目录在；中文目录名要百分号编码
        request("MKCOL", DAV + UriEncoder.encode(FOLDER), null);
    }

    private static boolean upload(File file, String name) {
        try {
            byte[] data = read(file);
            if (data == null || data.length == 0) return true; // 空文件直接算传完，好删掉
            int code = request("PUT", DAV + UriEncoder.encode(FOLDER + name), data);
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            return false;
        }
    }

    private static int request(String method, String url, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            String auth = android.util.Base64.encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + auth);
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

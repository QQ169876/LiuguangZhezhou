package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static File getWall(int index) {
        return Path.files("wallpaper_" + index);
    }

    public static File getWallCache() {
        return Path.files("wallpaper_cache");
    }

    public static void openFile(File file) {
        openFile(file, false);
    }

    /**
     * 拿系统里能处理这种文件的应用去打开它。
     *
     * chooser = true 时先弹系统的「用其他应用打开」列表，把「用谁开」的决定权留给用户 ——
     * 局域网推过来的东西，接收端自己没资格替对面挑应用。
     */
    public static void openFile(File file, boolean chooser) {
        if (file == null || !file.exists()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setDataAndType(openUri(file), getMimeType(file.getName()));
            Intent target = intent;
            if (chooser) {
                target = Intent.createChooser(intent, ResUtil.getString(R.string.push_open_with));
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            App.get().startActivity(target);
        } catch (Throwable e) {
            Notify.show(R.string.push_open_fail);
        }
    }

    /**
     * 推过来的安装包：直接把系统安装器拉起来装。
     *
     * 之前只做了一句 ACTION_VIEW，对端经常一点反应都没有，三个坑：
     * 1. mime 靠 guessContentTypeFromName 猜，.apk 基本猜不出来，落到 "* / *"，
     *    系统不知道该拿什么开 —— TV 上的表现就是「什么都没发生」；
     * 2. Android 8 起装未知应用要先给授权（canRequestPackageInstalls），没给的话 Intent 会被系统吞掉；
     * 3. 文件原先落在 sdcard 根目录，Android 10 分区存储下常常压根没写进去，装什么装。
     *
     * 安卓 6（我们的兼容版就是 minSdk 23）还得另眼看待：
     * 它的系统安装器不认 content://、只认 file://，而去读别的应用私有目录里的文件又常被系统拦住，
     * 所以先往公共的「下载」目录落一份，拿那份去拉安装器；两条路都不行再把位置告诉用户。
     */
    public static void installApk(File file) {
        String name = file == null ? "" : file.getName();
        if (!installPermissionGranted()) {
            openInstallPermission();
            Notify.show(R.string.push_install_perm);
            return;
        }
        String left = installApk(file, name);
        if (left == null) return;
        Notify.show(left.isEmpty() ? ResUtil.getString(R.string.push_install_fail) : ResUtil.getString(R.string.push_install_saved, left));
    }

    /**
     * 把系统安装器拉起来装这个包，成败交给调用方处理（更新流程要自己决定提示什么）。
     *
     * 返回 null = 安装界面已经起来了；返回空串 = 彻底没拉起来；返回路径 = 拉不起来，
     * 但已经在「下载」目录留了一份（路径给人看）。saveAs 用来指定下载目录里那份的名字。
     *
     * 三个老坑这里一并绕开：
     * 1. mime 必须写死 apk（靠文件名猜会落到 *／*，TV 上就是「什么都没发生」）；
     * 2. 安卓 7 起走 content:// 还得带 FLAG_GRANT_READ_URI_PERMISSION，不给读权限安装器一闪就没；
     * 3. 安卓 6 的安装器不认 content://，而私有目录里的文件它根本读不到，所以先往公共下载目录落一份再装。
     */
    public static String installApk(File file, String saveAs) {
        String name = TextUtils.isEmpty(saveAs) ? (file == null ? "" : file.getName()) : saveAs;
        if (file == null || !file.exists() || file.length() <= 0) return "";
        try {
            File target = file;
            String saved = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                saved = saveToDownload(file, name);
                if (saved != null) target = new File(saved);
                makeWorldReadable(target);
                if (launch(apkIntent(target, Intent.ACTION_INSTALL_PACKAGE))) return null;
                if (launch(apkIntent(target, Intent.ACTION_VIEW))) return null;
            } else {
                // 先 INSTALL_PACKAGE（更明确），个别精简系统没这个 activity 就退 ACTION_VIEW
                if (launch(apkIntent(file, Intent.ACTION_INSTALL_PACKAGE))) return null;
                if (launch(apkIntent(file, Intent.ACTION_VIEW))) return null;
            }
            return saved != null ? saved : keep(file, name);
        } catch (Throwable e) {
            return keep(file, name);
        }
    }

    /** 拉不起来的时候，至少在「下载」目录留一份让人手动装 */
    private static String keep(File file, String name) {
        String path = saveToDownload(file, name);
        return path == null ? "" : path;
    }

    /**
     * 有没有「安装未知应用」的权限：安卓 8 起按 App 单独授权，安卓 6 是全机一个总开关。
     * 没这个权限时系统会把安装 Intent 直接吞掉，界面上什么都不会发生。
     */
    public static boolean installPermissionGranted() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return App.get().getPackageManager().canRequestPackageInstalls();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return installNonMarketAllowed();
            return true; // 安卓 7 还是老规矩，装的时候系统自己会问
        } catch (Throwable e) {
            return true;
        }
    }

    /** 带用户去开「允许安装未知应用」（有些盒子把设置页精简掉了，拉不起来也不崩） */
    public static void openInstallPermission() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:".concat(App.get().getPackageName())));
            } else {
                intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            App.get().startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 安卓 6 及更早的那个全局开关。读不到就当开着的，别把人拦在门外 */
    private static boolean installNonMarketAllowed() {
        try {
            return Settings.Secure.getInt(App.get().getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 1) != 0;
        } catch (Throwable e) {
            return true;
        }
    }

    private static Intent apkIntent(File file, String action) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(getShareUri(file), APK_MIME);
        } else {
            intent.setDataAndType(Uri.fromFile(file), APK_MIME);
        }
        return intent;
    }

    private static boolean launch(Intent intent) {
        try {
            if (intent.resolveActivity(App.get().getPackageManager()) == null) return false;
            App.get().startActivity(intent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 交给别的应用打开的 Uri：安卓 7 起不能直接把 file:// 递出去（会抛 FileUriExposedException），得走 FileProvider；
     * 安卓 6 反过来 —— 它的系统安装器不认 content://，只认 file://。
     */
    private static Uri openUri(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return getShareUri(file);
        makeWorldReadable(file);
        return Uri.fromFile(file);
    }

    /**
     * 用 file:// 把私有目录里的文件交给别的应用时，对方是另一个 uid，
     * 不放开读权限它连文件都读不到（表现为安装器闪一下就没了）。
     * 文件本身要可读，父目录还得可进入。
     */
    private static void makeWorldReadable(File file) {
        try {
            file.setReadable(true, false);
            File dir = file.getParentFile();
            for (int i = 0; dir != null && i < 8; ++i, dir = dir.getParentFile()) {
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void gzipCompress(File target) {
        byte[] buffer = new byte[16384];
        try (FileInputStream is = new FileInputStream(target); GZIPOutputStream os = new GZIPOutputStream(new FileOutputStream(target.getAbsolutePath() + ".gz"))) {
            int read;
            while ((read = is.read(buffer)) > 0) os.write(buffer, 0, read);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Path.clear(target);
        }
    }

    public static void gzipDecompress(File target, File path) {
        byte[] buffer = new byte[16384];
        try (GZIPInputStream is = new GZIPInputStream(new BufferedInputStream(new FileInputStream(target))); BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(path))) {
            int read;
            while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void zipDecompress(File target, File path) {
        zipDecompress(target, path, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public static boolean zipDecompress(File target, File path, int maxEntries, long maxBytes) {
        try (ZipFile zip = new ZipFile(target)) {
            Enumeration<?> entries = zip.entries();
            String root = path.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[16384];
            long totalBytes = 0;
            int totalEntries = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                if (++totalEntries > maxEntries) throw new IOException("Archive entry limit exceeded");
                File out = new File(path, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (entry.isDirectory()) out.mkdirs();
                else try (BufferedInputStream is = new BufferedInputStream(zip.getInputStream(entry)); BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(Path.create(out)))) {
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > maxBytes) throw new IOException("Archive size limit exceeded");
                        os.write(buffer, 0, read);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void clearCache(Callback callback) {
        Task.execute(() -> {
            Path.clear(Path.cache());
            App.post(callback::success);
        });
    }

    public static void getCacheSize(Callback callback) {
        Task.execute(() -> {
            String usage = byteCountToDisplaySize(getDirectorySize(Path.cache()));
            App.post(() -> callback.success(usage));
        });
    }

    public static long getDirectorySize(File dir) {
        long size = 0;
        if (dir == null) return 0;
        if (dir.isDirectory()) for (File file : Path.list(dir)) size += getDirectorySize(file);
        else size = dir.length();
        return size;
    }

    public static long getAvailableStorageSpace(File file) {
        try {
            StatFs stat = new StatFs(file.getAbsolutePath());
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            return 0;
        }
    }

    public static Uri getShareUri(String path) {
        return getShareUri(new File(path.replace("file://", "")));
    }

    public static Uri getShareUri(File file) {
        return FileProvider.getUriForFile(App.get(), App.get().getPackageName() + ".provider", file);
    }

    /**
     * 猜文件的类型，交给系统去挑应用。
     *
     * 光靠 guessContentTypeFromName 很不顶用：apk、mkv、字幕这些它一律返回 null，
     * 落到 "* / *" 之后系统就不知道该拿什么开，TV 上的表现是「什么都没发生」。
     * 常见后缀自己给一份，剩下的再交回它去猜。
     */
    private static String getMimeType(String fileName) {
        String result = MIME.get(extension(fileName));
        if (!TextUtils.isEmpty(result)) return result;
        result = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(result) ? "*/*" : result;
    }

    private static String extension(String name) {
        int index = name == null ? -1 : name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase();
    }

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("apk", APK_MIME);
        MIME.put("mp3", "audio/mpeg");
        MIME.put("flac", "audio/flac");
        MIME.put("m4a", "audio/mp4");
        MIME.put("wav", "audio/x-wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("mp4", "video/mp4");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("m3u8", "application/vnd.apple.mpegurl");
        MIME.put("ts", "video/mp2t");
        MIME.put("avi", "video/x-msvideo");
        MIME.put("flv", "video/x-flv");
        MIME.put("webm", "video/webm");
        MIME.put("3gp", "video/3gpp");
        MIME.put("mov", "video/quicktime");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("json", "application/json");
        MIME.put("txt", "text/plain");
        MIME.put("log", "text/plain");
        MIME.put("xml", "text/xml");
        MIME.put("zip", "application/zip");
        MIME.put("pdf", "application/pdf");
        MIME.put("html", "text/html");
        MIME.put("m3u", "audio/x-mpegurl");
        MIME.put("srt", "application/x-subrip");
        MIME.put("ass", "text/x-ssa");
        MIME.put("ssa", "text/x-ssa");
        MIME.put("vtt", "text/vtt");
        MIME.put("lrc", "application/x-subrip");
    }

    /**
     * 把文件另存一份到系统的「下载」目录，返回给人看的位置（比如 Download/xxx.apk）。
     *
     * 用途：有的设备（模拟器、改过的盒子）拉不起系统安装器，装不上就干瞪眼，
     * 存一份到下载目录，用户去文件管理里点一下就能手动装。
     *
     * 两条路：能直接写公共目录就直接写（老系统、或是给了全部文件访问权限的），
     * 写不了就走 MediaStore（Android 10 起分区存储，下载目录要靠它写）。
     */
    public static String saveToDownload(File src, String name) {
        if (src == null || !src.exists() || src.length() <= 0) return null;
        String path = saveToDownloadDir(src, name);
        if (path != null) return path;
        return saveToMediaStore(src, name);
    }

    private static String saveToDownloadDir(File src, String name) {
        File dst = null;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            cleanDownload(dir, name);
            dst = new File(dir, name);
            copy(src, dst);
            if (dst.exists() && dst.length() == src.length()) return dst.getAbsolutePath();
        } catch (Throwable ignored) {
            // 分区存储不让直接写，交给 MediaStore
        }
        Path.clear(dst); // 写了一半的别留着占地方
        return null;
    }

    /** 只留最新这一版的安装包，老版本的别在下载目录里堆着 */
    private static void cleanDownload(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            String n = file.getName();
            if (n.endsWith(".apk") && n.contains("流光褶皱") && !n.equals(keep)) Path.clear(file);
        }
    }

    private static String saveToMediaStore(File src, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try {
            ContentResolver resolver = App.get().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) return null;
                try (InputStream is = new FileInputStream(src)) {
                    byte[] buffer = new byte[65536];
                    int len;
                    while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                }
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
        }
    }

    public static String byteCountToDisplaySize(long size) {
        if (size <= 0) return ResUtil.getString(R.string.none);
        String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}

package com.fongmi.android.tv.utils;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

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
            Uri uri = openUri(file);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // 少了这句，对方应用一样 Permission Denial
                grant(uri, intent);
            }
            intent.setDataAndType(uri, getMimeType(file.getName()));
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
     * 2. Android 8 起装未知应用要先给授权（canRequestPackageInstalls）——但不在这儿拦：
     *    先照老办法把安装器拉起来，系统自己会处理授权；真拉不起来了才带人去开设置；
     * 3. 文件原先落在 sdcard 根目录，Android 10 分区存储下常常压根没写进去，装什么装。
     *
     * 安卓 6（我们的兼容版就是 minSdk 23）还得另眼看待：
     * 它的系统安装器不认 content://、只认 file://，而去读别的应用私有目录里的文件又常被系统拦住，
     * 所以先往公共的「下载」目录落一份，拿那份去拉安装器；两条路都不行再把位置告诉用户。
     */
    public static void installApk(File file) {
        String name = file == null ? "" : file.getName();
        // 跟更新流程一个路数：先把安装器拉起来，拉不起来再去看是不是权限没开
        String left = installApk(file, name);
        if (left == null) return;
        if (!installPermissionGranted()) {
            openInstallPermission();
            Notify.show(R.string.push_install_perm);
            return;
        }
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
                // ⚠️ 顺序是「先 VIEW 后 INSTALL_PACKAGE」，别倒过来——这是小米上能不能装的关键。
                // 642（5.6.82）起自动更新从 ACTION_VIEW 改成了优先 ACTION_INSTALL_PACKAGE、
                // 还带上 EXTRA_NOT_UNKNOWN_SOURCE，小米（HyperOS/MIUI）就会进「安装包扫描」那条流程，
                // 扫描是安装器在 intent 授权链之外读我们的 FileProvider，授权对不上，于是报：
                //   解析软件包出现问题。(11) Permission Denial: opening provider
                //   androidx.core.content.FileProvider ... that is not exported from UID ...
                // 早期版本（≤5.6.81）自动更新走的正是 ACTION_VIEW，在小米上一直是好的，所以恢复它打头；
                // 个别精简系统没有 VIEW 的处理者，再退到更明确的 INSTALL_PACKAGE。
                if (launch(apkIntent(file, Intent.ACTION_VIEW))) return null;
                if (launch(apkIntent(file, Intent.ACTION_INSTALL_PACKAGE))) return null;
            }
            return saved != null ? saved : keep(file, name);
        } catch (Throwable e) {
            return keep(file, name);
        }
    }

    /** 安装流程的异步回调：left 含义同 installApk(file, saveAs)（null=已交给系统；""=没戏；其他=已存到下载目录的路径） */
    public interface Installer {

        void onDone(String left);
    }

    /**
     * 安装包：**先让系统「包安装器会话」把包收走**，不行再退回老办法去拉安装器。
     *
     * 为什么要多这一条路：小米（HyperOS/MIUI）上把 FileProvider 的 content:// 递给安装器，
     * 安装器在「安装包扫描」那一步读文件时授权已经对不上，直接报——
     *   解析软件包出现问题。(11) Permission Denial: opening provider ... that is not exported from UID ...
     * 而会话这条路是**系统从我们进程里把包读走**（我们只管往里写），
     * 安装器根本不碰我们的 FileProvider，也就没有 URI 授权这一关。
     * 会话走不通（个别 ROM/精简系统）就退回原来的 launch，行为跟以前一样，不会更糟。
     *
     * ⚠️ 写 80MB 不能放主线程，所以整个是异步的，结果回主线程给你。
     */
    public static void installApkAsync(File file, String saveAs, Installer installer) {
        final String name = TextUtils.isEmpty(saveAs) ? (file == null ? "" : file.getName()) : saveAs;
        if (file == null || !file.exists() || file.length() <= 0) {
            App.post(() -> installer.onDone(""));
            return;
        }
        new Thread(() -> {
            boolean ok = sessionInstall(file);
            App.post(() -> installer.onDone(ok ? null : installApk(file, name)));
        }, "install-apk").start();
    }

    /** 系统包安装器会话：把包装进会话里 commit 出去，剩下的交给系统（它会弹自己的安装确认界面） */
    private static boolean sessionInstall(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        PackageInstaller.Session session = null;
        try {
            PackageInstaller installer = App.get().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(file.length());
            int id = installer.createSession(params);
            session = installer.openSession(id);
            try (OutputStream os = session.openWrite("base.apk", 0, file.length()); InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                session.fsync(os);
            }
            Intent result = new Intent(App.get(), com.fongmi.android.tv.receiver.ActionReceiver.class);
            result.setAction(App.get().getPackageName() + ".install.result");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE; // 系统要往里面回塞结果
            PendingIntent pending = PendingIntent.getBroadcast(App.get(), id, result, flags);
            session.commit(pending.getIntentSender());
            DebugLog.d("Install", "已交给系统包安装器：" + file.getName());
            return true;
        } catch (Throwable e) {
            DebugLog.d("Install", "会话安装走不通，退回拉安装器：" + e);
            try {
                if (session != null) session.abandon();
            } catch (Throwable ignored) {
            }
            return false;
        } finally {
            try {
                if (session != null) session.close();
            } catch (Throwable ignored) {
            }
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
        // 这里故意**不加** CATEGORY_DEFAULT、也不塞 EXTRA_NOT_UNKNOWN_SOURCE：
        // 那两样是 640/642 加进来的，正是小米上装不了的开端（细见 installApk 里的注释）。
        // 早期版本（≤5.6.81）拿 ACTION_VIEW 装一直是好的，这次就是退回那个干净的样子。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri uri = getShareUri(file);
            intent.setDataAndType(uri, APK_MIME);
            grant(uri, intent); // 小米这关必须过：光靠 flag 它读不到
        } else {
            intent.setDataAndType(Uri.fromFile(file), APK_MIME);
        }
        return intent;
    }

    /**
     * 把「读这个 apk」的权限**显式**发给所有可能接手的安装器。
     *
     * 2026-10-12 小米（MIUI）实测报错：
     *   Permission Denial: opening provider androidx.core.content.FileProvider from
     *   ProcessRecord{... com.miui.packageinstaller ...} that is not exported from UID ...
     * 原因：`FLAG_GRANT_READ_URI_PERMISSION` 只授权给 startActivity 直接拉起的那一个包；
     * MIUI 的「安装包扫描」是安装器自己进程里再往下传一道，传递过程中授权丢了，于是读不到。
     * 所以这里对候选列表逐个 `grantUriPermission`（进程级授权，重启前一直有效），
     * 再把各家安装器的包名也一并授上（有的 ROM 它压根不出现在 query 结果里）。
     *
     * 另外挂一份 ClipData 到 intent 上：走 chooser/中转 Activity 时 URI 授权靠它才带得过去。
     */
    public static void grant(Uri uri, Intent intent) {
        java.util.Set<String> pkgs = new java.util.HashSet<>();
        try {
            // 拿一个单独的「带 CATEGORY_DEFAULT 的看 apk」去列候选：intent 本身没有 category，
            // 直接拿它配 MATCH_DEFAULT_ONLY 会一个都列不出来
            Intent query = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT);
            query.setDataAndType(uri, APK_MIME);
            java.util.List<android.content.pm.ResolveInfo> list = App.get().getPackageManager().queryIntentActivities(query, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (list != null) for (android.content.pm.ResolveInfo info : list) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) pkgs.add(info.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        for (String pkg : INSTALLERS) pkgs.add(pkg);
        for (String pkg : pkgs) {
            try {
                App.get().grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        try {
            intent.setClipData(android.content.ClipData.newUri(App.get().getContentResolver(), "apk", uri));
        } catch (Throwable ignored) {
        }
    }

    /** 各家 ROM 的安装器包名：不出现在候选里也一并授权，免得又撞「not exported」 */
    private static final String[] INSTALLERS = {
            "com.miui.packageinstaller",                 // 小米（这次报错的就是它）
            "com.android.packageinstaller",              // 原生 / 大部分 ROM
            "com.google.android.packageinstaller",       // Pixel
            "com.samsung.android.packageinstaller",      // 三星
            "com.huawei.android.packageinstaller",       // 华为
            "com.coloros.packageinstaller",              // OPPO / 一加
            "com.vivo.packageinstaller",                 // vivo
            "com.bbk.appstore",                          // vivo 应用商店
            "com.miui.securitycenter",                   // 小米安全中心（有时由它接管扫描）
    };

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

    /**
     * 解压 RAR（伪射手那边不少字幕包是 .rar，java.util.zip 只认 zip）。
     *
     * 用 junrar 解。它内部会碰到 java.nio.file（API 26 才有的类），
     * Android 7.x（API 24/25）上会抛 NoClassDefFoundError——那是 Error 不是 Exception，
     * 所以这里 catch Throwable 兜住：老设备最多是这类包解不开，绝不能把 App 带崩。
     */
    public static boolean rarDecompress(File target, File path, int maxEntries, long maxBytes) {
        long[] total = new long[1];
        int count = 0;
        try (Archive rar = new Archive(target)) {
            String root = path.getCanonicalPath() + File.separator;
            for (FileHeader header : rar.getFileHeaders()) {
                if (header == null || header.isDirectory()) continue;
                if (++count > maxEntries) throw new IOException("Archive entry limit exceeded");
                String name = header.getFileName();
                if (TextUtils.isEmpty(name)) continue;
                File out = new File(path, name.replace('\\', File.separatorChar));
                if (!out.getCanonicalPath().startsWith(root)) continue; // 防路径穿越
                try (OutputStream os = limited(new FileOutputStream(Path.create(out)), total, maxBytes)) {
                    rar.extractFile(header, os);
                }
            }
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 边写边数，超量就停，避免一个坏包把存储写满 */
    private static OutputStream limited(OutputStream os, long[] total, long maxBytes) {
        return new BufferedOutputStream(os) {

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                total[0] += len;
                if (total[0] > maxBytes) throw new IOException("Archive size limit exceeded");
                super.write(b, off, len);
            }

            @Override
            public void write(int b) throws IOException {
                total[0] += 1;
                if (total[0] > maxBytes) throw new IOException("Archive size limit exceeded");
                super.write(b);
            }
        };
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

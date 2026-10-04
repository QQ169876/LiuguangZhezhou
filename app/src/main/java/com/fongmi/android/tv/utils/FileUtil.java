package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {

    public static File getWall(int index) {
        return Path.files("wallpaper_" + index);
    }

    public static File getWallCache() {
        return Path.files("wallpaper_cache");
    }

    public static void openFile(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(getShareUri(file), FileUtil.getMimeType(file.getName()));
        App.get().startActivity(intent);
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

    private static String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
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

package com.fongmi.android.tv.utils;
import java.util.stream.Collectors;

import android.text.TextUtils;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SubtitleArchive {

    private static final String ZIP = ".zip";
    private static final String CACHE_DIR = "subtitle/";
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 128L * 1024 * 1024;
    private static final String[] EXTENSIONS = {".srt", ".ass", ".ssa", ".vtt", ".ttml", ".xml", ".dfxp"};

    public static boolean isZip(String name, String url) {
        return hasExtension(name, ZIP) || hasExtension(url, ZIP);
    }

    public static boolean isSupported(String name, String url) {
        return isSubtitle(name) || isSubtitle(url) || isZip(name, url);
    }

    public static File getFile(String url) {
        return Path.cache(CACHE_DIR + getKey(url) + ZIP);
    }

    public static File getDir(String url) {
        return Path.cache(CACHE_DIR + getKey(url));
    }

    public static Download createDownload(String url) {
        return Download.create(url, getFile(url)).maxBytes(MAX_DOWNLOAD_BYTES);
    }

    public static List<File> unzip(File archive, File dir) {
        Path.clear(dir);
        if (!FileUtil.zipDecompress(archive, dir, MAX_ENTRIES, MAX_EXTRACTED_BYTES)) Path.clear(dir);
        return findSubtitles(dir);
    }

    private static List<File> findSubtitles(File dir) {
        // 原来用 java.nio.file.Files.walk()，那是 API 26 才有的类，Android 6 上会抛
        // NoClassDefFoundError（属于 Error，原来的 catch (Exception) 根本抓不住，直接崩）。
        // 改成普通 File 递归，等价且全版本可用；限制层级避免异常目录结构拖死。
        try {
            List<File> result = new ArrayList<>();
            collectSubtitles(dir, result, 0);
            return result;
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static void collectSubtitles(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectSubtitles(file, out, depth + 1);
            else if (isSubtitle(file)) out.add(file);
        }
    }

    private static String getKey(String url) {
        return Util.md5(stripUrlSuffix(url));
    }

    private static boolean isSubtitle(File file) {
        return isSubtitle(file.getName());
    }

    private static boolean isSubtitle(String text) {
        for (String extension : EXTENSIONS) if (hasExtension(text, extension)) return true;
        return false;
    }

    private static boolean hasExtension(String text, String extension) {
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("://")) lower = stripUrlSuffix(lower);
        return lower.endsWith(extension);
    }

    private static String stripUrlSuffix(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = query < 0 ? url.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end);
    }
}

package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SubtitleArchive;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class SubtitleSearchItem {

    private final int id;
    private final String name;
    private final String url;
    private final String lang;
    private final boolean archive;

    /** 这条是哪一家字幕站来的：「自动」模式下几家结果混在一起，标出来才挑得动 */
    private int source;

    private SubtitleSearchItem(int id, String name, String url, String lang, boolean archive) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.lang = lang;
        this.archive = archive;
    }

    public static SubtitleSearchItem from(int id, String name, String url, String lang) {
        return new SubtitleSearchItem(id, name, url, lang, false);
    }

    /**
     * 下载地址是个压缩包，但链接上看不出扩展名（OpenSubtitles 的 zip 下载链接就是一串无后缀地址），
     * 得单独打个标记，不然 {@link #isZip()} 认不出来，会被当成单个字幕文件直接丢给播放器。
     */
    public static SubtitleSearchItem archive(int id, String name, String url, String lang) {
        return new SubtitleSearchItem(id, name, url, lang, true);
    }

    public static List<SubtitleSearchItem> fromFiles(List<File> files, String lang) {
        List<SubtitleSearchItem> items = new ArrayList<>();
        for (File file : files) items.add(new SubtitleSearchItem(0, file.getName(), file.getAbsolutePath(), lang, false));
        return items;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? String.valueOf(id) : name;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public String getLang() {
        return TextUtils.isEmpty(lang) ? "" : lang;
    }

    /** 打上来源标记（几家合并展示时用），返回自己方便连着写 */
    public SubtitleSearchItem source(int source) {
        this.source = source;
        return this;
    }

    public String getText() {
        String label = sourceLabel();
        StringBuilder text = new StringBuilder();
        if (!label.isEmpty()) text.append('[').append(label).append("] ");
        text.append(getName());
        if (!TextUtils.isEmpty(getLang())) text.append(" - ").append(getLang());
        return text.toString();
    }

    private String sourceLabel() {
        return switch (source) {
            case SubtitleSetting.SOURCE_ASSRT -> ResUtil.getString(R.string.subtitle_tag_assrt);
            case SubtitleSetting.SOURCE_OPENSUB -> ResUtil.getString(R.string.subtitle_tag_opensub);
            case SubtitleSetting.SOURCE_SUBDL -> ResUtil.getString(R.string.subtitle_tag_subdl);
            default -> "";
        };
    }

    public boolean hasUrl() {
        return !getUrl().isEmpty();
    }

    public boolean isZip() {
        return archive || SubtitleArchive.isZip(getName(), getUrl());
    }

    public Sub toSub() {
        return Sub.from(getName(), getUrl(), getLang(), TrackUtil.getSubtitleMimeType(getName()));
    }
}

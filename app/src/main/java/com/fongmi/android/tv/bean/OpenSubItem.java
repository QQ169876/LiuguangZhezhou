package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

/**
 * OpenSubtitles（rest.opensubtitles.org）搜索结果里我们用到的那几个字段。
 *
 * 这家是全球最大的字幕站，而且**不用注册、不用 token**，正好给伪射手当备用：
 * 伪射手要 token（没填就搜不了），中文片也更全；这家英文/外语片更全。两家互补。
 */
public class OpenSubItem {

    @SerializedName("IDSubtitleFile")
    private String id;
    @SerializedName("SubFileName")
    private String fileName;
    @SerializedName("SubDownloadLink")
    private String downloadLink;
    @SerializedName("ZipDownloadLink")
    private String zipLink;
    @SerializedName("SubLanguageID")
    private String langId;
    @SerializedName("LanguageName")
    private String langName;
    @SerializedName("MovieReleaseName")
    private String releaseName;
    @SerializedName("SubFormat")
    private String format;

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDownloadLink() {
        return downloadLink;
    }

    public String getZipLink() {
        return zipLink;
    }

    public String getLangId() {
        return langId;
    }

    public String getLangName() {
        return langName;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public String getFormat() {
        return format;
    }

    /** 显示用的名字：优先字幕文件名，没有就用发布名补上格式后缀 */
    public String getName() {
        if (!TextUtils.isEmpty(fileName)) return fileName;
        String base = TextUtils.isEmpty(releaseName) ? "" : releaseName;
        if (base.isEmpty()) return "";
        return TextUtils.isEmpty(format) ? base : base + "." + format;
    }

    public String getLang() {
        if (!TextUtils.isEmpty(langName)) return langName;
        return TextUtils.isEmpty(langId) ? "" : langId;
    }

    public int getIntId() {
        try {
            return Integer.parseInt(TextUtils.isEmpty(id) ? "0" : id.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

/**
 * SubDL 搜索结果里我们用到的那几个字段。
 *
 * 它家返回的是一条条"字幕包"（多数是 zip），url 是相对路径（/subtitle/xxx.zip），
 * 要拼上 dl.subdl.com 才是完整下载地址（拼接在 SubdlApi 里做）。
 */
public class SubdlItem {

    @SerializedName("url")
    private String url;
    @SerializedName("name")
    private String name;
    @SerializedName("release_name")
    private String releaseName;
    @SerializedName("language")
    private String language;
    @SerializedName("lang")
    private String lang;
    @SerializedName("format")
    private String format;
    @SerializedName("n_id")
    private String id;

    public String getUrl() {
        return url;
    }

    public String getName() {
        return name;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public String getFormat() {
        return format;
    }

    public String getId() {
        return id;
    }

    /**
     * 语言字段名它家文档里没写死，language / lang 都认，谁有值用谁。
     * language 给的是 "ZH" 这种代码，lang 给的是 "chinese-bg-code" 那种内部串，
     * 代码更适合展示，顺手翻成中文。
     */
    public String getLang() {
        String value = !TextUtils.isEmpty(language) ? language : (TextUtils.isEmpty(lang) ? "" : lang);
        String trim = value.trim();
        if (trim.equalsIgnoreCase("zh") || trim.equalsIgnoreCase("zh-cn") || trim.equalsIgnoreCase("zho")) return "中文";
        if (trim.equalsIgnoreCase("en") || trim.equalsIgnoreCase("eng")) return "英文";
        return trim;
    }
}

package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

/**
 * TMDB 上查到的一部作品（演员演过的片子）。
 *
 * 我们只借 TMDB 把「片名」查准——站点给的资源名经常是《XXX 第2季 国语》这种脏名字，
 * 直接拿去搜经常搜不到；TMDB 只提供准确的作品名，找片源仍然交回给站点。
 */
public class TmdbWork {

    public static final int TYPE_MOVIE = 0;
    public static final int TYPE_TV = 1;
    /** 不是 TMDB 条目：智能搜索里模型给的题材词/关键词，点下去照样拿名字去全站搜 */
    public static final int TYPE_WORD = 2;

    private final String name;
    private final String year;
    private final String role;
    private final int type;
    private String poster = "";  // TMDB 海报路径（/xxxx.jpg），没有就空串
    private String rating = "";  // 评分（"7.8"），没有就空串

    public TmdbWork(String name, String year, String role, int type) {
        this.name = name == null ? "" : name.trim();
        this.year = year == null ? "" : year.trim();
        this.role = role == null ? "" : role.trim();
        this.type = type;
    }

    /** 顺手补上海报/评分（discover、search、credits 里带得出来就带上），链式调用方便 */
    public TmdbWork with(String poster, double rating) {
        this.poster = poster == null ? "" : poster.trim();
        this.rating = rating > 0 ? String.format(java.util.Locale.US, "%.1f", rating) : "";
        return this;
    }

    /** 海报完整地址；image.tmdb.org 国内时通时断，加载不出来就显示占位图，名字年份照样能用 */
    public String getPoster() {
        return poster.isEmpty() ? "" : "https://image.tmdb.org/t/p/w185" + poster;
    }

    public String getRating() {
        return rating;
    }

    public String getName() {
        return name;
    }

    /** 年份（"2024"），没有就空串 */
    public String getYear() {
        return year;
    }

    /** 演的角色名，顺带显示，帮用户认片 */
    public String getRole() {
        return role;
    }

    public int getType() {
        return type;
    }

    public boolean isMovie() {
        return type == TYPE_MOVIE;
    }

    /** 列表上显示的名字：带年份，例「玫瑰的故事（2024）」 */
    public String getTitle() {
        return year.isEmpty() ? name : name + "（" + year + "）";
    }

    /** 同名同年的算一部：TMDB 的 cast 里偶尔会把同一部列两遍（演员一人分饰两角时） */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TmdbWork it)) return false;
        return type == it.type && name.equals(it.name) && year.equals(it.year);
    }

    @Override
    public int hashCode() {
        return (name + "|" + year + "|" + type).hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return getTitle();
    }
}

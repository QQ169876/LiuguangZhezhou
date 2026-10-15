package com.fongmi.android.tv.bean;

import java.util.ArrayList;
import java.util.List;

public final class SubtitleSearchPage {

    private final List<SubtitleSearchItem> items;
    private final List<String> hints;
    private final int resultCount;

    private SubtitleSearchPage(List<SubtitleSearchItem> items, int resultCount, List<String> hints) {
        this.items = items;
        this.resultCount = resultCount;
        this.hints = hints == null ? new ArrayList<>() : hints;
    }

    public static SubtitleSearchPage from(List<SubtitleSearchItem> items, int resultCount) {
        return new SubtitleSearchPage(items, resultCount, null);
    }

    /** 带「这片子还叫什么」的回执：见 {@link #getHints()} */
    public static SubtitleSearchPage withHints(List<SubtitleSearchItem> items, int resultCount, List<String> hints) {
        return new SubtitleSearchPage(items, resultCount, hints);
    }

    public List<SubtitleSearchItem> getItems() {
        return items;
    }

    public int getResultCount() {
        return resultCount;
    }

    /**
     * 这家顺带查出来的「这片子的其它叫法」（英文原名、IMDb 编号等）。
     *
     * 为什么要有它：中文片名在 OpenSubtitles 这类国外站上压根搜不到（实测「庆余年」「沙丘」都是 0 条），
     * 而拿中文名去问 SubDL，反倒能把这片子的英文名（Joy of Life）和 IMDb 编号（11273352）问出来。
     * 有了这两个，其它家就能换个名字接着搜 —— 不然中文剧在这些站上永远 0 条。
     */
    public List<String> getHints() {
        return hints;
    }
}

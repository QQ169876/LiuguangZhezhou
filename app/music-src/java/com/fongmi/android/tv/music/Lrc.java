package com.fongmi.android.tv.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析。一行行把 [mm:ss.xx] 拆出来，播放时按进度定位当前行。
 */
public class Lrc {

    private static final Pattern LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?](.*)");

    private final List<Entry> entries;

    private Lrc(List<Entry> entries) {
        this.entries = entries;
    }

    public static Lrc parse(String text) {
        List<Entry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) return new Lrc(entries);
        for (String raw : text.split("\n")) {
            Matcher matcher = LINE.matcher(raw.trim());
            if (!matcher.find()) continue;
            try {
                int mm = Integer.parseInt(matcher.group(1));
                int ss = Integer.parseInt(matcher.group(2));
                String frac = matcher.group(3);
                int ms = 0;
                if (frac != null && !frac.isEmpty()) {
                    while (frac.length() < 3) frac = frac + "0";
                    ms = Integer.parseInt(frac.substring(0, 3));
                }
                String line = matcher.group(4).trim();
                entries.add(new Entry(mm * 60000 + ss * 1000 + ms, line));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(entries, (a, b) -> Long.compare(a.time, b.time));
        return new Lrc(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String text(int index) {
        if (index < 0 || index >= entries.size()) return "";
        return entries.get(index).text;
    }

    /** 当前进度对应哪一行；返回 -1 表示还没开始 */
    public int index(long position) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).time <= position) index = i;
            else break;
        }
        return index;
    }

    public static class Entry {
        final long time;
        final String text;

        Entry(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }
}

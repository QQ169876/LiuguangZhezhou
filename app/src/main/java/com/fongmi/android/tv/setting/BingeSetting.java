package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** 刷剧：一部短剧播完后自动接下一部同题材短剧 */
public class BingeSetting {

    /** 典型短剧的集数区间下限，只用来给候选打分加权 */
    public static final int SHORT_EPISODES = 40;
    /** 一次最多连续刷多少部，防止无限循环 */
    public static final int MAX_SERIAL = 20;
    /** 连续失败几次就停 */
    public static final int MAX_FAILURE = 5;
    /** 一批搜索等多久算没戏 */
    public static final long SEARCH_TIMEOUT = 6000L;

    public static boolean isEnabled() {
        return Prefers.getBoolean("binge", true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("binge", enabled);
    }
}

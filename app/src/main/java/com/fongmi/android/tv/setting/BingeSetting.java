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
    /** 还剩几集就开始备下一部：短剧单集平均 2~3 分钟，剩 2 集≈提前 4~6 分钟，跨站搜索+详情最坏约 45 秒，余量足够 */
    public static final int PREPARE_AHEAD = 2;
    /** 探索位初始比例：25% 概率换到画像里比较冷的题材族试试新口味（工业典型 0.05~0.20，冷启动取偏上） */
    public static final double EXPLORE_INIT = 0.25;
    /** 探索比例地板：再怎么衰减也留 5% 出界试探 */
    public static final double EXPLORE_MIN = 0.05;
    /** 探索比例按画像累计完播数指数衰减（0.99^n），刷得越久越不折腾 */
    public static final double EXPLORE_DECAY = 0.99;
    /** 搜索结果是逐站回来的，先攒这么久再挑，免得"谁先回来就切谁"——挑到差的回头又得换，就是跳剧 */
    public static final long PICK_DELAY = 800L;
    /** 攒够这么多候选就不等了，直接挑 */
    public static final int PICK_BATCH = 8;
    /** 切过去之前先拉详情验一遍能播，这一趟最多等这么久，等不来就换下一部 */
    public static final long PROBE_TIMEOUT = 8000L;

    public static boolean isEnabled() {
        return Prefers.getBoolean("binge", true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("binge", enabled);
    }
}

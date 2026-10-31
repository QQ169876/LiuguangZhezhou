package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.Setting;

import java.util.Arrays;
import java.util.List;

/**
 * 更新通道（2026-11-01 定的三条路）：
 *   1. 码云 —— 国内直连，默认主通道；
 *   2. 网盘 —— 马先生自己的网盘，直连，备；
 *   3. GitHub —— 兜底。**默认直连，直连不通自动过代理**，这些代理全收在 GhRoute 里，
 *      不再摆到设置里让用户挑（挑了也没用，代理哪天通哪天不通用户判断不了）。
 *
 * 「自动」= 按上面这个顺序来。手动指定某一条 = 它优先走，它取不到才退回其它通道。
 */
public class UpdateChannel {

    /** 自动：按 码云 → 网盘 → GitHub 的顺序 */
    public static final String AUTO = "";

    private UpdateChannel() {
    }

    /** 设置里能挑的全部通道，顺序就是「自动」时的尝试顺序 */
    public static List<String> list() {
        return Arrays.asList(AUTO, Gitee.ROUTE, SelfHost.ROUTE, Github.ROUTE);
    }

    /** 当前选中的通道：没挑过或存了过期的值都算自动 */
    public static String current() {
        String channel = Setting.getUpdateChannel();
        return is(channel) ? channel : AUTO;
    }

    /** 手动锁定的那条；null = 自动 */
    public static String fixed() {
        String channel = Setting.getUpdateChannel();
        return is(channel) ? channel : null;
    }

    /** 这条通道名认不认：只认三条正经通道，空串是自动 */
    public static boolean is(String channel) {
        return Gitee.is(channel) || SelfHost.is(channel) || Github.is(channel);
    }
}

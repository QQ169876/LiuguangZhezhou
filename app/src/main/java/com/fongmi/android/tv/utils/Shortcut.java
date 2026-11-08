package com.fongmi.android.tv.utils;

/**
 * 点播源快捷输入：填个数字就能配好常用的源，省得在小屏幕上敲一长串网址。
 *
 * 2026-11-08 起：三套值（点播源 / 直播源 / 影视站同步站点）由
 * {@link ShortcutConfig} 在启动时从服务器 kuaijie.json 拉取覆盖，这里只留内置默认值当兜底，
 * 改地址不用再发版。域名平替（169876.xyz / 12356.cool / 169876.us.kg）也在那边统一处理。
 */
public class Shortcut {

    /** 默认直接源（保留给旧引用；实际取值一律走 ShortcutConfig） */
    public static final String DIRECT = "http://www.\u996d\u592a\u786c.cc/tv";

    /** 快捷码换成点播源地址，认不出来的原样返回 */
    public static String vod(String text) {
        return ShortcutConfig.vod(text);
    }

    /** 认得出来的才叫快捷码，随便一个网址不算；服务器可以新增快捷码 */
    public static boolean isCode(String text) {
        return ShortcutConfig.isCode(text);
    }

    /** 快捷码顺手把影视站同步站点带上 */
    public static void moon(String text) {
        ShortcutConfig.moon(text);
    }

    /** 直播源 */
    public static String live(String text) {
        return ShortcutConfig.live(text);
    }

    /** 按类型（0 点播 / 1 直播）解析快捷码 */
    public static String parse(int type, String text) {
        if (type == 0) return vod(text);
        if (type == 1) return live(text);
        return text == null ? "" : text.trim();
    }
}

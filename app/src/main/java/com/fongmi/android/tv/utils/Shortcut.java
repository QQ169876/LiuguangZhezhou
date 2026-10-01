package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.moontv.MoonSetting;

/**
 * 点播源快捷输入：填个数字就能配好常用的源，省得在小屏幕上敲一长串网址。
 * 直接源统一成一个地址，新增订阅只要在这里加一行。
 */
public class Shortcut {

    /** 直接源（统一地址） */
    public static final String DIRECT = "http://www.\u996d\u592a\u786c.cc/tv";

    private static final String SUB_3 = "https://ok.169876.us.kg/sub/3";
    private static final String SUB_PG18 = "https://ok.169876.us.kg/sub/pg18";

    /**
     * 快捷码换成真正的地址，认不出来的原样返回
     */
    public static String vod(String text) {
        String value = text == null ? "" : text.trim();
        return switch (value) {
            case "520" -> DIRECT;
            case "521" -> SUB_3;
            case "6669" -> SUB_PG18;
            default -> value;
        };
    }

    /** 认得出来的才叫快捷码，随便一个网址不算 */
    public static boolean isCode(String text) {
        String value = text == null ? "" : text.trim();
        return switch (value) {
            case "520", "521", "6669" -> true;
            default -> false;
        };
    }

    /**
     * 快捷码本来就是一键配置，影视站网址顺手捎带上，省得再来一遍。
     * 网址平时不留默认值（只有输入框里的灰字提示），只有走到这一步才真的写进去；
     * 已经填过别的值就不动，免得把人自己配的站点冲掉。
     */
    public static void moon(String text) {
        if (!isCode(text)) return;
        if (!MoonSetting.getUrl().isEmpty()) return;
        MoonSetting.putUrl(MoonSetting.DEFAULT_URL);
    }

    /**
     * 直播源不分子站，快捷码一律给直接源
     */
    public static String live(String text) {
        String value = text == null ? "" : text.trim();
        return switch (value) {
            case "520", "521", "6669" -> DIRECT;
            default -> value;
        };
    }

    /**
     * 按类型（0 点播 / 1 直播）解析快捷码
     */
    public static String parse(int type, String text) {
        if (type == 0) return vod(text);
        if (type == 1) return live(text);
        return text == null ? "" : text.trim();
    }
}

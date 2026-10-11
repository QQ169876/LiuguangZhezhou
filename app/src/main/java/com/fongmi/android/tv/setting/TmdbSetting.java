package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TMDB 的接法：查演员演过哪些片子，好拿准确的片名回 APP 里搜（马先生 2026-10-11 定的需求）。
 *
 * 为什么绕一圈：TMDB 的官方域名 api.themoviedb.org 在国内基本连不上（实测 TCP 握手直接超时），
 * 所以走两条路，谁先回来用谁：
 *   1. 自建 CORS 代理（mtdl.12356.cool）转发到官方域名 —— 代理在境外，肯定通；
 *   2. 直连 api.tmdb.org —— 这个域名挂在 Cloudflare 上，国内多半能直连，而且更快。
 * 两条都死了就当没查到，调用方退回原来的「拿演员名直接全站搜」，不让用户干等。
 *
 * key 的存放跟 AI 那套一样：打散成异或数组，扫包扫不出明文。真要换 key 不用发版，
 * 往 tmdb_key 里写一份就能顶掉（服务器下发的配置也能写）。
 */
public class TmdbSetting {

    /**
     * CORS 中转（马先生自己部署的 CORSAPI），用法：{proxy}/?url={编码后的目标地址}&其它参数透传。
     * 两个互为备份：都是 Cloudflare Worker，冷启动偶尔十来秒，多一个就少一次干等。
     */
    public static final String[] DEFAULT_PROXIES = {"https://mtdl.169876.us.kg", "https://mtdl.12356.cool"};
    /** TMDB 官方 API（给代理去访问） */
    public static final String DEFAULT_API = "https://api.themoviedb.org/3";
    /** 直连备用域名：挂在 Cloudflare 上，国内通常能直连 */
    public static final String DEFAULT_DIRECT = "https://api.tmdb.org/3";

    /** 单次请求最多等这么久（worker 冷启动偶尔二十几秒，等不起） */
    public static final int TIMEOUT = 12000;

    private static final String MASK = "liuguang-zhezhou";
    private static final int[] K = {85, 95, 17, 95, 64, 4, 89, 2, 20, 77, 11, 81, 66, 11, 91, 22, 92, 95, 16, 6, 64, 89, 89, 3, 75, 25, 91, 4, 77, 94, 93, 65};

    public static boolean isEnabled() {
        return Prefers.getBoolean("tmdb", true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("tmdb", enabled);
    }

    /** 中转地址们（第一个是首选）；要换就往 tmdb_proxy 里写一份顶掉（逗号分隔也行） */
    public static List<String> getProxies() {
        String custom = Prefers.getString("tmdb_proxy", "").trim();
        if (custom.isEmpty()) return Arrays.asList(DEFAULT_PROXIES);
        List<String> list = new ArrayList<>();
        for (String item : custom.split("[,，\\s]+")) {
            String url = item.trim().replaceAll("/+$", "");
            if (!url.isEmpty() && !list.contains(url)) list.add(url);
        }
        return list.isEmpty() ? Arrays.asList(DEFAULT_PROXIES) : list;
    }

    public static String getApi() {
        String url = Prefers.getString("tmdb_api", "").trim();
        return url.isEmpty() ? DEFAULT_API : url.replaceAll("/+$", "");
    }

    public static String getDirect() {
        String url = Prefers.getString("tmdb_direct", "").trim();
        return url.isEmpty() ? DEFAULT_DIRECT : url.replaceAll("/+$", "");
    }

    /** TMDB 的 key（v3 auth）。要换就往 tmdb_key 里写一份顶掉，不用重新发版 */
    public static String getKey() {
        String custom = Prefers.getString("tmdb_key", "").trim();
        if (!custom.isEmpty()) return custom;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < K.length; i++) sb.append((char) (K[i] ^ MASK.charAt(i % MASK.length())));
        return sb.toString();
    }
}

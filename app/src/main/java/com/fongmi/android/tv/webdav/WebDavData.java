package com.fongmi.android.tv.webdav;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.utils.ConfigCache;
import com.fongmi.android.tv.utils.DebugLog;
import com.github.catvod.utils.Prefers;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

public class WebDavData {

    @SerializedName("version")
    private int version;
    @SerializedName("time")
    private long time;
    @SerializedName("device")
    private String device;
    @SerializedName("data")
    private Backup data;
    @SerializedName("cache")
    private Map<String, String> cache;

    public static WebDavData create() {
        WebDavData item = new WebDavData();
        item.version = 1;
        item.time = System.currentTimeMillis();
        item.device = WebDavSetting.getDevice();
        item.data = Backup.create();
        // ConfigCache 不上传：源缓存跟本机当前源绑定，别台的缓存对本机无用
        item.setPrefers(Prefers.getPrefers().getAll());
        return item;
    }

    public static WebDavData empty() {
        WebDavData item = new WebDavData();
        item.version = 1;
        item.time = 0;
        item.device = "";
        item.data = new Backup();
        return item;
    }

    /**
     * 解析云端同步文件。
     *
     * 服务器覆盖写不截断时，文件会变成「一份合法 JSON + 上一版的尾巴」，
     * Gson 整串解会抛异常返回 null，上层就把它当成「文件不存在」，
     * 于是「云端覆盖本机」整库清空、或普通同步悄悄回退成空数据（2026-11-11 TV 丢记录那次）。
     * 这里做兜底：整串解不了就截到第一个完整 JSON 对象再解，只采信主内容，
     * 尾部那截是旧版本，不参与合并；同时留一行日志方便核对。
     */
    public static WebDavData from(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        if (json.startsWith("\uFEFF")) json = json.substring(1); // 服务器加了 BOM 也照解
        WebDavData item = parse(json);
        if (item != null) return item;
        String head = firstObject(json);
        // 用 json.length() 比，别用 trim()：文件前有空白/BOM 时 head 也含前缀，
        // 拿 trim 后的长度比会误判成「没有尾巴」，兜底就不生效了。
        if (head != null && head.length() < json.length()) {
            item = parse(head);
            if (item != null) {
                DebugLog.d("Sync", "云端同步文件带旧尾巴，截断 " + (json.length() - head.length()) + " 字符后解析成功");
                return item;
            }
        }
        return null;
    }

    private static WebDavData parse(String json) {
        try {
            GsonBuilder builder = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER);
            WebDavData item = builder.create().fromJson(json, WebDavData.class);
            return item == null || item.getData() == null ? null : item;
        } catch (Exception e) {
            return null;
        }
    }

    /** 第一个完整 JSON 对象/数组的结束位置：跳过字符串里的括号和转义符 */
    private static String firstObject(String json) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (--depth == 0) return json.substring(0, i + 1);
            }
        }
        return null;
    }

    /**
     * 云端文件合法，但一条观看记录/收藏都没有：用来挡住「空云端整库覆盖本机」。
     * 只看记录/收藏，不看设置项——设备写空文件时往往还带着一堆偏好，
     * 按「全空」判断会漏，本机记录照样被 clearLocal() 清掉。
     */
    public static boolean noMedia(WebDavData data) {
        if (data == null || data.getData() == null) return true;
        return data.getData().getHistory().isEmpty() && data.getData().getKeep().isEmpty();
    }

    public String toJson() {
        return new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create().toJson(this);
    }

    public String plain() {
        return App.gson().toJson(this);
    }

    public int getVersion() {
        return version;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getDevice() {
        return device == null ? "" : device;
    }

    public Backup getData() {
        return data;
    }

    public void setData(Backup data) {
        this.data = data;
    }

    public Map<String, String> getCache() {
        return cache == null ? new HashMap<>() : cache;
    }

    public void setCache(Map<String, String> cache) {
        this.cache = cache;
    }

    public Map<String, ?> getPrefers() {
        return getData().getPrefers();
    }

    /**
     * 只属于你的设备的设置，永远不上云、也不从云上下发给别的设备：
     * webdav_/moontv_ 是同步配置本身（干url、账号、同步标识），别的设备不能直接套用别人的账号；
     * owner_ 是归属账本，「谁的数据」是本机自己的判断；
     * route 系列（route/route_auto/route_socks/route_customs）是 GitHub 加速/代理，
     * 跟本机网络环境强绑定——别的设备（尤其走不同网络的电视）套用了可能全网请求卡死。
     * mpv_blocked：哪台设备用 MPV 内核崩过，是这台设备自己的结论（PlayWatchdog 记的），
     * 不能同步给别人，也不能被别人的「用 MPV」覆盖掉，否则一同步又给它推回崩溃内核。
     * debug_：调试流水账是本机排查用的（debug_up_len 是这台设备流水账传到哪了的标记），别同步到全家设备
     * config_ 系列是本机正在用的源地址（config_0=点播源 config_1=直播源 config_2=壁纸，
     * config_cache_url_0/1 是源内容缓存的 URL 标记）——不传不收不合不落库，每台设备各配各的源。
     */
    public static boolean localOnly(String key) {
        if (key == null) return true;
        return key.startsWith("webdav_") || key.startsWith("moontv_") || key.startsWith("owner_") || key.startsWith("route") || key.startsWith("mpv_blocked") || key.startsWith("hwdec_") || key.startsWith("debug_");
    }

    /**
     * 同步时要跳过的「那 3 项设置」本身，只此一家别无分店：
     * config_0=点播源、config_1=直播源、config_2=壁纸，外加跟着源走的缓存标记 config_cache_url_0/1。
     * 除这几个键以外的设置一律照常同步（历史、收藏、站点、直播、Cookie 等等都不在这儿）。
     */
    public static boolean isSourcePref(String key) {
        if (key == null) return false;
        if (key.equals("config_0") || key.equals("config_1") || key.equals("config_2")) return true;
        return key.startsWith("config_cache_url_");
    }

    /**
     * 上传时 Prefers 排除掉本机独有和源地址相关的键：
     * config_ 系列是本机正在用的源地址（config_0=点播源 config_1=直播源 config_2=壁纸，
     * config_cache_url_0/1 是源内容缓存 URL 标记）——不传不收不合，每台设备各配各的。
     */
    public void setPrefers(Map<String, ?> prefers) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefers.entrySet()) {
            String key = entry.getKey();
            if (localOnly(key)) continue;
            if (isSourcePref(key)) continue;
            values.put(key, entry.getValue());
        }
        getData().setPrefers(values);
    }
}

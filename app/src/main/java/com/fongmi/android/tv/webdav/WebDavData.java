package com.fongmi.android.tv.webdav;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.utils.ConfigCache;
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

    public static WebDavData from(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return null;
            GsonBuilder builder = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER);
            WebDavData item = builder.create().fromJson(json, WebDavData.class);
            if (item == null || item.getData() == null) return null;
            return item;
        } catch (Exception e) {
            return null;
        }
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
     * 源地址相关的 Prefers 键（config_0=点播源 config_1=直播源 config_2=壁纸，
     * config_cache_url_0/1=源缓存 URL 标记）：上传、合并、落库全部跳过——
     * 每台设备配自己的源，不能被别台的拉过来覆盖。
     */
    public static boolean isSourcePref(String key) {
        return key != null && key.startsWith("config_");
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

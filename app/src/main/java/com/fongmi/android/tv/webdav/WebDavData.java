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
        item.cache = ConfigCache.export();
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

    public void setPrefers(Map<String, ?> prefers) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefers.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("webdav_last") || key.startsWith("webdav_device")) continue;
            values.put(key, entry.getValue());
        }
        getData().setPrefers(values);
    }
}

package com.fongmi.android.tv.music;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/**
 * 一首歌。字段跟洛雪的歌单格式保持一致，meta 原样保留，
 * 这样同步回服务器时不会丢东西。
 */
public class Music {

    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("singer")
    private String singer;
    @SerializedName("source")
    private String source;
    @SerializedName("interval")
    private String interval;
    @SerializedName("meta")
    private JsonObject meta;

    public String getId() {
        return id == null ? "" : id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getSinger() {
        return singer == null ? "" : singer;
    }

    public String getSource() {
        return source == null ? "" : source;
    }

    public String getInterval() {
        return interval == null ? "" : interval;
    }

    public JsonObject getMeta() {
        return meta == null ? new JsonObject() : meta;
    }

    /** 歌曲在平台上的 ID（字符串，tx 这类不是纯数字） */
    public String getSongId() {
        if (meta == null || !meta.has("songId")) return "";
        return meta.get("songId").getAsString();
    }

    /** 酷狗这类要多一个 hash 才能取地址 */
    public String getHash() {
        if (meta == null || !meta.has("hash")) return "";
        return meta.get("hash").getAsString();
    }

    public String getPicUrl() {
        if (meta == null || !meta.has("picUrl")) return "";
        return meta.get("picUrl").getAsString();
    }

    public String getAlbumName() {
        if (meta == null || !meta.has("albumName")) return "";
        return meta.get("albumName").getAsString();
    }

    /** 当前音质对应的 hash（酷狗不同音质 hash 不一样） */
    public String getHash(String quality) {
        if (meta == null) return getHash();
        if (!meta.has("_qualitys")) return getHash();
        JsonObject all = meta.getAsJsonObject("_qualitys");
        if (all.has(quality)) {
            JsonObject item = all.getAsJsonObject(quality);
            if (item.has("hash")) return item.get("hash").getAsString();
        }
        return getHash();
    }

    /** 转成音源脚本要的 musicInfo */
    public JsonObject toInfo(String quality) {
        JsonObject info = new JsonObject();
        info.addProperty("songmid", getSongId());
        info.addProperty("id", getId());
        info.addProperty("name", getName());
        info.addProperty("singer", getSinger());
        info.addProperty("source", getSource());
        info.addProperty("interval", getInterval());
        JsonObject meta = getMeta().deepCopy();
        meta.addProperty("songId", getSongId());
        meta.addProperty("hash", getHash(quality));
        info.add("meta", meta);
        return info;
    }

    public boolean isSame(Music item) {
        return item != null && getId().equals(item.getId());
    }

    @NonNull
    @Override
    public String toString() {
        return getName();
    }
}

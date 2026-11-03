package com.fongmi.android.tv.bean;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 一条大模型通道：接入地址（OpenAI 兼容）+ API Key + 模型名。
 *
 * 两种来源：
 *   - 内置（豆包 / 千问自建中继）：只有走快捷码录入源的设备才配用（马先生 2026-11-03 定），
 *     界面上只给启停，不给改、不给删，也不进同步数据（每台设备自己按内部标记决定有没有）；
 *   - 用户自建：自己填地址/Key/模型名，可增可改可删可手动排序，**优先级高于内置**。
 *
 * 自建的这些存成 JSON 放在 Prefers 的 ai_models 里，跟着账号走（WebDAV + 影视站都同步）：
 * 换设备不用再录一遍。Key 按马先生定的**明文同步**（2026-11-03）。
 * 每条带 utime（最后改动时间）和 del（删除标记），多设备合并时按它分胜负、删除也能传过去。
 */
public class AiModel {

    public static final String BUILTIN_DOUBAO = "builtin_doubao";
    public static final String BUILTIN_QWEN = "builtin_qwen";

    private String id;
    private String name;
    private String endpoint;
    private String key;
    private String model;
    private boolean builtin;
    private boolean enabled;
    private long utime;
    private boolean del;

    public static AiModel create() {
        AiModel item = new AiModel();
        item.id = UUID.randomUUID().toString();
        item.enabled = true;
        item.utime = System.currentTimeMillis();
        return item;
    }

    public static AiModel builtin(String id, String name, String endpoint, String key, String model) {
        AiModel item = new AiModel();
        item.id = id;
        item.name = name;
        item.endpoint = endpoint;
        item.key = key;
        item.model = model;
        item.builtin = true;
        item.enabled = true;
        return item;
    }

    public static AiModel from(JSONObject object) {
        if (object == null) return null;
        AiModel item = new AiModel();
        item.id = object.optString("id");
        item.name = object.optString("name");
        item.endpoint = object.optString("endpoint");
        item.key = object.optString("key");
        item.model = object.optString("model");
        item.builtin = object.optBoolean("builtin");
        item.enabled = object.optBoolean("enabled", true);
        item.utime = object.optLong("utime");
        item.del = object.optBoolean("del");
        return item.id.isEmpty() ? null : item;
    }

    public JSONObject toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("name", name);
            object.put("endpoint", endpoint);
            object.put("key", key);
            object.put("model", model);
            object.put("builtin", builtin);
            object.put("enabled", enabled);
            object.put("utime", utime);
            object.put("del", del);
            return object;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 填得全不全：地址、Key、模型名缺一样就用不了（用不了的一律跳过，不挡本地搜索） */
    public boolean usable() {
        return enabled && !endpoint.trim().isEmpty() && !key.trim().isEmpty() && !model.trim().isEmpty();
    }

    public void touch() {
        utime = System.currentTimeMillis();
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEndpoint() {
        return endpoint == null ? "" : endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getKey() {
        return key == null ? "" : key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getModel() {
        return model == null ? "" : model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getUtime() {
        return utime;
    }

    public void setUtime(long utime) {
        this.utime = utime;
    }

    public boolean isDel() {
        return del;
    }

    public void setDel(boolean del) {
        this.del = del;
    }

    /** 列表里显示什么：没名字就拿模型名顶上，再没有就显示地址 */
    public String display() {
        if (!getName().trim().isEmpty()) return getName();
        if (!getModel().trim().isEmpty()) return getModel();
        return getEndpoint();
    }
}

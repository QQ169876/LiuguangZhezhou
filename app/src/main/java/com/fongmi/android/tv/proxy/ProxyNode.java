package com.fongmi.android.tv.proxy;

import org.json.JSONObject;

public class ProxyNode {

    private final JSONObject data;
    private int delay;

    public ProxyNode(JSONObject data) {
        this.data = data;
    }

    public JSONObject getData() {
        return data;
    }

    public String getTag() {
        return data.optString("tag");
    }

    public void setTag(String tag) {
        try {
            data.put("tag", tag);
        } catch (Exception ignored) {
        }
    }

    public String getType() {
        return data.optString("type");
    }

    public String getServer() {
        return data.optString("server");
    }

    public int getPort() {
        return data.optInt("server_port");
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public String getDelayText() {
        if (delay <= 0) return "";
        return delay + "ms";
    }
}

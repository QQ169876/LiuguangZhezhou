package com.fongmi.android.tv.proxy;

import com.github.catvod.utils.Prefers;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class ProxySetting {

    public static final String AUTO = "auto";
    public static final String SELECT = "select";

    public static String getSub() {
        return Prefers.getString("proxy_sub").trim();
    }

    public static void putSub(String url) {
        Prefers.put("proxy_sub", url == null ? "" : url.trim());
    }

    public static String getTag() {
        String tag = Prefers.getString("proxy_tag").trim();
        return tag.isEmpty() ? AUTO : tag;
    }

    public static void putTag(String tag) {
        Prefers.put("proxy_tag", tag == null ? AUTO : tag);
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean("proxy_enable");
    }

    public static void putEnabled(boolean enable) {
        Prefers.put("proxy_enable", enable);
    }

    public static boolean isIdle() {
        return Prefers.getBoolean("proxy_idle", true);
    }

    public static void putIdle(boolean idle) {
        Prefers.put("proxy_idle", idle);
    }

    public static long getUpdate() {
        return Prefers.getLong("proxy_update");
    }

    public static void putUpdate(long time) {
        Prefers.put("proxy_update", time);
    }

    public static List<ProxyNode> getNodes() {
        List<ProxyNode> nodes = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(Prefers.getString("proxy_nodes"));
            for (int i = 0; i < array.length(); i++) nodes.add(new ProxyNode(array.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return nodes;
    }

    public static void putNodes(List<ProxyNode> nodes) {
        try {
            JSONArray array = new JSONArray();
            for (ProxyNode node : nodes) array.put(node.getData());
            Prefers.put("proxy_nodes", array.toString());
        } catch (Exception ignored) {
        }
    }
}

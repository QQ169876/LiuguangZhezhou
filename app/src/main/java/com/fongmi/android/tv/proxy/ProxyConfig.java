package com.fongmi.android.tv.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class ProxyConfig {

    private static final String URL_TEST = "https://www.gstatic.com/generate_204";

    public static String build(List<ProxyNode> nodes, String selected) {
        if (nodes == null || nodes.isEmpty()) return null;
        try {
            JSONArray tags = new JSONArray();
            for (ProxyNode node : nodes) tags.put(node.getTag());

            JSONObject log = new JSONObject();
            log.put("level", "warn");
            log.put("timestamp", false);

            JSONObject dns = new JSONObject();
            JSONArray servers = new JSONArray();
            JSONObject direct = new JSONObject();
            direct.put("tag", "dns-direct");
            direct.put("address", "223.5.5.5");
            direct.put("detour", "direct");
            JSONObject proxy = new JSONObject();
            proxy.put("tag", "dns-proxy");
            proxy.put("address", "tcp://8.8.8.8");
            proxy.put("detour", ProxySetting.SELECT);
            servers.put(direct).put(proxy);
            JSONArray rules = new JSONArray();
            JSONObject cn = new JSONObject();
            cn.put("domain_suffix", new JSONArray(new String[]{".cn", ".qq.com", ".aliyun.com", ".baidu.com"}));
            cn.put("server", "dns-direct");
            rules.put(cn);
            dns.put("servers", servers);
            dns.put("rules", rules);
            dns.put("final", "dns-proxy");
            dns.put("strategy", "prefer_ipv4");

            JSONObject tun = new JSONObject();
            tun.put("type", "tun");
            tun.put("tag", "tun-in");
            tun.put("stack", "mixed");
            tun.put("mtu", 9000);
            tun.put("auto_route", true);
            tun.put("strict_route", false);
            tun.put("address", new JSONArray(new String[]{"172.19.0.1/28"}));
            JSONArray inbounds = new JSONArray();
            inbounds.put(tun);

            JSONArray outbounds = new JSONArray();
            JSONObject selector = new JSONObject();
            selector.put("type", "selector");
            selector.put("tag", ProxySetting.SELECT);
            selector.put("default", selected);
            JSONArray selectorOut = new JSONArray();
            selectorOut.put(ProxySetting.AUTO);
            for (int i = 0; i < tags.length(); i++) selectorOut.put(tags.getString(i));
            selectorOut.put("direct");
            selector.put("outbounds", selectorOut);
            outbounds.put(selector);

            JSONObject urltest = new JSONObject();
            urltest.put("type", "urltest");
            urltest.put("tag", ProxySetting.AUTO);
            urltest.put("outbounds", tags);
            urltest.put("url", URL_TEST);
            urltest.put("interval", "1m");
            urltest.put("tolerance", 50);
            outbounds.put(urltest);

            for (ProxyNode node : nodes) outbounds.put(node.getData());

            JSONObject outDirect = new JSONObject();
            outDirect.put("type", "direct");
            outDirect.put("tag", "direct");
            JSONObject block = new JSONObject();
            block.put("type", "block");
            block.put("tag", "block");
            JSONObject dnsOut = new JSONObject();
            dnsOut.put("type", "dns");
            dnsOut.put("tag", "dns-out");
            outbounds.put(outDirect).put(block).put(dnsOut);

            JSONObject route = new JSONObject();
            JSONArray routeRules = new JSONArray();
            JSONObject dnsRule = new JSONObject();
            dnsRule.put("protocol", "dns");
            dnsRule.put("outbound", "dns-out");
            JSONObject privateRule = new JSONObject();
            privateRule.put("ip_is_private", true);
            privateRule.put("outbound", "direct");
            routeRules.put(dnsRule).put(privateRule);
            route.put("rules", routeRules);
            route.put("final", ProxySetting.SELECT);
            route.put("auto_detect_interface", true);

            JSONObject config = new JSONObject();
            config.put("log", log);
            config.put("dns", dns);
            config.put("inbounds", inbounds);
            config.put("outbounds", outbounds);
            config.put("route", route);
            return config.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

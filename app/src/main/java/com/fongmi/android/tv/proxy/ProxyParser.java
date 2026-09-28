package com.fongmi.android.tv.proxy;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProxyParser {

    private static final String[] SCHEMES = {"vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https", "hysteria2", "hy2", "tuic"};

    public static List<ProxyNode> parse(String text) {
        List<ProxyNode> nodes = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return nodes;
        String body = text.trim();
        String decoded = decodeBase64(body);
        if (decoded != null && decoded.contains("://")) body = decoded;
        if (body.startsWith("{")) return json(body, nodes);
        if (body.contains("proxies:")) return yaml(body, nodes);
        return lines(body, nodes);
    }

    private static List<ProxyNode> lines(String body, List<ProxyNode> nodes) {
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            ProxyNode node = uri(line);
            if (node != null) nodes.add(node);
        }
        return unique(nodes);
    }

    private static List<ProxyNode> json(String body, List<ProxyNode> nodes) {
        try {
            JSONObject object = new JSONObject(body);
            JSONArray array = object.optJSONArray("outbounds");
            if (array == null) return nodes;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type");
                if (type.isEmpty() || isGroup(type)) continue;
                if (item.optString("tag").isEmpty()) item.put("tag", type + "-" + i);
                nodes.add(new ProxyNode(item));
            }
        } catch (Exception ignored) {
        }
        return unique(nodes);
    }

    private static boolean isGroup(String type) {
        return "selector".equals(type) || "urltest".equals(type) || "direct".equals(type) || "block".equals(type) || "dns".equals(type);
    }

    // region URI

    public static ProxyNode uri(String link) {
        try {
            int index = link.indexOf("://");
            if (index < 0) return null;
            String scheme = link.substring(0, index).toLowerCase();
            if (!Arrays.asList(SCHEMES).contains(scheme)) return null;
            String rest = link.substring(index + 3);
            String name = "";
            int hash = rest.lastIndexOf('#');
            if (hash >= 0) {
                name = urlDecode(rest.substring(hash + 1));
                rest = rest.substring(0, hash);
            }
            Map<String, String> query = new LinkedHashMap<>();
            int ask = rest.indexOf('?');
            if (ask >= 0) {
                for (String pair : rest.substring(ask + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    query.put(pair.substring(0, eq).toLowerCase(), urlDecode(pair.substring(eq + 1)));
                }
                rest = rest.substring(0, ask);
            }
            switch (scheme) {
                case "vmess":
                    return vmess(rest, name);
                case "vless":
                    return vless(rest, name, query);
                case "trojan":
                    return trojan(rest, name, query);
                case "ss":
                    return shadowsocks(rest, name, query);
                case "socks":
                case "socks5":
                    return socks(rest, name);
                case "http":
                case "https":
                    return http(rest, name, "https".equals(scheme));
                case "hysteria2":
                case "hy2":
                    return hysteria2(rest, name, query);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static ProxyNode vless(String rest, String name, Map<String, String> query) {
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        String uuid = rest.substring(0, at);
        String[] hostPort = splitHostPort(rest.substring(at + 1));
        if (hostPort == null) return null;
        JSONObject data = base("vless", name, hostPort[0], hostPort[1]);
        put(data, "uuid", uuid);
        put(data, "packet_encoding", "xudp");
        String flow = query.get("flow");
        if (flow != null && !flow.isEmpty()) put(data, "flow", flow);
        putTls(data, query);
        putTransport(data, query.get("type"), query);
        return new ProxyNode(data);
    }

    private static ProxyNode vmess(String rest, String name) {
        String json = decodeBase64(rest);
        if (json == null) json = rest;
        try {
            JSONObject object = new JSONObject(json);
            String server = object.optString("add");
            int port = object.optInt("port", 0);
            if (server.isEmpty() || port == 0) return null;
            if (name.isEmpty()) name = object.optString("ps");
            JSONObject data = base("vmess", name, server, String.valueOf(port));
            put(data, "uuid", object.optString("id"));
            put(data, "alter_id", object.optInt("aid", 0));
            String cipher = object.optString("scy", object.optString("cipher", "auto"));
            put(data, "security", cipher.isEmpty() ? "auto" : cipher);
            put(data, "packet_encoding", "xudp");
            Map<String, String> query = new LinkedHashMap<>();
            query.put("security", object.optString("tls").equals("tls") ? "tls" : "none");
            query.put("sni", object.optString("sni", object.optString("host")));
            query.put("host", object.optString("host"));
            query.put("path", object.optString("path"));
            query.put("fp", object.optString("fp"));
            query.put("alpn", object.optString("alpn"));
            query.put("type", object.optString("net", "tcp"));
            query.put("serviceName", object.optString("path"));
            if ("1".equals(object.optString("allowInsecure"))) query.put("allowInsecure", "1");
            putTls(data, query);
            putTransport(data, query.get("type"), query);
            return new ProxyNode(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static ProxyNode trojan(String rest, String name, Map<String, String> query) {
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        String password = urlDecode(rest.substring(0, at));
        String[] hostPort = splitHostPort(rest.substring(at + 1));
        if (hostPort == null) return null;
        JSONObject data = base("trojan", name, hostPort[0], hostPort[1]);
        put(data, "password", password);
        String security = query.get("security");
        if (security == null) security = "tls";
        query.put("security", security);
        putTls(data, query);
        putTransport(data, query.get("type"), query);
        return new ProxyNode(data);
    }

    private static ProxyNode shadowsocks(String rest, String name, Map<String, String> query) {
        String method;
        String password;
        String hostPort;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            hostPort = rest.substring(at + 1);
            String decoded = decodeBase64(pad(userInfo));
            if (decoded == null) decoded = userInfo;
            int colon = decoded.indexOf(':');
            if (colon < 0) return null;
            method = decoded.substring(0, colon);
            password = decoded.substring(colon + 1);
        } else {
            String decoded = decodeBase64(pad(rest));
            if (decoded == null) return null;
            int at2 = decoded.lastIndexOf('@');
            if (at2 < 0) return null;
            String userInfo = decoded.substring(0, at2);
            hostPort = decoded.substring(at2 + 1);
            int colon = userInfo.indexOf(':');
            if (colon < 0) return null;
            method = userInfo.substring(0, colon);
            password = userInfo.substring(colon + 1);
        }
        String[] hp = splitHostPort(hostPort);
        if (hp == null) return null;
        JSONObject data = base("shadowsocks", name, hp[0], hp[1]);
        put(data, "method", method);
        put(data, "password", password);
        put(data, "udp_over_tcp", false);
        return new ProxyNode(data);
    }

    private static ProxyNode socks(String rest, String name) {
        int at = rest.lastIndexOf('@');
        String hostPort = rest;
        String user = null;
        String pass = null;
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            hostPort = rest.substring(at + 1);
            String decoded = decodeBase64(pad(userInfo));
            if (decoded == null) decoded = userInfo;
            int colon = decoded.indexOf(':');
            if (colon > 0) {
                user = decoded.substring(0, colon);
                pass = decoded.substring(colon + 1);
            }
        }
        String[] hp = splitHostPort(hostPort);
        if (hp == null) return null;
        JSONObject data = base("socks", name, hp[0], hp[1]);
        if (user != null) put(data, "username", user);
        if (pass != null) put(data, "password", pass);
        put(data, "version", "5");
        return new ProxyNode(data);
    }

    private static ProxyNode http(String rest, String name, boolean tls) {
        int at = rest.lastIndexOf('@');
        String hostPort = rest;
        String user = null;
        String pass = null;
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            hostPort = rest.substring(at + 1);
            int colon = userInfo.indexOf(':');
            if (colon > 0) {
                user = userInfo.substring(0, colon);
                pass = userInfo.substring(colon + 1);
            }
        }
        String[] hp = splitHostPort(hostPort);
        if (hp == null) return null;
        JSONObject data = base("http", name, hp[0], hp[1]);
        if (user != null) put(data, "username", user);
        if (pass != null) put(data, "password", pass);
        if (tls) {
            JSONObject t = new JSONObject();
            try {
                t.put("enabled", true);
                data.put("tls", t);
            } catch (Exception ignored) {
            }
        }
        return new ProxyNode(data);
    }

    private static ProxyNode hysteria2(String rest, String name, Map<String, String> query) {
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        String password = urlDecode(rest.substring(0, at));
        String[] hostPort = splitHostPort(rest.substring(at + 1));
        if (hostPort == null) return null;
        JSONObject data = base("hysteria2", name, hostPort[0], hostPort[1]);
        put(data, "password", password);
        query.put("security", "tls");
        putTls(data, query);
        String obfs = query.get("obfs");
        if (obfs != null && !obfs.isEmpty()) {
            try {
                JSONObject o = new JSONObject();
                o.put("type", obfs);
                String obfsPassword = query.get("obfs-password");
                if (obfsPassword != null) o.put("password", obfsPassword);
                data.put("obfs", o);
            } catch (Exception ignored) {
            }
        }
        return new ProxyNode(data);
    }

    // endregion

    // region helpers

    private static JSONObject base(String type, String name, String host, String port) {
        JSONObject data = new JSONObject();
        try {
            data.put("type", type);
            data.put("tag", name.isEmpty() ? host + ":" + port : name);
            data.put("server", host);
            data.put("server_port", Integer.parseInt(port));
        } catch (Exception ignored) {
        }
        return data;
    }

    private static void put(JSONObject data, String key, Object value) {
        try {
            if (value == null) return;
            if (value instanceof String && ((String) value).isEmpty()) return;
            data.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static void putTls(JSONObject data, Map<String, String> query) {
        String security = query.get("security");
        if (security == null || security.isEmpty() || "none".equals(security)) return;
        try {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                reality.put("enabled", true);
                String pbk = query.get("pbk");
                if (pbk != null) reality.put("public_key", pbk);
                String sid = query.get("sid");
                if (sid != null) reality.put("short_id", sid);
                tls.put("reality", reality);
            }
            String sni = query.get("sni");
            if (sni == null || sni.isEmpty()) sni = query.get("peer");
            if (sni == null || sni.isEmpty()) sni = query.get("host");
            if (sni != null && !sni.isEmpty()) tls.put("server_name", sni);
            if ("1".equals(query.get("allowInsecure")) || "true".equals(query.get("allowInsecure"))) tls.put("insecure", true);
            String alpn = query.get("alpn");
            if (alpn != null && !alpn.isEmpty()) tls.put("alpn", new JSONArray(alpn.split(",")));
            String fp = query.get("fp");
            if (fp == null || fp.isEmpty()) fp = query.get("fingerprint");
            if (fp != null && !fp.isEmpty()) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
            data.put("tls", tls);
        } catch (Exception ignored) {
        }
    }

    private static void putTransport(JSONObject data, String net, Map<String, String> query) {
        if (net == null || net.isEmpty() || "tcp".equals(net) || "raw".equals(net) || "none".equals(net)) return;
        try {
            JSONObject transport = new JSONObject();
            switch (net) {
                case "ws":
                case "websocket":
                    transport.put("type", "ws");
                    String path = query.get("path");
                    if (path != null) transport.put("path", path);
                    String host = query.get("host");
                    if (host == null) host = query.get("sni");
                    if (host != null && !host.isEmpty()) {
                        JSONObject headers = new JSONObject();
                        headers.put("Host", host);
                        transport.put("headers", headers);
                    }
                    String ed = query.get("ed");
                    if (ed != null && !ed.isEmpty() && !"0".equals(ed)) {
                        transport.put("max_early_data", Integer.parseInt(ed));
                        transport.put("early_data_header_name", "Sec-WebSocket-Protocol");
                    }
                    break;
                case "grpc":
                    transport.put("type", "grpc");
                    String service = query.get("serviceName");
                    if (service == null) service = query.get("path");
                    if (service != null) transport.put("service_name", service);
                    break;
                case "h2":
                case "http":
                    transport.put("type", "http");
                    String h2Path = query.get("path");
                    if (h2Path != null) transport.put("path", h2Path);
                    String h2Host = query.get("host");
                    if (h2Host != null && !h2Host.isEmpty()) {
                        JSONArray hosts = new JSONArray();
                        for (String h : h2Host.split(",")) hosts.put(h);
                        transport.put("host", hosts);
                    }
                    break;
                case "quic":
                    transport.put("type", "quic");
                    break;
                default:
                    return;
            }
            data.put("transport", transport);
        } catch (Exception ignored) {
        }
    }

    private static String[] splitHostPort(String text) {
        text = text.trim();
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) return null;
            String host = text.substring(1, close);
            String port = text.substring(close + 1);
            if (port.startsWith(":")) port = port.substring(1);
            return new String[]{host, port};
        }
        int colon = text.lastIndexOf(':');
        if (colon <= 0) return null;
        return new String[]{text.substring(0, colon), text.substring(colon + 1)};
    }

    private static String pad(String text) {
        text = text.trim();
        int mod = text.length() % 4;
        if (mod == 0) return text;
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() % 4 != 0) sb.append('=');
        return sb.toString();
    }

    public static String decodeBase64(String text) {
        try {
            byte[] bytes = Base64.decode(pad(text), Base64.DEFAULT);
            String result = new String(bytes, "UTF-8");
            for (char c : result.toCharArray()) {
                if (c < 9 || (c > 13 && c < 32)) return null;
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlDecode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return text;
        }
    }

    // endregion

    // region clash yaml

    private static List<ProxyNode> yaml(String body, List<ProxyNode> nodes) {
        for (Map<String, Object> map : yamlProxies(body)) {
            ProxyNode node = fromMap(map);
            if (node != null) nodes.add(node);
        }
        return unique(nodes);
    }

    private static List<Map<String, Object>> yamlProxies(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean inProxies = false;
        Map<String, Object> current = null;
        Map<String, Object> nested = null;
        String nestedKey = null;
        Map<String, Object> deep = null;
        for (String raw : body.split("\n")) {
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) continue;
            int indent = raw.length() - raw.replaceFirst("^\\s+", "").length();
            String line = raw.trim();
            if (indent == 0) {
                String key = line.split(":")[0];
                if (inProxies) {
                    if (current != null) result.add(current);
                    current = null;
                    inProxies = false;
                }
                if ("proxies".equals(key)) inProxies = true;
                continue;
            }
            if (!inProxies) continue;
            if (line.startsWith("- ")) {
                if (current != null) result.add(current);
                current = new LinkedHashMap<>();
                nested = null;
                nestedKey = null;
                deep = null;
                line = line.substring(2).trim();
                putYaml(current, null, line);
                continue;
            }
            if (current == null) continue;
            if (line.endsWith(":") && !line.contains(": ")) {
                nestedKey = line.substring(0, line.length() - 1);
                nested = new LinkedHashMap<>();
                deep = null;
                current.put(nestedKey, nested);
                continue;
            }
            if (nested != null && deep != null && nestedKey != null) {
                putYaml(deep, null, line);
                continue;
            }
            if (nested != null && line.endsWith(":") && !line.contains(": ")) {
                deep = new LinkedHashMap<>();
                nested.put(line.substring(0, line.length() - 1), deep);
                continue;
            }
            if (nested != null && deep == null) {
                putYaml(nested, null, line);
                continue;
            }
            putYaml(current, null, line);
        }
        if (current != null) result.add(current);
        return result;
    }

    private static void putYaml(Map<String, Object> map, String unused, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return;
        String key = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            map.put(key, new ArrayList<>(Arrays.asList(value.substring(1, value.length() - 1).split(","))));
            return;
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length() - 1);
        if (value.startsWith("'") && value.endsWith("'") && value.length() > 1) value = value.substring(1, value.length() - 1);
        if ("true".equals(value) || "false".equals(value)) {
            map.put(key, Boolean.parseBoolean(value));
            return;
        }
        if (value.matches("\\d+")) {
            map.put(key, Integer.parseInt(value));
            return;
        }
        map.put(key, value);
    }

    private static String str(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isEmpty()) return value.toString();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<>();
    }

    private static ProxyNode fromMap(Map<String, Object> item) {
        String type = str(item, "type").toLowerCase();
        String name = str(item, "name");
        String server = str(item, "server");
        int port = 0;
        try {
            port = Integer.parseInt(str(item, "port", "server_port"));
        } catch (Exception ignored) {
        }
        if (server.isEmpty() || port == 0) return null;
        try {
            switch (type) {
                case "vmess": {
                    JSONObject data = base("vmess", name, server, String.valueOf(port));
                    put(data, "uuid", str(item, "uuid", "password"));
                    put(data, "alter_id", parseInt(item, "alterId", "alter_id"));
                    String cipher = str(item, "cipher", "scy");
                    put(data, "security", cipher.isEmpty() ? "auto" : cipher);
                    put(data, "packet_encoding", "xudp");
                    put(data, "tls", yamlTls(item));
                    put(data, "transport", yamlTransport(item, str(item, "network", "net")));
                    return new ProxyNode(data);
                }
                case "vless": {
                    JSONObject data = base("vless", name, server, String.valueOf(port));
                    put(data, "uuid", str(item, "uuid", "password"));
                    put(data, "packet_encoding", "xudp");
                    put(data, "flow", str(item, "flow"));
                    put(data, "tls", yamlTls(item));
                    put(data, "transport", yamlTransport(item, str(item, "network", "net")));
                    return new ProxyNode(data);
                }
                case "trojan": {
                    JSONObject data = base("trojan", name, server, String.valueOf(port));
                    put(data, "password", str(item, "password"));
                    put(data, "tls", yamlTls(item));
                    put(data, "transport", yamlTransport(item, str(item, "network", "net")));
                    return new ProxyNode(data);
                }
                case "ss":
                case "shadowsocks": {
                    JSONObject data = base("shadowsocks", name, server, String.valueOf(port));
                    put(data, "method", str(item, "cipher", "method"));
                    put(data, "password", str(item, "password"));
                    put(data, "udp_over_tcp", false);
                    return new ProxyNode(data);
                }
                case "socks":
                case "socks5": {
                    JSONObject data = base("socks", name, server, String.valueOf(port));
                    put(data, "username", str(item, "username"));
                    put(data, "password", str(item, "password"));
                    put(data, "version", "5");
                    return new ProxyNode(data);
                }
                case "http": {
                    JSONObject data = base("http", name, server, String.valueOf(port));
                    put(data, "username", str(item, "username"));
                    put(data, "password", str(item, "password"));
                    return new ProxyNode(data);
                }
                case "hysteria2":
                case "hy2": {
                    JSONObject data = base("hysteria2", name, server, String.valueOf(port));
                    put(data, "password", str(item, "password", "auth"));
                    put(data, "tls", yamlTls(item));
                    String obfs = str(item, "obfs");
                    if (!obfs.isEmpty() && !"none".equals(obfs)) {
                        JSONObject o = new JSONObject();
                        o.put("type", obfs);
                        String obfsPassword = str(item, "obfs-password", "obfs_password");
                        if (!obfsPassword.isEmpty()) o.put("password", obfsPassword);
                        data.put("obfs", o);
                    }
                    return new ProxyNode(data);
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value instanceof Integer) return (Integer) value;
            if (value != null && value.toString().matches("\\d+")) return Integer.parseInt(value.toString());
        }
        return 0;
    }

    private static JSONObject yamlTls(Map<String, Object> item) {
        JSONObject tls = new JSONObject();
        try {
            Object enabled = item.get("tls");
            boolean on = enabled != null && ("true".equals(enabled.toString()) || Boolean.TRUE.equals(enabled));
            if (!on && str(item, "sni").isEmpty() && str(item, "servername").isEmpty()) return null;
            tls.put("enabled", true);
            String sni = str(item, "sni", "servername", "servername", "peer");
            if (!sni.isEmpty()) tls.put("server_name", sni);
            if (Boolean.TRUE.equals(item.get("skip-cert-verify")) || "true".equals(str(item, "skip-cert-verify"))) tls.put("insecure", true);
            String fp = str(item, "client-fingerprint", "fingerprint");
            if (!fp.isEmpty()) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
            Map<String, Object> reality = map(item, "reality-opts");
            if (!reality.isEmpty()) {
                JSONObject r = new JSONObject();
                r.put("enabled", true);
                String pbk = str(reality, "public-key");
                if (!pbk.isEmpty()) r.put("public_key", pbk);
                String sid = str(reality, "short-id");
                if (!sid.isEmpty()) r.put("short_id", sid);
                tls.put("reality", r);
            }
        } catch (Exception ignored) {
        }
        return tls;
    }

    private static JSONObject yamlTransport(Map<String, Object> item, String net) {
        if (net == null || net.isEmpty() || "tcp".equals(net) || "none".equals(net)) return null;
        try {
            JSONObject transport = new JSONObject();
            switch (net) {
                case "ws": {
                    transport.put("type", "ws");
                    Map<String, Object> opts = map(item, "ws-opts");
                    String path = opts.isEmpty() ? str(item, "ws-path", "path") : str(opts, "path");
                    if (!path.isEmpty()) transport.put("path", path);
                    Map<String, Object> headers = map(opts, "headers");
                    String host = headers.isEmpty() ? str(item, "ws-headers-host", "host") : str(headers, "Host", "host");
                    if (!host.isEmpty()) {
                        JSONObject h = new JSONObject();
                        h.put("Host", host);
                        transport.put("headers", h);
                    }
                    int ed = parseInt(opts, "max-early-data");
                    if (ed > 0) {
                        transport.put("max_early_data", ed);
                        transport.put("early_data_header_name", "Sec-WebSocket-Protocol");
                    }
                    break;
                }
                case "grpc": {
                    transport.put("type", "grpc");
                    Map<String, Object> opts = map(item, "grpc-opts");
                    String service = str(opts, "grpc-service-name", "grpc_service_name");
                    if (!service.isEmpty()) transport.put("service_name", service);
                    break;
                }
                case "h2": {
                    transport.put("type", "http");
                    Map<String, Object> opts = map(item, "h2-opts");
                    String path = str(opts, "path");
                    if (!path.isEmpty()) transport.put("path", path);
                    Object hostObj = opts.get("host");
                    JSONArray hosts = new JSONArray();
                    if (hostObj instanceof List) {
                        for (Object o : (List<?>) hostObj) hosts.put(o.toString());
                    } else if (hostObj != null) {
                        hosts.put(hostObj.toString());
                    }
                    if (hosts.length() > 0) transport.put("host", hosts);
                    break;
                }
                default:
                    return null;
            }
            return transport;
        } catch (Exception ignored) {
        }
        return null;
    }

    // endregion

    private static List<ProxyNode> unique(List<ProxyNode> nodes) {
        List<ProxyNode> result = new ArrayList<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (ProxyNode node : nodes) {
            String tag = node.getTag().isEmpty() ? node.getType() : node.getTag();
            Integer times = count.get(tag);
            if (times == null) {
                count.put(tag, 1);
            } else {
                count.put(tag, times + 1);
                tag = tag + " (" + (times + 1) + ")";
            }
            node.setTag(tag);
            result.add(node);
        }
        return result;
    }
}

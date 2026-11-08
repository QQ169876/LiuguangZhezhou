package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.moontv.MoonSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 点播源快捷设置的远程配置（马先生 2026-11-08）。
 *
 * 以前快捷码写死在 Shortcut.java 里，改一个地址就要发一次版本。现在：
 *   服务器 /www/wwwroot/alistroot/webdata/file/Other/kuaijie.json 放快捷键和三个对应值，
 *   对外的 WebDAV 路径是 /dav/Other/kuaijie.json（和自建更新源同一套 Basic 凭据，见 SelfHost），
 *   App 启动时异步拉一次，取到的值覆盖内置默认值；拉不到用上次缓存，再不行用内置默认值。
 * JSON 形如：
 *   {"version":1,"shortcuts":[
 *     {"code":"520","vod":"http://...","live":"http://...","sync":"https://ys.169876.us.kg"}]}
 *   sync 兼容别名 moon / site；远程可新增快捷码，同码覆盖；某个值不写就沿用内置默认；
 *   值也可以写成数组（多个候选地址，按顺序挑）。
 *
 * 域名平替：169876.xyz / 12356.cool / 169876.us.kg 是同一批服务的不同入口。
 * ① 拉配置文件时三个 www 域名依次试，哪个通就记下它，下次先从它开始；
 * ② 配置里凡含这三个主域的地址，自动展开成三个同子域的候选（ok. / ys. / www. 都保留），
 *    后台并发探一次活，解析时优先返回探通的那个；全没探完/全不通就用服务器写的原值。
 *    不能整串硬替换：实测 www 走 xyz、ok 走 us.kg、ys 走 us.kg/xyz，各子域并不通用。
 *
 * 铁律：联网/解析/探活出错一律静默回退，绝不让启动崩；全程不阻塞主线程。
 */
public class ShortcutConfig {

    private static final String TAG = "Shortcut";
    private static final String KEY_CACHE = "shortcut_config_json";
    private static final String KEY_HOST = "shortcut_config_host";
    private static final String PATH = "/dav/Other/kuaijie.json";
    private static final int TIMEOUT = 6000;
    private static final int ALT_TIMEOUT = 12000; // 备用域名走 CDN，冷启动握手要 3~9 秒（Updater 同款）
    private static final int PROBE_TIMEOUT = 3000;

    /** 三个平替主域：马先生 2026-11-08 点名 */
    public static final String[] DOMAINS = {"169876.xyz", "12356.cool", "169876.us.kg"};

    private static final String DEFAULT_DIRECT = "http://www.\u996d\u592a\u786c.cc/tv";
    private static final String DEFAULT_SYNC = "https://ys.169876.us.kg";

    static class Item {
        final List<String> vod;
        final List<String> live;
        final List<String> sync;

        Item(List<String> vod, List<String> live, List<String> sync) {
            this.vod = vod;
            this.live = live;
            this.sync = sync;
        }
    }

    private static volatile Map<String, Item> table = defaults();
    private static volatile String alive = DOMAINS[0]; // 默认从主域 xyz 起（实测最快），拉通哪个就记住哪个
    private static final Set<String> verified = ConcurrentHashMap.newKeySet();

    private ShortcutConfig() {
    }

    /** App 启动调一次：先让上次的缓存立刻生效，再后台拉最新 + 探活 */
    public static void boot() {
        try {
            String host = Prefers.getString(KEY_HOST);
            if (isDomain(host)) alive = host;
            apply(Prefers.getString(KEY_CACHE));
        } catch (Throwable ignored) {
        }
        Task.execute(ShortcutConfig::refresh);
    }

    private static void refresh() {
        if (fetch()) probe();
    }

    private static boolean fetch() {
        int start = 0;
        for (int i = 0; i < DOMAINS.length; i++) if (DOMAINS[i].equals(alive)) start = i;
        for (int i = 0; i < DOMAINS.length; i++) {
            String domain = DOMAINS[(start + i) % DOMAINS.length];
            String url = "https://www." + domain + PATH;
            try {
                Request request = new Request.Builder().url(url)
                        .header("Authorization", SelfHost.auth())
                        .header("Cache-Control", "no-cache")
                        .cacheControl(CacheControl.FORCE_NETWORK).get().build();
                int timeout = domain.equals(DOMAINS[0]) ? TIMEOUT : ALT_TIMEOUT; // 主域快，CDN 备用域放宽
                try (Response res = OkHttp.client(timeout).newCall(request).execute()) {
                    if (!res.isSuccessful() || res.body() == null) continue;
                    String body = res.body().string();
                    if (body == null || body.trim().isEmpty() || !apply(body)) continue;
                    alive = domain;
                    Prefers.put(KEY_HOST, domain);
                    Prefers.put(KEY_CACHE, body);
                    DebugLog.d(TAG, "快捷配置已更新（" + domain + "）共 " + table.size() + " 个码");
                    return true;
                }
            } catch (Throwable e) {
                DebugLog.d(TAG, "快捷配置取不到 " + domain);
            }
        }
        DebugLog.d(TAG, "快捷配置三个域名都没取到，沿用缓存/默认值");
        return false;
    }

    /** 把所有含主域的地址并发探一遍，探通的记进 verified；探不完成/超时都不影响使用 */
    private static void probe() {
        Set<String> urls = new LinkedHashSet<>();
        for (Item item : table.values()) {
            urls.addAll(item.vod);
            urls.addAll(item.live);
            urls.addAll(item.sync);
        }
        urls.removeIf(ShortcutConfig::noAlias); // 外站地址（饭太硬等）不探，默认可用
        if (urls.isEmpty()) return;
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (String url : urls) futures.add(Task.largeExecutor().submit(() -> ping(url)));
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void ping(String url) {
        try {
            Request request = new Request.Builder().url(url).head().build();
            try (Response res = OkHttp.client(PROBE_TIMEOUT).newCall(request).execute()) {
                if (res.isSuccessful() || res.isRedirect()) verified.add(url);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 有些站点不认 HEAD，再补一次 GET（不读正文，连上就关）
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response res = OkHttp.client(PROBE_TIMEOUT).newCall(request).execute()) {
                if (res.isSuccessful() || res.isRedirect()) verified.add(url);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 优先返回探通的候选，没有就按服务器写的原值 */
    private static String resolve(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        for (String url : candidates) if (verified.contains(url)) return url;
        return candidates.get(0);
    }

    /** 解析并覆盖默认值，返回是不是一份有效配置 */
    private static boolean apply(String body) {
        if (body == null || body.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray array = root.optJSONArray("shortcuts");
            if (array == null || array.length() == 0) return false; // 空配置不算数，别把默认码冲掉
            Map<String, Item> next = defaults();
            int ok = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String code = obj.optString("code").trim();
                if (code.isEmpty()) continue;
                Item base = next.get(code);
                if (base == null) base = next.get("520"); // 服务器新增的码只写了部分字段：缺的按 520 的默认补齐，别解析出空地址
                List<String> vod = values(obj, "vod", "url");
                List<String> live = values(obj, "live");
                List<String> sync = values(obj, "sync", "moon", "site");
                next.put(code, new Item(
                        vod.isEmpty() && base != null ? base.vod : expand(vod),
                        live.isEmpty() && base != null ? base.live : expand(live),
                        sync.isEmpty() && base != null ? base.sync : expand(sync)));
                ++ok;
            }
            if (ok == 0) return false;
            table = next;
            verified.clear();
            return true;
        } catch (Throwable e) {
            DebugLog.d(TAG, "快捷配置解析失败，沿用内置默认");
            return false;
        }
    }

    /** 字段可以是字符串，也可以是候选数组；空值交给调用方沿用默认 */
    private static List<String> values(JSONObject obj, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isEmpty()) out.add(text);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    String text = array.optString(i, "").trim();
                    if (!text.isEmpty() && !out.contains(text)) out.add(text);
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** 把含三个主域之一的地址展开成「原值优先 + 另外两个主域」的候选，子域与路径原样保留 */
    private static List<String> expand(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String url : raw) {
            String domain = domainOf(url);
            if (domain == null) {
                if (!out.contains(url)) out.add(url);
                continue;
            }
            if (!out.contains(url)) out.add(url);
            for (String alt : DOMAINS) {
                if (alt.equals(domain)) continue;
                String candidate = url.replace(domain, alt);
                if (!out.contains(candidate)) out.add(candidate);
            }
        }
        return out;
    }

    private static String domainOf(String url) {
        if (url == null) return null;
        for (String domain : DOMAINS) if (url.contains(domain)) return domain;
        return null;
    }

    private static boolean noAlias(String url) {
        return domainOf(url) == null;
    }

    private static boolean isDomain(String host) {
        if (host == null) return false;
        for (String domain : DOMAINS) if (domain.equals(host)) return true;
        return false;
    }

    private static Map<String, Item> defaults() {
        Map<String, Item> map = new LinkedHashMap<>();
        map.put("520", new Item(expand(list(DEFAULT_DIRECT)), expand(list(DEFAULT_DIRECT)), expand(list(DEFAULT_SYNC))));
        map.put("521", new Item(expand(list("https://ok.169876.us.kg/sub/3")), expand(list(DEFAULT_DIRECT)), expand(list(DEFAULT_SYNC))));
        map.put("6669", new Item(expand(list("https://ok.169876.us.kg/sub/pg18")), expand(list(DEFAULT_DIRECT)), expand(list(DEFAULT_SYNC))));
        return map;
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        out.add(value);
        return out;
    }

    public static boolean isCode(String code) {
        return table.containsKey(code == null ? "" : code.trim());
    }

    /** 点播源；认不出的码原样返回（还可能是个真网址） */
    public static String vod(String text) {
        String value = text == null ? "" : text.trim();
        Item item = table.get(value);
        if (item == null) return value;
        String url = resolve(item.vod);
        return url.isEmpty() ? value : url;
    }

    /** 直播源 */
    public static String live(String text) {
        String value = text == null ? "" : text.trim();
        Item item = table.get(value);
        if (item == null) return value;
        String url = resolve(item.live);
        return url.isEmpty() ? value : url;
    }

    /** 影视站同步站点：快捷码顺手带上，取不到值就不动用户已设的 */
    public static void moon(String text) {
        String value = text == null ? "" : text.trim();
        Item item = table.get(value);
        if (item == null) return;
        String url = resolve(item.sync);
        if (url.isEmpty() || url.equals(MoonSetting.getUrl())) return;
        MoonSetting.putUrl(url);
    }
}

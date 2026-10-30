package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 115 网盘更新通道：登录 → 找包 → 取直链 → 下载 → 登出。
 *
 * 为什么要登录：这不是 App 自己去扒别人的分享，而是马先生自己的号 —— 分享链接
 * https://115cdn.com/s/swsuswd3hcv?password=0007 的**免登录下载额度只有 1T**，
 * 流量耗尽之后 115 会要求登录才给下载（2026-10-30 马先生）。所以这一步绕不过去：
 * **用账号密码登录**，下完即登出。
 *
 * 网页里拷出来的 Cookie 不能用 —— 会过期，而且每台设备都要手工做一次；
 * 反过来由 App 自己去登录，用户侧全程无感，设备上也不留会话。
 *
 * 登录走的都是 115 自己在用的接口：
 *   1. GET  /app/1.0/{app}/1.0/login/getKey                取 RSA 公钥（base64 的 PEM）
 *   2. 密文 = RSA_PKCS1( SHA1(密码) + "_" + 秒级时间戳 )，再 base64
 *   3. POST /app/1.0/{app}/1.0/login/login  account/passwd/cipher_ver/device_id → 种下 Cookie
 *
 * 两步验证的说明：这个账号开过，开发中曾一次性走过「发短信 → 提交验证码 →
 * two_step_trust/device_trust 把 device_id 记为受信任设备」。之后带同一个 device_id 登录
 * 就是免验证码的（实测直接返回登录成功），**用户侧永远不会被要求输验证码**。
 *
 * 凭据（账号、密码、受信任的 device_id）由 Gradle 从 `local.properties` 注入 BuildConfig，
 * **不写死在代码里、不进 Git**；三项有任何一个没配，整条通道自动跳过，退回浏览器/自建网盘。
 */
public class Pan115 {

    private static final String PASSPORT = "https://passportapi.115.com/app/1.0/";
    private static final String WEBAPI = "https://webapi.115.com/";
    private static final String APP = "qandroid";
    /** 115 只肯搭理看着像浏览器的请求，用 OkHttp 默认 UA 会被挡在门外 */
    private static final String UA = "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String SHARE_CODE = "swsuswd3hcv";
    /** 列目录一页多少条 */
    private static final int PAGE = 100;
    private static final String RECEIVE_CODE = "0007";

    /**
     * 这条通道**不用全局那个 OkHttp**：全局客户端为了方便站点适配，
     * 把证书校验和主机名校验都放开了（trust-all），还会走用户设的代理。
     * 登录请求里带着账号密码，走那个等于把密码敞开给中间人 —— 所以自己单独开一个
     * 严格校验证书、也不吃代理配置的客户端。代价是用户配了代理时这条通道不走代理。
     */
    private static OkHttpClient client() {
        return strict(TimeUnit.SECONDS.toMillis(20));
    }

    private static OkHttpClient download() {
        return strict(TimeUnit.MINUTES.toMillis(5));
    }

    private static OkHttpClient strict(long timeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    private static Request.Builder builder(String url) {
        return new Request.Builder().url(url).header("User-Agent", UA);
    }

    private static String api(String path) {
        return PASSPORT + APP + "/1.0" + path;
    }

    /** 三项凭据配齐了才启用这条通道 */
    public static boolean ready() {
        return !BuildConfig.PAN115_ACCOUNT.isEmpty() && !BuildConfig.PAN115_PASSWORD.isEmpty() && !BuildConfig.PAN115_DEVICE.isEmpty();
    }

    /* ---------- 登录 / 登出 ---------- */

    /**
     * 解开 115 给的公钥。注意它是**套了两层**：先把整个字符串 base64 解一次得到一段
     * PEM 文本（带 -----BEGIN / END PUBLIC KEY----- 的头尾和换行），再把中间那段
     * base64 解一次才是 DER。直接拿第一层的结果当 DER 用会抛 InvalidKeySpecException。
     */
    private static PublicKey pubKey(String wrapped) throws Exception {
        String pem = new String(Base64.decode(wrapped.trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
        StringBuilder der = new StringBuilder();
        for (String line : pem.split("\\n")) {
            String part = line.trim();
            if (part.isEmpty() || part.startsWith("-----")) continue;
            der.append(part);
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(der.toString(), Base64.DEFAULT)));
    }

    public static class Session {

        final String cookie;

        Session(String cookie) {
            this.cookie = cookie;
        }

        boolean live() {
            return cookie != null && cookie.contains("UID=");
        }
    }

    public static Session login() throws Exception {
        JSONObject key = new JSONObject(body(client().newCall(builder(api("/login/getKey")).get().build()).execute()));
        PublicKey pub = pubKey(key.getJSONObject("data").getString("key"));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        String text = sha1(BuildConfig.PAN115_PASSWORD) + "_" + (System.currentTimeMillis() / 1000);
        String passwd = Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        FormBody form = new FormBody.Builder()
                .add("account", BuildConfig.PAN115_ACCOUNT)
                .add("passwd", passwd)
                .add("cipher_ver", "2")
                .add("device_id", BuildConfig.PAN115_DEVICE)
                .build();
        try (Response res = client().newCall(builder(api("/login/login")).post(form).build()).execute()) {
            JSONObject data = new JSONObject(body(res));
            if (data.optInt("state", 0) != 1) throw new IllegalStateException("115 登录失败：" + data.optString("message", data.optString("error")));
            return new Session(join(res.headers("Set-Cookie")));
        }
    }

    /** 登出，不留会话 */
    public static void logout(Session session) {
        if (session == null || !session.live()) return;
        try {
            client().newCall(builder(api("/logout/logout")).header("Cookie", session.cookie).get().build()).execute().close();
        } catch (Exception ignored) {
        }
    }

    /* ---------- 找包 ---------- */

    public static class Apk {

        public final String name;
        public final long size;
        final String fid;
        final boolean legacy;

        Apk(String name, long size, String fid, boolean legacy) {
            this.name = name;
            this.size = size;
            this.fid = fid;
            this.legacy = legacy;
        }
    }

    /**
     * 把整个分享树铺开，把里面的 APK 都收上来。
     * 目录实际是四层：根 → 流光褶皱 → 主线版本/兼容版本 → v5.6.126 → 安装包，
     * 还有个 latest 目录放版本清单。层数按 owner 什么时候整理是会变的，
     * 所以这里递归走，别写死。
     */
    private static List<Apk> tree(Session session) throws Exception {
        List<Apk> result = new ArrayList<>();
        walk(session, "0", false, 0, result);
        return result;
    }

    private static void walk(Session session, String cid, boolean legacy, int depth, List<Apk> out) throws Exception {
        if (depth > 5) return;
        for (JSONObject item : level(session, cid)) {
            String name = item.optString("n");
            if (name.endsWith(".apk")) {
                out.add(new Apk(name, item.optLong("s"), item.optString("fid"), legacy));
            } else if (!item.has("fid") && !name.isEmpty()) {
                walk(session, item.optString("cid"), legacy || name.contains("兼容版本"), depth + 1, out);
            }
        }
    }

    /** JSONArray 不能 foreach，这里摊平成 List */
    private static List<JSONObject> items(JSONArray array) {
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) items.add(item);
        }
        return items;
    }

    /**
     * 一层的全部条目；带 fid 的是文件，没带的是目录。
     * 一次最多 100 条，拿满就接着翻下一页 —— 版本目录攒多了也不会漏掉最新那个包。
     */
    private static List<JSONObject> level(Session session, String cid) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (int offset = 0; offset < 1000; offset += PAGE) {
            String url = Uri.parse(WEBAPI + "share/snap").buildUpon()
                    .appendQueryParameter("share_code", SHARE_CODE)
                    .appendQueryParameter("receive_code", RECEIVE_CODE)
                    .appendQueryParameter("cid", cid)
                    .appendQueryParameter("offset", String.valueOf(offset))
                    .appendQueryParameter("limit", String.valueOf(PAGE))
                    .build().toString();
            JSONArray list;
            try (Response res = client().newCall(builder(url).header("Cookie", session.cookie).get().build()).execute()) {
                JSONObject data = new JSONObject(body(res)).optJSONObject("data");
                JSONArray array = data == null ? null : data.optJSONArray("list");
                list = array == null ? new JSONArray() : array;
            }
            List<JSONObject> items = items(list);
            result.addAll(items);
            if (items.size() < PAGE) break; // 没拿满，说明这一层就这么多了
        }
        return result;
    }

    /**
     * 从一堆 APK 里挑本机型本架构用的最新版，挑不到返回 null（调用方会退回别的通道）。
     * 文件名按 owner 定的规矩来：流光褶皱-<机型>-<版本>-<架构>.apk，
     * 兼容版的机型那段是「TV兼容版」，且只在「兼容版本」这条目录枝上。
     */
    public static Apk pick(List<Apk> items) {
        boolean tv = "leanback".equals(BuildConfig.FLAVOR_mode);
        boolean arm64 = "arm64_v8a".equals(BuildConfig.FLAVOR_abi);
        if (BuildConfig.LEGACY && !tv) return null; // 兼容版只出了 TV，手机版没有对应的包
        String kind = BuildConfig.LEGACY ? "TV兼容版" : tv ? "TV版" : "手机版";
        String abi = arm64 ? "arm64" : "armv7";
        Apk best = null;
        for (Apk item : items) {
            if (item.name == null || item.legacy != BuildConfig.LEGACY) continue;
            if (item.name.contains("legacy") != BuildConfig.LEGACY) continue;
            if (!item.name.contains(kind) || !item.name.contains(abi)) continue;
            if (best == null || version(item.name) > version(best.name)) best = item;
        }
        return best;
    }

    /** 文件名里的版本号，比如 流光褶皱-TV版-5.6.126-arm64.apk → 506126 */
    private static long version(String name) {
        long value = 0;
        for (String part : name.replace(".apk", "").split("-")) {
            String[] nums = part.split("\\.");
            if (nums.length < 2) continue;
            try {
                value = Long.parseLong(nums[0]) * 1000000L + Long.parseLong(nums[1]) * 1000L + Long.parseLong(nums[2]);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    /** 取这个文件的下载地址 */
    private static String link(Session session, Apk apk) throws Exception {
        String url = Uri.parse(WEBAPI + "share/downurl").buildUpon()
                .appendQueryParameter("share_code", SHARE_CODE)
                .appendQueryParameter("receive_code", RECEIVE_CODE)
                .appendQueryParameter("file_id", apk.fid)
                .build().toString();
        try (Response res = client().newCall(builder(url).header("Cookie", session.cookie).get().build()).execute()) {
            return new JSONObject(body(res)).getJSONObject("data").getJSONObject("url").getString("url");
        }
    }

    /* ---------- 下载 ---------- */

    /**
     * 走 115 通道把最新安装包下载到 app 缓存目录，交给系统安装器就能装。
     * 登录/登出自己搞定，失败返回 null，调用方退回别的通道。
     */
    public static File fetch(String apkName) {
        Session session = null;
        try {
            session = login();
            Apk apk = pick(tree(session));
            if (apk == null) throw new IllegalStateException("115 上没有本机型的安装包");
            return save(link(session, apk), session.cookie, apkName == null || apkName.isEmpty() ? apk.name : apkName, apk.size);
        } catch (Exception e) {
            DebugLog.d("Pan115", "115 通道没走通：" + e.getMessage());
            return null;
        } finally {
            logout(session);
        }
    }

    /**
     * 下到 app 的缓存目录（跟「检查更新」那条路落到同一个文件），交给系统安装器就能装。
     * 直链自带时效签名，**不需要 Cookie**（实测不带也能拉下来，不把会话 Cookie
     * 往 CDN 域名上送更干净）；万一失败再带一次 Cookie 重试。
     */
    private static File save(String url, String cookie, String name, long expect) throws Exception {
        File file = Path.cache(name);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("缓存目录不可用");
        Path.clear(file);
        for (int attempt = 0; attempt < 2; attempt++) {
            Request.Builder builder = builder(url);
            if (attempt > 0 && cookie != null && !cookie.isEmpty()) builder.header("Cookie", cookie);
            try (Response res = download().newCall(builder.build()).execute()) {
                if (!res.isSuccessful()) {
                    if (attempt == 0) continue; // 头一次不带 Cookie 没成功，带上再试一次
                    throw new IllegalStateException("下载失败：" + res.code());
                }
                InputStream in = res.body() == null ? null : res.body().byteStream();
                if (in == null) throw new IllegalStateException("下载没拿到内容");
                try (OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[128 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    out.flush();
                }
                if (expect > 0 && file.length() != expect) throw new IOException("下全了吗：磁盘上 " + file.length() + " 字节，网盘上是 " + expect);
                if (!isZip(file)) throw new IOException("下来的东西不是 APK");
                return file;
            } catch (IOException e) {
                if (attempt > 0) throw e;
            }
        }
        throw new IllegalStateException("下载中断");
    }

    /** APK 本质是个 zip，开头两个字节必然是 PK。防盗链/限流返回的网页在这一关就露馅 */
    private static boolean isZip(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static String body(@NonNull Response res) throws Exception {
        return res.body() == null ? "" : res.body().string();
    }

    /** 只留 115 认的那几个 Cookie 字段 */
    private static String join(List<String> raw) {
        StringBuilder builder = new StringBuilder();
        if (raw != null) {
            for (String item : raw) {
                for (String pair : item.split(";")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length != 2) continue;
                    String key = kv[0].trim();
                    if (!key.equals("UID") && !key.equals("CID") && !key.equals("SEID") && !key.equals("KID")) continue;
                    if (builder.length() > 0) builder.append("; ");
                    builder.append(key).append("=").append(kv[1].trim());
                }
            }
        }
        return builder.toString();
    }

    private static String sha1(String text) throws Exception {
        byte[] data = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : data) builder.append(String.format("%02x", item));
        return builder.toString();
    }
}

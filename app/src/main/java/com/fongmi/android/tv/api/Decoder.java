package com.fongmi.android.tv.api;

import android.util.Base64;

import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.HttpUrl;
import okhttp3.Response;

public class Decoder {

    private static final Pattern JS_URI = Pattern.compile("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"");

    /** 超过这个体量的配置不再做正则扫描与整串替换，避免大文件把内存打爆 */
    private static final int SCAN_LIMIT = 1024 * 1024;
    /** 单个配置文件上限，超过直接报错，不让超大文件把 App 拖崩 */
    private static final int MAX_SIZE = 24 * 1024 * 1024;

    public static String getJson(String url, String tag) throws Exception {
        try (Response res = OkHttp.newCall(url, tag).execute()) {
            HttpUrl httpUrl = res.request().url();
            int size = HttpUrl.parse(url).querySize();
            if (httpUrl.querySize() == size) url = httpUrl.toString();
            long length = res.body().contentLength();
            if (length > MAX_SIZE) throw new Exception("config too large: " + length);
            String data = res.body().string();
            if (data.length() > MAX_SIZE) throw new Exception("config too large: " + data.length());
            return verify(url, data);
        }
    }

    private static String verify(String url, String data) throws Exception {
        if (data.isEmpty()) throw new Exception();
        if (isJson(data)) return fix(url, data);
        if (data.length() <= SCAN_LIMIT && Json.isObj(data)) return fix(url, data);
        if (data.contains("**")) data = base64(data);
        if (data.startsWith("2423")) data = cbc(data.replaceAll("\\s+", ""));
        return fix(url, data);
    }

    /**
     * 只看第一个有效字符判断是不是 JSON。
     * 以前用 org.json 把整份配置再解析一遍来判断，大文件等于多占一份完整内存，是崩溃主因。
     */
    private static boolean isJson(String data) {
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            return c == '{' || c == '[';
        }
        return false;
    }

    private static String fix(String url, String data) {
        if (data.length() <= SCAN_LIMIT) {
            Matcher matcher = JS_URI.matcher(data);
            while (matcher.find()) data = replace(url, data, matcher.group());
            if (data.contains("../")) data = data.replace("../", UrlUtil.resolve(url, "../"));
            if (data.contains("./")) data = data.replace("./", UrlUtil.resolve(url, "./"));
            if (data.contains("__JS1__")) data = data.replace("__JS1__", "./");
            if (data.contains("__JS2__")) data = data.replace("__JS2__", "../");
        }
        return data;
    }

    private static String replace(String url, String data, String ext) {
        String t = ext.replace("\"./", "\"" + UrlUtil.resolve(url, "./"));
        t = t.replace("\"../", "\"" + UrlUtil.resolve(url, "../"));
        t = t.replace("./", "__JS1__").replace("../", "__JS2__");
        return data.replace(ext, t);
    }

    private static String cbc(String data) throws Exception {
        String decode = new String(Util.hex2byte(data)).toLowerCase();
        String key = padEnd(decode.substring(decode.indexOf("$#") + 2, decode.indexOf("#$")));
        String iv = padEnd(decode.substring(decode.length() - 13));
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes());
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        data = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        byte[] decryptData = cipher.doFinal(Util.hex2byte(data));
        return new String(decryptData, StandardCharsets.UTF_8);
    }

    private static String base64(String data) {
        String extract = extract(data);
        if (extract.isEmpty()) return data;
        return new String(Base64.decode(extract, Base64.DEFAULT));
    }

    private static String extract(String data) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(data);
        return matcher.find() ? data.substring(data.indexOf(matcher.group()) + 10) : "";
    }

    private static String padEnd(String key) {
        return key + "0000000000000000".substring(key.length());
    }
}

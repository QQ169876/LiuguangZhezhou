package com.fongmi.android.tv.moontv;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

public class MoonSetting {

    /** 影视站默认地址，自己人不用手敲 */
    public static final String DEFAULT_URL = "https://ys.169876.us.kg";

    public static String getUrl() {
        String url = Prefers.getString("moontv_url").trim();
        return url.isEmpty() ? DEFAULT_URL : url;
    }

    public static void putUrl(String url) {
        Prefers.put("moontv_url", url == null ? "" : url.trim());
    }

    public static String getUser() {
        return Prefers.getString("moontv_user").trim();
    }

    public static void putUser(String user) {
        Prefers.put("moontv_user", user == null ? "" : user.trim());
    }

    public static String getPass() {
        return Prefers.getString("moontv_pass").trim();
    }

    public static void putPass(String pass) {
        Prefers.put("moontv_pass", pass == null ? "" : pass.trim());
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean("moontv_enable");
    }

    public static void putEnabled(boolean enable) {
        Prefers.put("moontv_enable", enable);
    }

    public static boolean isAuto() {
        return Prefers.getBoolean("moontv_auto", true);
    }

    public static void putAuto(boolean auto) {
        Prefers.put("moontv_auto", auto);
    }

    public static long getLast() {
        return Prefers.getLong("moontv_last");
    }

    public static void putLast(long time) {
        Prefers.put("moontv_last", time);
    }

    public static String getBase() {
        String url = getUrl();
        if (url.isEmpty()) return "";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("url", getUrl());
            object.put("user", getUser());
            object.put("pass", getPass());
        } catch (Exception ignored) {
        }
        return object.toString();
    }

    public static boolean isValid() {
        return getBase().startsWith("http") && !getUser().isEmpty() && !getPass().isEmpty();
    }

    public static boolean isSyncable() {
        return isEnabled() && isValid();
    }

    /** 同步目标 = 站点网址 + 账号；换站点或换账号都等于换了一份数据源 */
    public static String getScope() {
        return getBase().concat("|").concat(getUser());
    }

    /** 上次由用户确认过「以哪边为准」的同步目标 */
    private static String getConfirm() {
        return Prefers.getString("moontv_confirm");
    }

    /** 目标换过之后还没选方向：true 表示需要先问一次，不能直接双向同步 */
    public static boolean isSwitch() {
        return !getConfirm().equals(getScope());
    }

    public static void putConfirm() {
        Prefers.put("moontv_confirm", getScope());
    }
}

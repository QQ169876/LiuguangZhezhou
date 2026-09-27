package com.fongmi.android.tv.moontv;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

public class MoonSetting {

    public static String getUrl() {
        return Prefers.getString("moontv_url").trim();
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
}

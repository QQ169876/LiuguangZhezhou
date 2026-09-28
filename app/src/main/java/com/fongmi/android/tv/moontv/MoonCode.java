package com.fongmi.android.tv.moontv;

import android.util.Base64;

import org.json.JSONObject;

public class MoonCode {

    private static final String TAG_TYPE = "type";
    private static final String TYPE = "moontv";

    public static String encode() {
        try {
            JSONObject object = new JSONObject();
            object.put(TAG_TYPE, TYPE);
            object.put("url", MoonSetting.getUrl());
            object.put("user", MoonSetting.getUser());
            object.put("pass", Base64.encodeToString(MoonSetting.getPass().getBytes(), Base64.NO_WRAP));
            return object.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 扫码导入只写站点网址 / 账号 / 密码，不启用同步。
     * 由用户确认账号无误后手动打开「启用同步」。
     */
    public static boolean decode(String text) {
        if (text == null || !text.trim().startsWith("{")) return false;
        try {
            JSONObject object = new JSONObject(text.trim());
            if (object.has(TAG_TYPE) && !TYPE.equals(object.optString(TAG_TYPE))) return false;
            String url = object.optString("url");
            String user = object.optString("user");
            if (url.isEmpty() || user.isEmpty()) return false;
            MoonSetting.putUrl(url);
            MoonSetting.putUser(user);
            MoonSetting.putPass(decodePass(object.optString("pass")));
            MoonSetting.putEnabled(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String decodePass(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.decode(value, Base64.NO_WRAP));
        } catch (Exception e) {
            return value;
        }
    }
}

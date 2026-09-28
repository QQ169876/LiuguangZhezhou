package com.fongmi.android.tv.webdav;

import android.util.Base64;

import org.json.JSONObject;

public class WebDavCode {

    private static final String TAG_TYPE = "type";
    private static final String TYPE = "webdav";

    public static String encode() {
        try {
            JSONObject object = new JSONObject();
            object.put(TAG_TYPE, TYPE);
            object.put("url", WebDavSetting.getUrl());
            object.put("user", WebDavSetting.getUser());
            object.put("pass", Base64.encodeToString(WebDavSetting.getPass().getBytes(), Base64.NO_WRAP));
            object.put("folder", WebDavSetting.getFolder());
            return object.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isWebDav(String text) {
        if (text == null || text.isEmpty()) return false;
        if (!text.trim().startsWith("{")) return false;
        return text.contains("\"url\"") && (text.contains("\"webdav\"") || text.contains("\"user\""));
    }

    public static boolean decode(String text) {
        try {
            JSONObject object = new JSONObject(text.trim());
            if (object.has(TAG_TYPE) && !TYPE.equals(object.optString(TAG_TYPE))) return false;
            WebDavSetting.putUrl(object.optString("url"));
            WebDavSetting.putUser(object.optString("user"));
            WebDavSetting.putPass(decodePass(object.optString("pass")));
            WebDavSetting.putFolder(object.optString("folder", WebDavSetting.getFolder()));
            WebDavSetting.putEnabled(true);
            return WebDavSetting.isValid();
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

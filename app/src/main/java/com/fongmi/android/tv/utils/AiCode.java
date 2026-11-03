package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.AiSetting;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 大模型配置的二维码（马先生 2026-11-03 要的「扫描二维码手机辅助录入」的另一半）：
 * 一台设备上配好了，生成二维码；另一台设备扫一下就录进去了，不用在小屏幕上敲 Key。
 *
 * 装的是**用户自建**那几条模型（内置的地址/Key 打散在包里，不用也不能搬）。
 * 扫到就按 id 合进本机列表，已有的那条谁新听谁的。
 */
public class AiCode {

    private static final String TAG_TYPE = "type";
    private static final String TYPE = "ai";

    public static String encode() {
        try {
            JSONArray models = new JSONArray(AiSetting.exportJson());
            if (models.length() == 0) return ""; // 一条自建模型都没有：别出个空码让人扫了白高兴
            JSONObject object = new JSONObject();
            object.put(TAG_TYPE, TYPE);
            object.put("models", models);
            return object.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 这段文本是不是大模型配置的二维码 */
    public static boolean isAi(String text) {
        if (text == null || text.isEmpty()) return false;
        if (!text.trim().startsWith("{")) return false;
        return text.contains("\"ai\"") && text.contains("\"models\"");
    }

    /** 扫到的配置合进本机；不是这个格式的返回 false，交给别的码去认 */
    public static boolean decode(String text) {
        if (!isAi(text)) return false;
        try {
            JSONObject object = new JSONObject(text.trim());
            JSONArray models = object.optJSONArray("models");
            if (models == null || models.length() == 0) return false;
            AiSetting.applyRemote(models.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

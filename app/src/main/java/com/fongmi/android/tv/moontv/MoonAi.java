package com.fongmi.android.tv.moontv;

import com.fongmi.android.tv.setting.AiSetting;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 大模型配置走影视站同步（马先生 2026-11-03 定：录进去的模型要跟着账号走）。
 *
 * 影视站只有「收藏 / 播放记录」两个通道，没有设置通道，所以**借一个保留键**放我们的配置：
 * 键名 `liuguang+ai`（source=liuguang、id=ai），内容塞在收藏条目的 cover 字段里。
 * 拉的时候认出这个保留键就取出来用，**不进收藏列表**；推的时候单独推这一条。
 *
 * 口径是「**账号优先、设备兜底**」（马先生 2026-11-03）：
 * 站点上有的话按 utime 合进本机；站点上没有（新账号 / 站点清过）就留着本机的那份，
 * 上传时再把本机的这份补上去——不会反过来把本机的清空。
 *
 * 里面装两样：用户自建的模型列表，和「是不是快捷码录进来的」这个内部标记。
 * 内置那两条的地址/Key 是打散在包里的，不同步，每台设备自己按内部标记决定有没有。
 */
public class MoonAi {

    private static final String SOURCE = "liuguang";
    private static final String ID = "ai";
    public static final String KEY = SOURCE.concat("+").concat(ID);

    /** 这条收藏是不是我们塞的大模型配置 */
    public static boolean is(String source, String id) {
        return SOURCE.equals(source) && ID.equals(id);
    }

    public static boolean is(String siteKey) {
        return KEY.equals(siteKey);
    }

    /** 拉下来的一条：取出配置合进本机（合不出来的当没看见） */
    public static void apply(JSONObject item) {
        if (item == null) return;
        String text = item.optString("cover");
        if (text.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(text);
            if (payload.optBoolean("insider")) AiSetting.putInsider(true);
            JSONArray models = payload.optJSONArray("models");
            if (models != null && models.length() > 0) AiSetting.applyRemote(models.toString());
        } catch (Exception ignored) {
        }
    }

    /** 本机这份打包成收藏条目；本机啥也没有就不推（别在别人站点上留条空数据） */
    private static JSONObject pack() {
        try {
            String json = AiSetting.exportJson();
            JSONArray array = json.isEmpty() ? new JSONArray() : new JSONArray(json);
            boolean insider = AiSetting.isInsider();
            if (array.length() == 0 && !insider) return null;
            JSONObject payload = new JSONObject();
            payload.put("v", 1);
            payload.put("insider", insider);
            payload.put("models", array);
            JSONObject item = new JSONObject();
            item.put("title", ID);
            item.put("source_name", SOURCE);
            item.put("save_time", System.currentTimeMillis());
            item.put("cover", payload.toString());
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    /** 往站点推一份（同步时调一次，没数据就不推） */
    public static void push() {
        try {
            JSONObject item = pack();
            if (item == null) return;
            MoonApi.saveFavorite(KEY, item);
        } catch (Exception ignored) {
        }
    }
}

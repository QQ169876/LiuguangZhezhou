package com.fongmi.android.tv.sync;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.moontv.MoonSetting;
import com.fongmi.android.tv.ui.dialog.SyncRiskDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

/**
 * 每个点播源各自留一套同步设置。
 *
 * 收藏和观看记录都是跟着点播源走的（换源就换一批数据），
 * 所以影视站的账号、WebDAV 的同步标识也必须跟着换，
 * 否则两个源的数据会被同步混在一起。
 *
 * 切换点播源时：先把当前这套存进旧源的档案，再套用新源上次用过的那套；
 * 新源从没配过就沿用现在的设置。不管哪种情况都把「启用同步」关掉，
 * 由用户确认无误后自己打开，避免换源瞬间就把两边的数据糊到一起。
 */
public class SyncProfile {

    private static final String CURRENT = "sync_profile_cid";

    private static String key(int cid) {
        return "sync_profile_" + cid;
    }

    /** 点播源加载完成后调：cid 变了就换一套同步设置 */
    public static void onVodChanged(int cid) {
        if (cid <= 0) return;
        int last = Prefers.getInt(CURRENT, 0);
        if (last == cid) return;
        if (last > 0) save(last);
        Prefers.put(CURRENT, cid);
        if (last > 0) apply(cid);
    }

    /** 把当前这套设置存进某个点播源的档案 */
    public static void save(int cid) {
        if (cid <= 0) return;
        try {
            JSONObject moon = new JSONObject();
            moon.put("url", MoonSetting.getUrl());
            moon.put("user", MoonSetting.getUser());
            moon.put("pass", MoonSetting.getPass());
            JSONObject dav = new JSONObject();
            dav.put("url", WebDavSetting.getUrl());
            dav.put("user", WebDavSetting.getUser());
            dav.put("pass", WebDavSetting.getPass());
            dav.put("folder", WebDavSetting.getFolder());
            JSONObject object = new JSONObject();
            object.put("moontv", moon);
            object.put("webdav", dav);
            Prefers.put(key(cid), object.toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * 套用某个点播源的同步设置。
     * 没有档案 = 这个源从没配过，沿用现在这套（多半是上一个源的），但一样不启用。
     */
    private static void apply(int cid) {
        boolean known = restore(cid);
        MoonSetting.putEnabled(false);
        WebDavSetting.putEnabled(false);
        Prefers.put("moontv_confirm", ""); // 换源之后第一次双向同步要重新选方向
        Prefers.put("webdav_confirm", "");
        App.post(() -> {
            Notify.show(known ? R.string.sync_profile_switch : R.string.sync_profile_new);
            SyncRiskDialog.show(App.activity(), R.string.sync_risk_vod, null);
        });
    }

    private static boolean restore(int cid) {
        String text = Prefers.getString(key(cid));
        if (text.isEmpty()) return false;
        try {
            JSONObject object = new JSONObject(text);
            JSONObject moon = object.optJSONObject("moontv");
            if (moon != null) {
                MoonSetting.putUrl(moon.optString("url"));
                MoonSetting.putUser(moon.optString("user"));
                MoonSetting.putPass(moon.optString("pass"));
            }
            JSONObject dav = object.optJSONObject("webdav");
            if (dav != null) {
                WebDavSetting.putUrl(dav.optString("url"));
                WebDavSetting.putUser(dav.optString("user"));
                WebDavSetting.putPass(dav.optString("pass"));
                WebDavSetting.putFolder(dav.optString("folder"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

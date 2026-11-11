package com.fongmi.android.tv.moontv;

import com.fongmi.android.tv.utils.DebugLog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 删除墓碑走影视站同步（马先生 2026-11-03 定：TV 上删掉的「最近观看」，不能又被别的设备同步拉回来）。
 *
 * 以前墓碑只记在**删除它的那一台设备**上：TV 删了某条记录并顺手删了站点那份，可手机本地还有这条，
 * 手机下一次同步把它推回站点，站点的保存时间比 TV 的墓碑新 → TV 判「站点后来又更新过」→ 拉回。删除等于白删。
 *
 * 所以墓碑也得跟着账号走。影视站只有「收藏 / 播放记录」两个通道，照 MoonAi 的样子**借一个保留键**：
 * 键名 `liuguang+tomb`（source=liuguang、id=tomb），内容塞在收藏条目的 cover 字段里，
 * 装 {record: {站点key: 删除时刻}, favorite: {…}}。拉的时候认出来合进本机墓碑（时间取新），
 * 本机还留着的对应条目顺手删掉（不然上传又推回站点）；推的时候把本机墓碑打进去。
 *
 * 只同步**当前账号**（mine）的墓碑——墓碑本来就是跟着「哪个账号的记录」走的，
 * 别的账号的墓碑留在本机等切回那个账号时用（跟 MoonSync 其它墓簿的口径一致）。
 */
public class MoonTomb {

    private static final String SOURCE = "liuguang";
    private static final String ID = "tomb";
    public static final String KEY = SOURCE.concat("+").concat(ID);

    /** 墓碑推上去最多带多少条：90 天清理前也得有个硬顶，cover 字段不能无限膨胀 */
    private static final int MAX_ITEMS = 300;

    /** 这条收藏是不是我们塞的删除墓碑 */
    public static boolean is(String source, String id) {
        return SOURCE.equals(source) && ID.equals(id);
    }

    /** 站点那份收藏表里有没有墓碑（有就直接取，不用挨个认） */
    public static void pullIn(JSONObject favorites) {
        if (favorites == null) return;
        apply(favorites.optJSONObject(KEY));
    }

    /** 拉下来的一条：合进本机墓碑（时间取新），并把本机命中的旧记录/收藏删掉 */
    public static void apply(JSONObject item) {
        if (item == null) return;
        String text = item.optString("cover");
        if (text.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(text);
            merge(payload.optJSONObject("record"), false);
            merge(payload.optJSONObject("favorite"), true);
        } catch (Exception ignored) {
        }
    }

    private static void merge(JSONObject remote, boolean favorite) {
        if (remote == null || remote.length() == 0) return;
        JSONObject local = MoonSync.loadTomb(favorite ? MoonSync.tombFavorite() : MoonSync.tombRecord());
        List<String> arrived = new ArrayList<>();
        int revoked = 0;
        try {
            for (Iterator<String> it = remote.keys(); it.hasNext(); ) {
                String key = it.next();
                long time = remote.optLong(key, 0);
                if (time <= 0) continue;
                if (time > local.optLong(key, 0)) {
                    // 删除之后本机又看过 / 又收藏过这部：这条墓碑已经过期，不能再拿它删本机数据。
                    // 旧实现只比「本机墓碑里有没有更晚的时间」，本机墓碑被 dropAlive 撤掉后，
                    // 站点那条老墓碑每次同步都会被当成「别台设备刚删的」，把刚看的记录又删一遍，
                    // 然后 push 又把墓碑推回站点 —— 看一次删一次，循环不停（2026-11-11 实测）。
                    // 这里直接把过期墓碑撤掉，后面的 push 会把站点那份也清干净。
                    if (MoonSync.revived(key, favorite, time)) {
                        local.remove(key);
                        ++revoked;
                        DebugLog.d("Sync", "墓碑已过期（本机之后又看过/收藏）：" + key);
                        continue;
                    }
                    local.put(key, time);
                    arrived.add(key);
                }
            }
        } catch (Exception ignored) {
        }
        if (arrived.isEmpty() && revoked == 0) return;
        // 撤掉的过期墓碑也要落盘，不然本机墓碑里还留着，下次同步又会被推回站点
        MoonSync.saveTomb(favorite ? MoonSync.tombFavorite() : MoonSync.tombRecord(), local);
        for (String key : arrived) MoonSync.purgeLocal(key, favorite); // 本机命中的删掉，上传才不会把它推回去
        DebugLog.d("Sync", "墓碑合并：拉到别台删过的 " + arrived.size() + " 条、撤掉过期 " + revoked + " 条（" + (favorite ? "收藏" : "记录") + "）");
    }

    /** 往站点推一份本机墓碑（同步时调一次；删完一条也会立刻推，见 markDeleted） */
    public static void push() {
        try {
            JSONObject record = MoonSync.loadTomb(MoonSync.tombRecord());
            JSONObject favorite = MoonSync.loadTomb(MoonSync.tombFavorite());
            if (record.length() == 0 && favorite.length() == 0) return;
            JSONObject payload = new JSONObject();
            payload.put("v", 1);
            payload.put("record", cap(record));
            payload.put("favorite", cap(favorite));
            JSONObject item = new JSONObject();
            item.put("title", ID);
            item.put("source_name", SOURCE);
            item.put("save_time", System.currentTimeMillis());
            item.put("cover", payload.toString());
            MoonApi.saveFavorite(KEY, item);
        } catch (Exception ignored) {
        }
    }

    /** 太老的砍掉：带上最新的 MAX_ITEMS 条就够，90 天外的本来也压不住删除了 */
    private static JSONObject cap(JSONObject source) {
        if (source.length() <= MAX_ITEMS) return source;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = source.keys(); it.hasNext(); ) keys.add(it.next());
        keys.sort((a, b) -> Long.compare(source.optLong(b, 0), source.optLong(a, 0)));
        JSONObject result = new JSONObject();
        for (int i = 0; i < MAX_ITEMS; i++) {
            try {
                result.put(keys.get(i), source.optLong(keys.get(i), 0));
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}

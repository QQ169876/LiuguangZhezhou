package com.fongmi.android.tv.sync;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.moontv.MoonSetting;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 数据归属账本。
 *
 * WebDAV 同步和影视站同步互不认识，它们只是各自盯着本机这一份观看记录 / 收藏 / 观看进度，
 * 所以「谁的数据」这个问题必须在两边各自记一遍：
 *   - WebDAV 看的是「同步标识」（同步文件地址），换了标识就是另一家人的数据；
 *   - 影视站看的是「用户名」（连同站点网址），换了用户名就是另一个账号的数据。
 * 本机每条数据（按 站点key+视频id 认）在两边各留一个归属标记，
 * 空 = 本机自己看出来的、还没跟任何账号打过交道，算当前账号的；
 * 有值但不等于当前账号 = 别人的数据，不往当前账号推、也不跟当前账号的删除一起删。
 *
 * 两本账互相独立：某条数据在影视站那边属于别的账号，不影响它在 WebDAV 这边正常同步
 * （本机是同一个人的数据池），只是它不会混进影视站那个账号。
 */
public class Owner {

    /** 通道 */
    public static final int DAV = 0;
    public static final int MOON = 1;

    /** 数据类型：观看记录 / 收藏 */
    public static final int RECORD = 0;
    public static final int FAVORITE = 1;

    private static final String[][] BOOK = {
            {"owner_webdav_record", "owner_webdav_favorite"},
            {"moontv_owner_record", "moontv_owner_favorite"}
    };

    /** 用过哪些组合身份；超量就把最久没用的那份账本/基线清掉 */
    private static final String REGISTRY = "owner_registry";
    private static final int MAX = 6;

    /** WebDAV 的基线按同步标识分开存，文件名后缀 */
    public static final String BASELINE = "webdav-baseline";

    /** 影视站那边按账号分开存的四本账 */
    public static final String[] MOON_BASE = {"moontv_base_record", "moontv_base_favorite"};
    public static final String[] MOON_TOMB = {"moontv_tomb_record", "moontv_tomb_favorite"};

    private Owner() {
    }

    /** 当前身份：影视站用网址+用户名，WebDAV 用同步文件地址（含同步标识）+账号 */
    public static String scope(int channel) {
        return channel == DAV ? WebDavSetting.getScope() : MoonSetting.getScope();
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xFF);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            return "s".concat(String.valueOf(text.hashCode()));
        }
    }

    /** 这个通道当前身份的短记号：给偏好项名、基线文件名用 */
    public static String id(int channel) {
        return hash(scope(channel));
    }

    public static String pref(String base, int channel) {
        return base.concat(".").concat(id(channel));
    }

    public static File baseline() {
        return new File(App.get().getFilesDir(), BASELINE.concat("-").concat(id(DAV)).concat(".json"));
    }

    /* ---------- 读写账本 ---------- */

    public static JSONObject load(int channel, int type) {
        return raw(BOOK[channel][type]);
    }

    private static JSONObject raw(String pref) {
        String text = Prefers.getString(pref);
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static void save(int channel, int type, JSONObject map) {
        Prefers.put(BOOK[channel][type], map.toString());
    }

    /** 这条数据归谁；空 = 还没分过账号 */
    public static String of(JSONObject book, String key) {
        return book.optString(key, "");
    }

    /** 别人的数据：当前这个账号不该碰 */
    public static boolean foreign(int channel, JSONObject book, String key) {
        String who = of(book, key);
        return !who.isEmpty() && !who.equals(scope(channel));
    }

    public static boolean foreign(int channel, String who) {
        return !who.isEmpty() && !who.equals(scope(channel));
    }

    /** 记一笔：这条数据现在归这个账号 */
    public static void mark(int channel, JSONObject book, String key) {
        try {
            book.put(key, scope(channel));
        } catch (Exception ignored) {
        }
    }

    /* ---------- 身份登记：别让旧身份的账本在本机无限攒下去 ---------- */

    public static void touch() {
        JSONObject reg = raw(REGISTRY);
        String id = id(DAV).concat("-").concat(id(MOON));
        try {
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("d", id(DAV));
            entry.put("m", id(MOON));
            reg.put(id, entry);
        } catch (Exception ignored) {
            return;
        }
        int size = reg.length();
        if (size > MAX) {
            List<String> all = new ArrayList<>();
            for (Iterator<String> it = reg.keys(); it.hasNext(); ) all.add(it.next());
            Collections.sort(all, (a, b) -> Long.compare(timeOf(reg, b), timeOf(reg, a)));
            for (int i = MAX; i < all.size(); i++) {
                JSONObject entry = reg.optJSONObject(all.get(i));
                if (entry != null) evict(entry.optString("d", ""), entry.optString("m", ""));
                reg.remove(all.get(i));
            }
        }
        Prefers.put(REGISTRY, reg.toString());
    }

    private static long timeOf(JSONObject reg, String name) {
        JSONObject entry = reg.optJSONObject(name);
        return entry == null ? 0 : entry.optLong("t", 0);
    }

    /** 清掉某个身份留下的东西：WebDAV 基线文件 + 影视站那边的基线、墓碑 */
    private static void evict(String davId, String moonId) {
        if (!davId.isEmpty()) {
            File file = new File(App.get().getFilesDir(), BASELINE.concat("-").concat(davId).concat(".json"));
            if (file.exists()) file.delete();
        }
        if (moonId.isEmpty()) return;
        for (String base : MOON_BASE) Prefers.put(base.concat(".").concat(moonId), "");
        for (String base : MOON_TOMB) Prefers.put(base.concat(".").concat(moonId), "");
    }

    /* ---------- 数据识别 ---------- */

    /**
     * 一条历史/收藏在两条通道里共同的记号：站点 key + 视频 id。
     * 主键里后面还跟着 cid（点播源），认人的时候用前两段就够了。
     */
    public static String key(String value) {
        if (value == null) return null;
        String[] parts = value.split(AppDatabase.SYMBOL);
        if (parts.length < 2) return null;
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null;
        return parts[0].concat("+").concat(parts[1]);
    }
}

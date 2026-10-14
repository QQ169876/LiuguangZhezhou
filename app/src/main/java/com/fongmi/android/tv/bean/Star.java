package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.webdav.SyncManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关注的演员（收藏里的「人」）。跟着同步走，几台设备共用一份。
 *
 * 按演员名去重，不按站点分：演员是跨站的，搜索时拿名字去全站搜就行，
 * 这也是它没跟影视收藏混在一张表里的原因（收藏是按站点 cid 隔离的）。
 *
 * 取消关注不真删记录，只置 deleted=1 并刷新 updateTime —— 同步按 updateTime「最后操作为准」，
 * 这台取消了，别的设备同步过来也会跟着取消；不然按并集一合，刚取消的又活了。
 */
@Entity
public class Star implements Diffable<Star> {

    @NonNull
    @PrimaryKey
    @SerializedName("name")
    private String name;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("updateTime")
    private long updateTime;
    @SerializedName("deleted")
    private int deleted;

    public static List<Star> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, Star.class).getType();
        List<Star> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static List<Star> getAll() {
        return AppDatabase.get().getStarDao().getAll();
    }

    /** 同步上传用：连取消关注那条记录一起给出去，别的设备才知道你取消了 */
    public static List<Star> getAllRaw() {
        return AppDatabase.get().getStarDao().findAll();
    }

    @Nullable
    public static Star find(String name) {
        String key = fix(name);
        if (key.isEmpty()) return null;
        return AppDatabase.get().getStarDao().find(key);
    }

    public static boolean exist(String name) {
        Star item = find(name);
        return item != null && item.getDeleted() == 0;
    }

    /** 关注。已经关注着返回 false（没变化，也就不用去碰同步） */
    public static boolean add(String name) {
        String key = fix(name);
        if (key.isEmpty()) return false;
        Star item = AppDatabase.get().getStarDao().find(key);
        if (item != null && item.getDeleted() == 0) return false;
        if (item == null) {
            item = new Star();
            item.setName(key);
            item.setCreateTime(System.currentTimeMillis());
        }
        item.setDeleted(0);
        item.setUpdateTime(System.currentTimeMillis());
        AppDatabase.get().getStarDao().insertOrUpdate(item);
        SyncManager.touch();
        return true;
    }

    /**
     * 关注 / 取消关注切换。返回切换后是不是「关注着」（true=关注上了，false=取消了）。
     * 详情页点一下就是这个：不用先判断再决定调 add 还是 remove。
     */
    public static boolean toggle(String name) {
        if (exist(name)) {
            remove(name);
            return false;
        }
        add(name);
        return true;
    }

    /**
     * [a=cr:{"id":"..","name":".."}] 这种壳；另一个是没壳、直接给 JSON 的站点。
     *
     * ⚠️ 2026-10-12 手机实测崩溃（api36 / Xiaomi）：原来写成 `[^{}]`，花括号没转义，
     *    Android 的正则实际是 ICU 引擎（`com.android.icu.util.regex.PatternNative`），
     *    它在字符类里也不接受裸 `{`，直接抛 PatternSyntaxException；PC 上 Java 自带引擎容忍，所以编译期看不出来。
     *    而这两个是 static final 字段，一炸就是 Star 类初始化失败（ExceptionInInitializerError），
     *    之后每次碰 Star 都变成 NoClassDefFoundError —— 进详情页必崩。
     *    所以：① 花括号一律转义；② 改成懒编译 + 出错降级，正则再有脾气也只当没这层壳，绝不带崩 App。
     */
    private static volatile Pattern shell;
    private static volatile Pattern json;
    private static volatile Pattern loose;
    private static volatile boolean broken; // 有一台机器的引擎认不了就认不了，别反复试

    private static Pattern shell() {
        Pattern p = shell;
        if (p != null) return p;
        if (broken) return null;
        try {
            return (shell = Pattern.compile("\\[[^\\[\\]\\{\\}]*\\{[^\\{\\}]*\\}[^\\[\\]\\{\\}]*\\]"));
        } catch (Throwable e) {
            broken = true;
            return null;
        }
    }

    private static Pattern json() {
        Pattern p = json;
        if (p != null) return p;
        if (broken) return null;
        try {
            return (json = Pattern.compile("\\{[^\\{\\}]*\\}"));
        } catch (Throwable e) {
            broken = true;
            return null;
        }
    }

    /** 不规整 JSON 时按文本抠名字的那把；同一台机器上前面通过它也不会有问题，但还是单独兜一层 */
    private static Pattern loose() {
        Pattern p = loose;
        if (p != null) return p;
        if (broken) return null;
        try {
            return (loose = Pattern.compile("\"?(?:name|actor|star)\"?\\s*[:=]\\s*\"?([^\"\\{\\},]+)"));
        } catch (Throwable e) {
            broken = true;
            return null;
        }
    }

    /**
     * 站点给的演员串（"张三,李四，王五"）拆成一个个名字。
     * 分隔符各家不一，全认（2026-10-12 马先生点名：还有 and、星号、@ 这些，一并拆开）：
     * 逗号顿号斜杠竖线分号、&、*、@、+，以及英文「and」（"张三 and 李四"）。
     * 去重、去空、去占位词（"未知/暂无"这类）、限制个数防刷屏。
     * ⚠️ 不按空格拆：「Tom Hanks」会被拆成两个人，比漏拆更糟；只有 and / & 这种明确的连接词才拆。
     * ⚠️ 不按「·」无脑拆：外国演员的中文译名里那个点是**名字的一部分**
     *    （小罗伯特·唐尼、莱昂纳多·迪卡普里奥），劈开就成两个人，
     *    关注和搜作品全是错的。它到底是不是分隔符交给 {@link #splitDot} 判断。
     * ⚠️ 有的站点把演员包在壳里：[a=cr:{"id":"吴越","name":"吴越"}]，里面那个逗号是 JSON 的，
     *    不先把它还原成名字，就会拆出 `"name":"吴越"}` 这种碎片挂到牌子上。
     */
    public static List<String> split(String actor) {
        List<String> names = new ArrayList<>();
        if (actor == null) return names;
        String text = unwrap(actor);
        text = text.replaceAll("(?i)^\\s*(主演|演员|cast)\\s*[:：]?\\s*", ""); // 「主演：张三」这种前缀
        text = text.replaceAll("(?i)\\s+and\\s+", "，");                       // 张三 and 李四
        for (String part : text.split("[,，、/／|｜;；&*＊@+]+")) {
            for (String piece : splitDot(part)) {
                String name = fix(piece);
                if (name.isEmpty() || names.contains(name) || dirty(name) || placeholder(name)) continue;
                names.add(name);
                if (names.size() >= 30) break;
            }
            if (names.size() >= 30) break;
        }
        return names;
    }

    /**
     * 「·」和「•」到底是名字的一部分还是演员之间的分隔符。
     *
     * 外国演员的中文译名几乎都带一个间隔号（小罗伯特·唐尼、萨缪尔·杰克逊、让·雷诺），
     * 而拿它当分隔符的站点，一串里必然连着用好几个（周润发·刘德华·梁朝伟——两个点三个人）。
     * 所以：**只出现一个点 = 一个人的名字，原样留着；两个及以上 = 那是列表，才拆。**
     * 偶尔会把「张三·李四」当成一个人（漏拆），但漏拆只是少一张牌，
     * 拆错是把一个人劈成两个（关注也错、搜作品也错），后者糟得多。
     */
    private static List<String> splitDot(String part) {
        List<String> out = new ArrayList<>();
        if (part == null) return out;
        int dot = count(part, '·');
        int bullet = count(part, '•');
        if (dot > 0 && bullet > 0) {
            // 两种混着用：间隔号是「一个人一个」，分隔符是「比人数少一个」，
            // 所以出现得少的那个才是分隔符（"A·B•C·D" → 拆成 A·B 和 C·D）
            for (String seg : part.split(bullet <= dot ? "[•]" : "[·]")) out.add(seg);
            return out;
        }
        if (dot + bullet >= 2) { // 同一种符号连着用好几个，那是列表，才拆
            for (String seg : part.split("[·•]")) out.add(seg);
            return out;
        }
        out.add(part);
        return out;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) n++;
        return n;
    }

    /** 站点偷懒填的占位词，不是人名 —— 有这些就当这一栏没有演员 */
    private static boolean placeholder(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        for (String word : PLACEHOLDER) if (lower.equals(word)) return true;
        return false;
    }

    private static final String[] PLACEHOLDER = {
            "未知", "暂无", "无", "不详", "内详", "见详情", "未知演员", "未收录",
            "n/a", "na", "none", "null", "unknown", "unknown actor"
    };

    /** 把 [a=cr:{"id":"..","name":".."}]（以及光秃秃的 {"id":..,"name":..}）还原成名字，认不出的整块抹掉 */
    private static String unwrap(String actor) {
        String text = actor;
        Pattern shell = shell();
        if (shell != null) text = replace(text, shell);
        Pattern json = json();
        if (json != null) text = replace(text, json);
        return text;
    }

    private static String replace(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return text;
        StringBuffer buffer = new StringBuffer();
        matcher.reset();
        while (matcher.find()) {
            String name = pick(matcher.group());
            // 认得出就补个逗号（两块挨着也不会粘成一个名字），认不出就当这里压根没演员、整块去掉
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(name.isEmpty() ? "," : name + ","));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 从一小段 JSON 里取名字：优先 name，其次 actor/star，最后才拿 id（纯数字的 id 不算名字） */
    private static String pick(String block) {
        try {
            JsonObject object = JsonParser.parseString(block).getAsJsonObject();
            for (String key : new String[]{"name", "actor", "star"}) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) return fix(object.get(key).getAsString());
            }
            if (object.has("id") && object.get("id").isJsonPrimitive()) {
                String id = fix(object.get("id").getAsString());
                if (!id.matches("\\d+")) return id;
            }
        } catch (Throwable ignored) {
            // 不是规整 JSON（缺引号、被截断），落到下面按文本抠一把
        }
        Pattern pattern = loose();
        if (pattern == null) return "";
        Matcher matcher = pattern.matcher(block);
        return matcher.find() ? fix(matcher.group(1)) : "";
    }

    /** 拆分后还带着 JSON 残渣（{ } " [ ]）的，不是人名 —— 宁可少一个，也不能把乱码挂到牌子上 */
    private static boolean dirty(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '{' || c == '}' || c == '"' || c == '[' || c == ']') return true;
        }
        return false;
    }

    /** 取消关注。压根没关注过返回 false */
    public static boolean remove(String name) {
        String key = fix(name);
        if (key.isEmpty()) return false;
        Star item = AppDatabase.get().getStarDao().find(key);
        if (item == null || item.getDeleted() == 1) return false;
        item.setDeleted(1);
        item.setUpdateTime(System.currentTimeMillis());
        AppDatabase.get().getStarDao().insertOrUpdate(item);
        SyncManager.touch();
        return true;
    }

    /** 同步下来的一批：同一个演员取 updateTime 新的那条（最后操作为准） */
    public static void merge(List<Star> items) {
        if (items == null || items.isEmpty()) return;
        for (Star item : items) {
            if (item == null || item.getName() == null || item.getName().trim().isEmpty()) continue;
            item.setName(fix(item.getName())); // 先归一化，不然「张三」和「 张三 」会存成两个人
            Star mine = AppDatabase.get().getStarDao().find(item.getName());
            if (mine != null && mine.getUpdateTime() > item.getUpdateTime()) continue;
            AppDatabase.get().getStarDao().insertOrUpdate(item);
        }
    }

    public static void deleteAll() {
        AppDatabase.get().getStarDao().deleteAll();
    }

    /** 演员名前后空格、全角逗号之类的脏东西清掉，免得同一演员存成两条 */
    public static String fix(String name) {
        if (name == null) return "";
        return name.trim().replace("　", "").replaceAll("\\s+", " ").trim();
    }

    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public int getDeleted() {
        return deleted;
    }

    public void setDeleted(int deleted) {
        this.deleted = deleted;
    }

    @Override
    public boolean isSameItem(Star other) {
        return other != null && Objects.equals(getName(), other.getName());
    }

    @Override
    public boolean isSameContent(Star other) {
        return isSameItem(other) && getDeleted() == other.getDeleted() && getUpdateTime() == other.getUpdateTime();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Star it)) return false;
        return Objects.equals(getName(), it.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}

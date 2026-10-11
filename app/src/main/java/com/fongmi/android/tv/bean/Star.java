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

    /** [a=cr:{"id":"..","name":".."}] 这种壳；另一个是没壳、直接给 JSON 的站点 */
    private static final Pattern SHELL = Pattern.compile("\\[[^\\[\\]]*\\{[^{}]*}[^\\[\\]]*\\]");
    private static final Pattern JSON = Pattern.compile("\\{[^{}]*}");

    /**
     * 站点给的演员串（"张三,李四，王五"）拆成一个个名字。
     * 分隔符各家不一（逗号、顿号、斜杠、分号都见过），全认；去重、去空、限制个数防刷屏。
     * ⚠️ 不按空格拆：「Tom Hanks」会被拆成两个人，比漏拆更糟。
     * ⚠️ 有的站点把演员包在壳里：[a=cr:{"id":"吴越","name":"吴越"}]，里面那个逗号是 JSON 的，
     *    不先把它还原成名字，就会拆出 `"name":"吴越"}` 这种碎片挂到牌子上。
     */
    public static List<String> split(String actor) {
        List<String> names = new ArrayList<>();
        if (actor == null) return names;
        for (String part : unwrap(actor).split("[,，、/|;；]+")) {
            String name = fix(part);
            if (name.isEmpty() || names.contains(name) || dirty(name)) continue;
            names.add(name);
            if (names.size() >= 30) break;
        }
        return names;
    }

    /** 把 [a=cr:{"id":"..","name":".."}]（以及光秃秃的 {"id":..,"name":..}）还原成名字，认不出的整块抹掉 */
    private static String unwrap(String actor) {
        return replace(replace(actor, SHELL), JSON);
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
        Matcher matcher = Pattern.compile("\"?(?:name|actor|star)\"?\\s*[:=]\\s*\"?([^\"{},]+)").matcher(block);
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

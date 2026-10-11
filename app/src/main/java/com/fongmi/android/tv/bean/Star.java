package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.webdav.SyncManager;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
     * 站点给的演员串（"张三,李四，王五"）拆成一个个名字。
     * 分隔符各家不一（逗号、顿号、斜杠、分号都见过），全认；去重、去空、限制个数防刷屏。
     * ⚠️ 不按空格拆：「Tom Hanks」会被拆成两个人，比漏拆更糟。
     */
    public static List<String> split(String actor) {
        List<String> names = new ArrayList<>();
        if (actor == null) return names;
        for (String part : actor.split("[,，、/|;；]+")) {
            String name = fix(part);
            if (name.isEmpty() || names.contains(name)) continue;
            names.add(name);
            if (names.size() >= 30) break;
        }
        return names;
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

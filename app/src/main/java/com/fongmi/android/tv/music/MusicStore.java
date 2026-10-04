package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 歌单本地存放。直接存 JSON 文件，跟同步格式一模一样，落盘上传都无需转换。
 */
public class MusicStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static volatile MusicList list;

    private static File file() {
        File dir = new File(App.get().getFilesDir(), "music");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "list.json");
    }

    public static synchronized MusicList get() {
        if (list == null) list = load();
        return list;
    }

    private static MusicList load() {
        try {
            File file = file();
            if (!file.exists()) return MusicList.empty();
            MusicList result = GSON.fromJson(new FileReader(file), MusicList.class);
            return result == null ? MusicList.empty() : result;
        } catch (Exception e) {
            return MusicList.empty();
        }
    }

    public static synchronized void replace(MusicList data) {
        list = data == null ? MusicList.empty() : data;
        save();
    }

    public static synchronized void save() {
        try {
            File file = file();
            FileWriter writer = new FileWriter(file, false);
            GSON.toJson(get(), writer);
            writer.flush();
            writer.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clear() {
        list = MusicList.empty();
        save();
    }

    /** 我的 + 收藏 + 每个自建歌单，统一成一个「分组」列表给界面用 */
    public static synchronized List<Group> groups() {
        List<Group> groups = new ArrayList<>();
        MusicList data = get();
        groups.add(new Group("default", "我的列表", data.getDefaultList()));
        groups.add(new Group("love", "我喜欢", data.getLoveList()));
        for (MusicList.UserList item : data.getUserList()) {
            groups.add(new Group(item.getId(), item.getName(), item.getList()));
        }
        return groups;
    }

    /** 这首歌在不在收藏里 */
    public static synchronized boolean loved(Music music) {
        for (Music item : get().getLoveList()) if (item.isSame(music)) return true;
        return false;
    }

    /** 收藏 / 取消收藏，返回操作后的状态 */
    public static synchronized boolean toggleLove(Music music) {
        MusicList data = get();
        List<Music> love = data.getLoveList();
        for (int i = 0; i < love.size(); i++) {
            if (love.get(i).isSame(music)) {
                love.remove(i);
                save();
                return false;
            }
        }
        love.add(0, music);
        save();
        return true;
    }

    public static class Group {

        private final String id;
        private final String name;
        private final List<Music> list;

        Group(String id, String name, List<Music> list) {
            this.id = id;
            this.name = name;
            this.list = list == null ? new ArrayList<>() : list;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Music> getList() {
            return list;
        }

        public int getSize() {
            return list.size();
        }
    }
}

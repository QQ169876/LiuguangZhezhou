package com.fongmi.android.tv.music;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步下来的整份歌单，结构与洛雪一致：我的 + 收藏 + 自建歌单。
 */
public class MusicList {

    @SerializedName("defaultList")
    private List<Music> defaultList;
    @SerializedName("loveList")
    private List<Music> loveList;
    @SerializedName("userList")
    private List<UserList> userList;

    public static MusicList empty() {
        MusicList list = new MusicList();
        list.defaultList = new ArrayList<>();
        list.loveList = new ArrayList<>();
        list.userList = new ArrayList<>();
        return list;
    }

    public List<Music> getDefaultList() {
        return defaultList == null ? new ArrayList<>() : defaultList;
    }

    public List<Music> getLoveList() {
        return loveList == null ? new ArrayList<>() : loveList;
    }

    public List<UserList> getUserList() {
        return userList == null ? new ArrayList<>() : userList;
    }

    public void setDefaultList(List<Music> list) {
        this.defaultList = list == null ? new ArrayList<>() : list;
    }

    public void setLoveList(List<Music> list) {
        this.loveList = list == null ? new ArrayList<>() : list;
    }

    public void setUserList(List<UserList> list) {
        this.userList = list == null ? new ArrayList<>() : list;
    }

    public int count() {
        int total = getDefaultList().size() + getLoveList().size();
        for (UserList item : getUserList()) total += item.getList().size();
        return total;
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    public static class UserList {

        @SerializedName("id")
        private String id;
        @SerializedName("name")
        private String name;
        @SerializedName("list")
        private List<Music> list;

        public String getId() {
            return id == null ? "" : id;
        }

        public String getName() {
            return name == null ? "" : name;
        }

        public List<Music> getList() {
            return list == null ? new ArrayList<>() : list;
        }

        public void setList(List<Music> list) {
            this.list = list == null ? new ArrayList<>() : list;
        }
    }
}

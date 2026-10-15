package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

/** SubDL 搜索接口的返回：status + results（匹配到的影片）+ subtitles（第一条影片的字幕） */
public class SubdlResponse {

    @SerializedName("status")
    private boolean status;
    @SerializedName("error")
    private String error;
    @SerializedName("subtitles")
    private List<SubdlItem> subtitles;
    @SerializedName("results")
    private List<Movie> results;

    public boolean isStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public List<SubdlItem> getSubtitles() {
        return subtitles == null ? new ArrayList<>() : subtitles;
    }

    public List<Movie> getResults() {
        return results == null ? new ArrayList<>() : results;
    }

    /** 匹配到的那部片子：中文名问出来的英文原名和 IMDb 编号就在这儿 */
    public static class Movie {

        @SerializedName("sd_id")
        private int sdId;
        @SerializedName("name")
        private String name;
        @SerializedName("imdb_id")
        private String imdbId;

        public int getSdId() {
            return sdId;
        }

        public String getName() {
            return TextUtils.isEmpty(name) ? "" : name;
        }

        public String getImdbId() {
            return TextUtils.isEmpty(imdbId) ? "" : imdbId;
        }

        /** IMDb 编号去掉前面的 tt，只留数字 —— OpenSubtitles 的 imdbid 接口只认纯数字 */
        public String getImdbNo() {
            String id = getImdbId();
            if (id.length() > 2 && id.toLowerCase().startsWith("tt")) return id.substring(2);
            return id;
        }
    }
}

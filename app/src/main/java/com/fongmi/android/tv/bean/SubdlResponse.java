package com.fongmi.android.tv.bean;

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

    public boolean isStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public List<SubdlItem> getSubtitles() {
        return subtitles == null ? new ArrayList<>() : subtitles;
    }
}

package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.gson.AssrtListAdapter;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AssrtResponse {

    /** &#123; / &#x27; 这类数字实体 */
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);?");

    @SerializedName("status")
    private int status;
    @SerializedName(value = "errmsg", alternate = "message")
    private String error;
    @SerializedName("sub")
    private Payload payload;

    public static AssrtResponse from(String text) {
        AssrtResponse response = App.gson().fromJson(text, AssrtResponse.class);
        return response == null ? new AssrtResponse() : response;
    }

    public String getError() {
        if (status == 0) return "";
        return TextUtils.isEmpty(error) ? String.valueOf(status) : error.trim();
    }

    public List<Subtitle> getSubtitles() {
        return payload == null ? Collections.emptyList() : payload.getSubtitles();
    }

    private static final class Payload {

        @SerializedName("subs")
        @JsonAdapter(AssrtListAdapter.class)
        private List<Subtitle> subtitles;

        private List<Subtitle> getSubtitles() {
            return subtitles == null ? Collections.emptyList() : subtitles;
        }
    }

    public static final class Subtitle {

        @SerializedName("id")
        private int id;
        @SerializedName("native_name")
        private String nativeName;
        @SerializedName("title")
        private String title;
        @SerializedName("filename")
        private String fileName;
        @SerializedName("videoname")
        private String videoName;
        @SerializedName("url")
        private String url;
        @SerializedName("lang")
        private JsonElement language;
        @SerializedName("filelist")
        @JsonAdapter(AssrtListAdapter.class)
        private List<SubtitleFile> files;

        public int getId() {
            return id;
        }

        public String getNativeName() {
            return clean(nativeName);
        }

        public String getTitle() {
            return clean(title);
        }

        public String getFileName() {
            return clean(fileName);
        }

        public String getVideoName() {
            return clean(videoName);
        }

        public String getUrl() {
            return clean(url);
        }

        public String getLanguage() {
            try {
                if (language == null || language.isJsonNull()) return "";
                if (language.isJsonPrimitive()) return clean(language.getAsString());
                JsonElement desc = language.getAsJsonObject().get("desc");
                return desc == null || desc.isJsonNull() ? "" : clean(desc.getAsString());
            } catch (Exception e) {
                return "";
            }
        }

        public boolean hasFileList() {
            return files != null;
        }

        public List<SubtitleFile> getFiles() {
            return files == null ? Collections.emptyList() : files;
        }
    }

    public static final class SubtitleFile {

        @SerializedName(value = "f", alternate = {"filename", "name"})
        private String name;
        @SerializedName("url")
        private String url;

        public String getName() {
            return clean(name);
        }

        public String getUrl() {
            return clean(url);
        }
    }

    private static String clean(String text) {
        return TextUtils.isEmpty(text) ? "" : unescape(text.trim());
    }

    /**
     * 伪射手返回的片名带着 HTML 实体（实测 "Jodorowsky&#x27;s Dune"），
     * 不处理列表里就会露出一串 &#x27;，看着像乱码。
     * 没用 Html.fromHtml()——那会把名字里的尖括号当标签吞掉，这里只做实体还原。
     */
    private static String unescape(String text) {
        if (text.indexOf('&') < 0) return text;
        String result = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&#x27;", "'");
        Matcher matcher = NUMERIC.matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            try {
                int code = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                matcher.appendReplacement(buffer, "");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}

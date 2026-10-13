package com.fongmi.android.tv.api;

import java.util.stream.Collectors;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.OpenSubItem;
import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.bean.SubtitleSearchPage;
import com.fongmi.android.tv.impl.ApiCallback;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SubtitleArchive;
import com.github.catvod.net.OkHttp;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

/**
 * 在线搜字幕的**第二个来源：OpenSubtitles**（走它家的公开查询接口 rest.opensubtitles.org）。
 *
 * 为什么加它：原来只有伪射手（Assrt）一家，而 Assrt **必须要 token**，
 * 没填 token 的时候点了搜索是**一点反应都没有**（旧代码直接 return），看着像功能坏了。
 * 这家不用注册、不用 token，英文片资源还最多，正好当备用：
 * 主源挂了 / 没结果 / 没填 token，自动换它（详见 SubtitleSearchDialog 的自动模式）。
 *
 * 接口返回的是 JSON 数组，每条带 SubFileName / SubDownloadLink(.gz) / ZipDownloadLink(zip)。
 * 我们统一走 **ZipDownloadLink**：解压那套（SubtitleArchive）本来就有，不用再写一套 gunzip。
 */
public class OpenSubApi {

    public static final int SEARCH_COUNT = 20;

    private static final String TAG = OpenSubApi.class.getSimpleName();
    private static final String SEARCH_URL = "https://rest.opensubtitles.org/search/query-";
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
        SubtitleApi.cancel(); // 下载槽位是两家共用的，一起停掉
    }

    public static void search(String keyword, int pos, Consumer<SubtitleSearchPage> success, Consumer<Exception> error) {
        int requestId = begin();
        newSearchCall(keyword).enqueue(new ApiCallback<>(requestId, REQUEST_ID, response -> parseSearch(read(response), pos), success, error));
    }

    /** 压缩包下载两家共用同一套：Assrt 那份实现是通用的（只认 url），这里直接借过来 */
    public static void loadArchive(SubtitleSearchItem item, Consumer<List<SubtitleSearchItem>> success, Consumer<Exception> error) {
        SubtitleApi.loadArchive(item, success, error);
    }

    private static int begin() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
        SubtitleApi.cancelArchive(); // 上次那个还没下完就别下了
        return REQUEST_ID.get();
    }

    private static Call newSearchCall(String keyword) {
        String query = URLEncoder.encode(TextUtils.isEmpty(keyword) ? "" : keyword, StandardCharsets.UTF_8);
        return OkHttp.newCall(SEARCH_URL + query, TAG);
    }

    private static SubtitleSearchPage parseSearch(String text, int pos) throws IOException {
        List<OpenSubItem> raw = parse(text);
        List<SubtitleSearchItem> items = raw.stream().map(OpenSubApi::mapItem).filter(item -> item != null).collect(Collectors.toList());
        items.sort(Comparator.comparingInt(OpenSubApi::langRank)); // 中文英文排前面，其它语言靠后
        int from = Math.max(0, Math.min(pos, items.size()));
        int to = Math.min(from + SEARCH_COUNT, items.size());
        List<SubtitleSearchItem> page = from >= to ? new ArrayList<>() : new ArrayList<>(items.subList(from, to));
        return SubtitleSearchPage.from(page, page.size());
    }

    private static List<OpenSubItem> parse(String text) throws IOException {
        if (TextUtils.isEmpty(text)) throw new IOException(ResUtil.getString(R.string.error_empty));
        List<OpenSubItem> list = App.gson().fromJson(text, new TypeToken<List<OpenSubItem>>() {
        }.getType());
        return list == null ? new ArrayList<>() : list;
    }

    private static SubtitleSearchItem mapItem(OpenSubItem item) {
        if (item == null) return null;
        String name = item.getName();
        if (!SubtitleArchive.isSupported(name, "")) return null; // 不是字幕格式（比如只有 .sub 图形字幕）就别列出来
        String zip = item.getZipLink();
        if (!TextUtils.isEmpty(zip)) return SubtitleSearchItem.archive(item.getIntId(), name, zip, item.getLang());
        String link = item.getDownloadLink();
        if (TextUtils.isEmpty(link)) return null;
        // ⚠️ 只看链接本身的后缀，别看字幕名：名字是 .srt，链接却是 .gz（它家单文件下载就是这个），
        // 拿名字去判会放过这个 .gz，结果把压缩包直接丢给播放器当字幕解析，必定读不出来。
        if (!SubtitleArchive.isSupported("", link)) return null;
        return SubtitleSearchItem.from(item.getIntId(), name, link, item.getLang());
    }

    /** 中英排前面，其它语言往后靠（只是排序，不丢结果） */
    private static int langRank(SubtitleSearchItem item) {
        String lang = item.getLang().toLowerCase();
        if (lang.contains("chinese") || lang.contains("chi") || lang.contains("中文") || lang.contains("简体") || lang.contains("繁體")) return 0;
        if (lang.contains("english") || lang.contains("eng")) return 1;
        return 2;
    }

    private static String read(Response response) throws IOException {
        String text = response.body().string();
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
        if (TextUtils.isEmpty(text)) throw new IOException(ResUtil.getString(R.string.error_empty));
        return text;
    }
}

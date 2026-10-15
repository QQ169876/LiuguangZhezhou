package com.fongmi.android.tv.api;

import java.util.stream.Collectors;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.SubdlItem;
import com.fongmi.android.tv.bean.SubdlResponse;
import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.bean.SubtitleSearchPage;
import com.fongmi.android.tv.impl.ApiCallback;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SubtitleArchive;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Response;

/**
 * 在线搜字幕的**第三个来源：SubDL**（subdl.com）。
 *
 * 为什么加它：前面两家各有短板——伪射手（Assrt）中文最全但**必须填 token**，
 * OpenSubtitles 老接口免 key 但**只认英文片名**（中文名一条搜不到，还得靠播放地址兜底）。
 * 这家补在中间：
 *   - 免登录就能下（匿名 300 次/天/IP，够用）；
 *   - 免费 key 每天 2000 次搜索，注册后一键拿到；
 *   - 库里带 Subscene 的老存档，冷门片、老片反而更容易有；
 *   - 搜索一次最多给 30 条，**没有翻页**，所以 SEARCH_COUNT 设成"永远没有下一页"。
 *
 * 它返回的下载链接是相对路径（/subtitle/xxx.zip），要拼上 dl.subdl.com；
 * 基本都是 zip 包，解压那套直接复用 SubtitleApi.loadArchive。
 */
public class SubdlApi {

    /** 这家一次性返回（最多 30 条、不支持翻页），所以永远不会有"加载更多" */
    public static final int SEARCH_COUNT = Integer.MAX_VALUE;

    private static final String TAG = SubdlApi.class.getSimpleName();
    private static final String SEARCH_URL = "https://api.subdl.com/api/v1/subtitles";
    private static final String DOWNLOAD_HOST = "https://dl.subdl.com";
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    public static boolean hasKey() {
        return !TextUtils.isEmpty(SubtitleSetting.getEffectiveSubdlKey());
    }

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
        SubtitleApi.cancelArchive(); // 下载槽位是几家共用的，一起停掉
    }

    /**
     * 查一次。中文片名分两步：
     *   1. 先带 languages=ZH 问一遍，有中文字幕就只要中文（不筛的话三十条里大半是印尼语、阿拉伯语，中文一条看不到）；
     *   2. **筛完一条都没有就放开语言再问一遍** —— 实测「庆余年」筛中文是 0 条、不筛有 30 条，
     *      宁可给一堆外语字幕，也别让用户对着空列表发呆（有字幕总比没有强，播放器里还能再挑）。
     * 英文片名本来就不筛，一步到位。
     */
    public static void search(String keyword, Consumer<SubtitleSearchPage> success, Consumer<Exception> error) {
        int requestId = begin();
        boolean cjk = containsCJK(keyword);
        newSearchCall(keyword, cjk).enqueue(new ApiCallback<>(requestId, REQUEST_ID, response -> parseSearch(read(response)), page -> {
            if (!cjk || page.getResultCount() > 0) success.accept(page);
            else retryWithoutLang(requestId, keyword, success, error, page);
        }, error));
    }

    /** 中文筛完一条都没有，放开语言再问一遍；这次要是还是空的，就把第一次那（空）结果交回去 */
    private static void retryWithoutLang(int requestId, String keyword, Consumer<SubtitleSearchPage> success, Consumer<Exception> error, SubtitleSearchPage first) {
        newSearchCall(keyword, false).enqueue(new ApiCallback<>(requestId, REQUEST_ID, response -> parseSearch(read(response)), page -> success.accept(page.getResultCount() > 0 ? page : first), error));
    }

    /** 压缩包下载几家共用同一套：Assrt 那份实现是通用的（只认 url），这里直接借过来 */
    public static void loadArchive(SubtitleSearchItem item, Consumer<List<SubtitleSearchItem>> success, Consumer<Exception> error) {
        SubtitleApi.loadArchive(item, success, error);
    }

    private static int begin() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
        SubtitleApi.cancelArchive(); // 上次那个还没下完就别下了
        return REQUEST_ID.get();
    }

    private static Call newSearchCall(String keyword, boolean chineseOnly) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(SEARCH_URL)).newBuilder();
        builder.addQueryParameter("api_key", SubtitleSetting.getEffectiveSubdlKey());
        builder.addQueryParameter("film_name", TextUtils.isEmpty(keyword) ? "" : keyword.trim());
        if (chineseOnly) builder.addQueryParameter("languages", "zh");
        builder.addQueryParameter("subs_per_page", "30");
        builder.addQueryParameter("client", "custom_integration");
        return OkHttp.newCall(builder.build().toString(), TAG);
    }

    /** 关键字里有没有中日韩文字：有就是拿中文片名在搜，没有就是英文/拼音原名 */
    private static boolean containsCJK(String keyword) {
        if (TextUtils.isEmpty(keyword)) return false;
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return true;
        }
        return false;
    }

    private static SubtitleSearchPage parseSearch(String text) throws IOException {
        if (TextUtils.isEmpty(text)) throw new IOException(ResUtil.getString(R.string.error_empty));
        SubdlResponse response = App.gson().fromJson(text, SubdlResponse.class);
        if (response == null) throw new IOException(ResUtil.getString(R.string.error_empty));
        if (!TextUtils.isEmpty(response.getError())) throw new IOException(response.getError());
        List<SubdlItem> raw = response.getSubtitles();
        List<SubtitleSearchItem> items = raw == null ? new ArrayList<>() : raw.stream().map(SubdlApi::mapItem).filter(Objects::nonNull).collect(Collectors.toList());
        items.sort(Comparator.comparingInt(SubdlApi::langRank)); // 中文排前面，其它语言靠后
        return SubtitleSearchPage.withHints(items, items.size(), hintsOf(response));
    }

    /**
     * 它家回包里还带着"这片子是哪一部"（results），里面有英文原名和 IMDb 编号。
     * 这两个正是其它国外站认的写法 —— 拿中文名问 OpenSubtitles 一条都没有，换成 IMDb 编号就有上百条。
     */
    private static List<String> hintsOf(SubdlResponse response) {
        List<String> hints = new ArrayList<>();
        for (SubdlResponse.Movie movie : response.getResults()) {
            addHint(hints, movie.getImdbNo());
            addHint(hints, movie.getName());
        }
        return hints;
    }

    private static void addHint(List<String> hints, String value) {
        if (TextUtils.isEmpty(value)) return;
        String text = value.trim();
        for (String exist : hints) if (exist.equalsIgnoreCase(text)) return;
        hints.add(text);
    }

    private static SubtitleSearchItem mapItem(SubdlItem item) {
        if (item == null) return null;
        String url = item.getUrl();
        if (TextUtils.isEmpty(url)) return null;
        if (!url.startsWith("http")) url = DOWNLOAD_HOST + (url.startsWith("/") ? "" : "/") + url;
        // name 是它家站内文件名（"SUBDL::dune-2021-2770255.zip"），给用户看得用 release_name
        String name = item.getReleaseName();
        if (TextUtils.isEmpty(name)) name = item.getName();
        if (TextUtils.isEmpty(name)) return null;
        if (!SubtitleArchive.isSupported(name, url)) return null; // 不是字幕格式就别列出来
        return SubtitleArchive.isZip(name, url) ? SubtitleSearchItem.archive(0, name, url, item.getLang()) : SubtitleSearchItem.from(0, name, url, item.getLang());
    }

    /** 中英排前面，其它语言往后靠（只是排序，不丢结果） */
    private static int langRank(SubtitleSearchItem item) {
        String lang = TextUtils.isEmpty(item.getLang()) ? "" : item.getLang().toLowerCase(Locale.ROOT);
        if (lang.contains("chi") || lang.contains("zh") || lang.contains("中文") || lang.contains("简体") || lang.contains("繁體")) return 0;
        if (lang.contains("eng") || lang.contains("en")) return 1;
        return 2;
    }

    private static String read(Response response) throws IOException {
        String text = response.body().string();
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
        if (TextUtils.isEmpty(text)) throw new IOException(ResUtil.getString(R.string.error_empty));
        return text;
    }
}

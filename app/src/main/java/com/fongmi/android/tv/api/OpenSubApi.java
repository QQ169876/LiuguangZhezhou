package com.fongmi.android.tv.api;

import java.util.stream.Collectors;
import android.net.Uri;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.OkHttpClient;
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
    private static final String SEARCH_HOST = "https://rest.opensubtitles.org";
    private static final String SEARCH_URL = SEARCH_HOST + "/search/query-";
    private static final int MAX_REDIRECT = 3;
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    private static volatile OkHttpClient client;

    /**
     * 本站请求固定走这个「不自动跳」的 client（跳转我们自己处理，见 follow）。
     * 必须复用同一个实例：OkHttp.cancel(tag) 只认默认 client 的 dispatcher，
     * 每次 newBuilder 出来的新 client 是另一套调度，取消就够不着了（关弹窗、换来源时旧请求还在跑）。
     */
    private static OkHttpClient client() {
        if (client == null) client = OkHttp.noRedirect();
        return client;
    }

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(client(), TAG);
        SubtitleApi.cancel(); // 下载槽位是两家共用的，一起停掉
    }

    public static void search(String keyword, int pos, Consumer<SubtitleSearchPage> success, Consumer<Exception> error) {
        int requestId = begin();
        newSearchCall(keyword).enqueue(new ApiCallback<>(requestId, REQUEST_ID, response -> parseSearch(read(follow(response)), pos), success, error));
    }

    /**
     * 自己跟着跳，别让 OkHttp 直接跳。
     *
     * 实测（2026-10-14）：关键字里只要带大写字母，它家 Cloudflare 就回一个 302，
     * Location 是 https://_/search/query-inception+2010 —— 主机名被写成了一个下划线，
     * 谁去请求这个地址都是 UnknownHostException，等于**所有英文片名（首字母大写）全搜不出来**，
     * 外语片在线找字幕直接废掉。两层处理：
     *   1) 拼 URL 前先把关键字转小写，CF 就不会触发那条重定向规则（已实测 200）；
     *   2) 真遇到 3xx 也不怕：把 Location 里的主机名换回本站域名再请求一次，最多跟 3 跳。
     */
    private static Response follow(Response response) throws IOException {
        Response result = response;
        for (int i = 0; i < MAX_REDIRECT && isRedirect(result); i++) {
            String next = fixLocation(result.header("Location"));
            result.close();
            if (TextUtils.isEmpty(next)) break;
            result = OkHttp.newCall(client(), next, TAG).execute();
        }
        return result;
    }

    private static boolean isRedirect(Response response) {
        int code = response.code();
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Location 可能是 https://_/search/xxx 这种坏主机名，也可能是 /search/xxx 相对路径，统一拼回本站域名 */
    private static String fixLocation(String location) {
        if (TextUtils.isEmpty(location)) return "";
        Uri uri = Uri.parse(location);
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) return "";
        StringBuilder url = new StringBuilder(SEARCH_HOST).append(path);
        if (!TextUtils.isEmpty(uri.getQuery())) url.append('?').append(uri.getQuery());
        return url.toString();
    }

    /** 压缩包下载两家共用同一套：Assrt 那份实现是通用的（只认 url），这里直接借过来 */
    public static void loadArchive(SubtitleSearchItem item, Consumer<List<SubtitleSearchItem>> success, Consumer<Exception> error) {
        SubtitleApi.loadArchive(item, success, error);
    }

    private static int begin() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(client(), TAG);
        SubtitleApi.cancelArchive(); // 上次那个还没下完就别下了
        return REQUEST_ID.get();
    }

    private static Call newSearchCall(String keyword) {
        String imdb = imdbNo(keyword);
        // 别的家（SubDL）拿中文名问出了这片子的 IMDb 编号：它家认这个，一次能给出上百条，
        // 比拿中文名去搜（0 条）强太多
        if (!imdb.isEmpty()) return OkHttp.newCall(client(), SEARCH_HOST + "/search/imdbid-" + imdb, TAG);
        return OkHttp.newCall(client(), SEARCH_URL + encode(keyword), TAG);
    }

    /** IMDb 编号（纯数字，tt 前缀已去掉）；不是编号就返回空 */
    private static String imdbNo(String keyword) {
        if (TextUtils.isEmpty(keyword)) return "";
        String text = keyword.trim();
        if (text.length() < 6 || text.length() > 9) return "";
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return "";
        return text;
    }

    /** 关键字转小写：大小写对它家搜索没影响，但能绕开 CF 那条要命的重定向（见 follow 的说明） */
    private static String encode(String keyword) {
        String text = TextUtils.isEmpty(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
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

package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.setting.TmdbSetting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 演员作品查询（TMDB）。
 *
 * 干的事就一件：给个演员名，把他演过的电影、剧集列出来，名字是准的。
 * 拿到准名字之后干什么（回 APP 里全站搜）不归它管。
 *
 * 设计上跟 AiJudge 一样是「可有可无」的：关掉、超时、报错、查不到人，一律回调 null，
 * 调用方退回原来的「拿演员名直接搜」，绝不让用户干等着。
 *
 * 通路有两条（TmdbSetting 里有说明）：自建 CORS 代理转发官方域名 + 直连 api.tmdb.org，
 * 两条一起发，谁先回来用谁。
 */
public class Tmdb {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final ConcurrentHashMap<String, Entry> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    /** 演员的作品不常变，缓存一周 */
    private static final long TTL = 7 * 24 * 60 * 60 * 1000L;
    /** 查不到的人只压这么久就重新给机会，免得一次断网把人判了死刑 */
    private static final long MISS_TTL = 6 * 60 * 60 * 1000L;
    /** 一栏最多列多少部，多了划不到底也没意义 */
    private static final int LIMIT = 60;
    private static final int MEMO_LIMIT = 300;

    /** 片名认领/条件筛选的会话级缓存：同一个问题半小时内不再跑一轮网络 */
    private static final long QUERY_TTL = 30 * 60 * 1000L;
    private static final ConcurrentHashMap<String, Entry> QUERY = new ConcurrentHashMap<>();

    /**
     * 中文类型名 → TMDB 类型编号（{中文名, 电影编号, 剧集编号}；空串表示那边没这个类型，不过滤）。
     * 用「包含」匹配：模型说「动作冒险」也能认。顺序有意义，长的/具体的排前面。
     */
    private static final String[][] GENRES = {
            {"动作冒险", "28", "10759"}, {"科幻奇幻", "878", "10765"},
            {"动作", "28", "10759"}, {"冒险", "12", "10759"}, {"武侠", "28", "10759"},
            {"动画", "16", "16"}, {"喜剧", "35", "35"}, {"犯罪", "80", "80"},
            {"纪录", "99", "99"}, {"剧情", "18", "18"}, {"家庭", "10751", "10751"},
            {"奇幻", "14", "10765"}, {"魔幻", "14", "10765"}, {"历史", "36", ""},
            {"恐怖", "27", "9648"}, {"惊悚", "53", "9648"}, {"悬疑", "9648", "9648"},
            {"爱情", "10749", "18"}, {"言情", "10749", "18"}, {"科幻", "878", "10765"},
            {"战争", "10752", "10768"}, {"西部", "37", "37"}, {"音乐", "10402", ""},
            {"儿童", "10751", "10762"}, {"古装", "18", "18"}, {"真人秀", "", "10764"},
    };

    private static volatile OkHttpClient client;
    private static volatile boolean loaded;

    public interface Callback {

        void onResult(List<TmdbWork> works);
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (Tmdb.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(TmdbSetting.TIMEOUT, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return client;
    }

    /** 查过并缓存着的作品列表，直接给；没查过返回 null */
    public static List<TmdbWork> peek(String name) {
        load();
        Entry entry = MEMO.get(key(name));
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.at > TTL) return null;
        return entry.list.isEmpty() ? null : entry.list;
    }

    /**
     * 查某演员演过什么。结果记在本机（一周内不再问），任何异常都回调 null。
     * 已在查同一个人的时候重复调用，直接回调 null，别重复烧请求。
     */
    public static void works(String name, Callback callback) {
        String key = key(name);
        if (TextUtils.isEmpty(key)) {
            reply(callback, null);
            return;
        }
        load();
        Entry entry = MEMO.get(key);
        if (entry != null && System.currentTimeMillis() - entry.at <= TTL) {
            reply(callback, entry.list.isEmpty() ? null : entry.list);
            return;
        }
        if (!TmdbSetting.isEnabled()) {
            reply(callback, null);
            return;
        }
        if (ASKING.putIfAbsent(key, Boolean.TRUE) != null) {
            reply(callback, null);
            return;
        }
        POOL.execute(() -> {
            List<TmdbWork> list = null;
            try {
                list = request(name);
            } catch (Throwable e) {
                DebugLog.d("Tmdb", "查不到「" + name + "」的作品：" + e);
            }
            ASKING.remove(key);
            // 查不到也记一笔（存空列表）：下次点同一个人直接走「拿名字全站搜」的老路，不用再干等一轮。
            // 但只压 6 小时——万一是当时断网，别让人从此再也查不到。
            if (MEMO.size() > MEMO_LIMIT) MEMO.clear();
            Entry saved = new Entry(list);
            if (list == null) saved.at = System.currentTimeMillis() - TTL + MISS_TTL;
            MEMO.put(key, saved);
            save();
            List<TmdbWork> result = list;
            App.post(() -> reply(callback, result == null || result.isEmpty() ? null : result));
        });
    }

    /** 按片名在 TMDB 上认一认：拿准名字、海报、评分，顺带确认这片子真实存在。查不到回调 null。 */
    public static void search(String title, Callback callback) {
        String key = "s:" + key(title);
        if (title == null || title.trim().isEmpty()) {
            reply(callback, null);
            return;
        }
        Entry entry = QUERY.get(key);
        if (entry != null && System.currentTimeMillis() - entry.at <= QUERY_TTL) {
            reply(callback, entry.list.isEmpty() ? null : entry.list);
            return;
        }
        if (!TmdbSetting.isEnabled()) {
            reply(callback, null);
            return;
        }
        POOL.execute(() -> {
            List<TmdbWork> list = null;
            try {
                list = query(title);
            } catch (Throwable e) {
                DebugLog.d("Tmdb", "认领「" + title + "」失败：" + e);
            }
            if (QUERY.size() > 200) QUERY.clear();
            QUERY.put(key, new Entry(list));
            List<TmdbWork> result = list;
            App.post(() -> reply(callback, result == null || result.isEmpty() ? null : result));
        });
    }

    /**
     * 按条件筛片子（「今年新出的动作片」「评分高的推荐」走这里）。
     * 筛出来的是 TMDB 上的真实条目和真实评分——不靠模型记忆，杜绝一本正经地编片名。
     * 任何异常回调 null，调用方自己兜底。
     */
    public static void discover(boolean movie, boolean tv, String genre, int yearMin, double minRating, Callback callback) {
        String key = "d:" + movie + tv + genre + yearMin + minRating;
        Entry entry = QUERY.get(key);
        if (entry != null && System.currentTimeMillis() - entry.at <= QUERY_TTL) {
            reply(callback, entry.list.isEmpty() ? null : entry.list);
            return;
        }
        if (!TmdbSetting.isEnabled()) {
            reply(callback, null);
            return;
        }
        POOL.execute(() -> {
            List<TmdbWork> list = null;
            try {
                list = filter(movie, tv, genre, yearMin, minRating);
            } catch (Throwable e) {
                DebugLog.d("Tmdb", "筛选失败：" + e);
            }
            if (QUERY.size() > 200) QUERY.clear();
            QUERY.put(key, new Entry(list));
            List<TmdbWork> result = list;
            App.post(() -> reply(callback, result == null || result.isEmpty() ? null : result));
        });
    }

    /** 片名 → 最像的那一部（电影、剧集都认） */
    private static List<TmdbWork> query(String title) throws Exception {
        Map<String, String> params = base();
        params.put("query", title);
        params.put("include_adult", "false");
        String body = fetch("/search/multi", params);
        if (body == null) return null;
        JSONArray array = new JSONObject(body).optJSONArray("results");
        if (array == null) return null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.optBoolean("adult", false)) continue;
            String media = item.optString("media_type");
            boolean isMovie = "movie".equals(media);
            if (!isMovie && !"tv".equals(media)) continue;
            String name = item.optString(isMovie ? "title" : "name");
            if (name.isEmpty()) name = item.optString(isMovie ? "original_title" : "original_name");
            if (name.isEmpty()) continue;
            String date = item.optString(isMovie ? "release_date" : "first_air_date");
            List<TmdbWork> one = new ArrayList<>();
            one.add(new TmdbWork(name, date.length() >= 4 ? date.substring(0, 4) : "", "", isMovie ? TmdbWork.TYPE_MOVIE : TmdbWork.TYPE_TV).with(item.optString("poster_path"), item.optDouble("vote_average", 0)));
            return one;
        }
        return null;
    }

    /** 按类型/年份下限/评分下限筛，电影和剧集可以一起要，按热度排 */
    private static List<TmdbWork> filter(boolean movie, boolean tv, String genre, int yearMin, double minRating) throws Exception {
        List<TmdbWork> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<TmdbWork>>> tasks = new ArrayList<>();
            if (movie) tasks.add(pool.submit(() -> filterOne(TmdbWork.TYPE_MOVIE, genre, yearMin, minRating)));
            if (tv) tasks.add(pool.submit(() -> filterOne(TmdbWork.TYPE_TV, genre, yearMin, minRating)));
            long deadline = System.currentTimeMillis() + TmdbSetting.TIMEOUT;
            for (Future<List<TmdbWork>> task : tasks) {
                try {
                    List<TmdbWork> got = task.get(Math.max(deadline - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
                    if (got != null) all.addAll(got);
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return all.isEmpty() ? null : all;
    }

    private static List<TmdbWork> filterOne(int type, String genre, int yearMin, double minRating) throws Exception {
        boolean isMovie = type == TmdbWork.TYPE_MOVIE;
        Map<String, String> params = base();
        params.put("include_adult", "false");
        params.put("sort_by", "popularity.desc");
        params.put("vote_count.gte", isMovie ? "50" : "20"); // 没几个人打分的条目分数没参考意义
        String genreId = genreId(genre, isMovie);
        if (genreId != null) params.put("with_genres", genreId);
        if (yearMin >= 1950) params.put(isMovie ? "primary_release_date.gte" : "first_air_date.gte", yearMin + "-01-01");
        if (minRating > 0) params.put("vote_average.gte", String.valueOf(minRating));
        String body = fetch(isMovie ? "/discover/movie" : "/discover/tv", params);
        if (body == null) return null;
        JSONArray array = new JSONObject(body).optJSONArray("results");
        if (array == null) return null;
        List<TmdbWork> list = new ArrayList<>();
        for (int i = 0; i < array.length() && list.size() < 10; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.optBoolean("adult", false)) continue;
            String name = item.optString(isMovie ? "title" : "name");
            if (name.isEmpty()) continue;
            String date = item.optString(isMovie ? "release_date" : "first_air_date");
            TmdbWork work = new TmdbWork(name, date.length() >= 4 ? date.substring(0, 4) : "", "", type).with(item.optString("poster_path"), item.optDouble("vote_average", 0));
            if (!list.contains(work)) list.add(work);
        }
        return list;
    }

    /** 中文类型名 → TMDB 的类型编号（电影和剧集编号不一样）；认不出来就不过滤类型 */
    private static String genreId(String genre, boolean movie) {
        if (genre == null || genre.isEmpty()) return null;
        for (String[] g : GENRES) {
            if (genre.contains(g[0])) {
                String id = movie ? g[1] : g[2];
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }

    private static void reply(Callback callback, List<TmdbWork> works) {
        try {
            if (callback != null) callback.onResult(works);
        } catch (Throwable ignored) {
        }
    }

    /** 两步：先按名字找到人，再拉他演过的电影和剧集 */
    private static List<TmdbWork> request(String name) throws Exception {
        String id = findPerson(name);
        if (id == null) return null;
        List<TmdbWork> movies = new ArrayList<>();
        List<TmdbWork> series = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<TmdbWork>> movieTask = pool.submit(() -> credits(id, TmdbWork.TYPE_MOVIE));
            Future<List<TmdbWork>> tvTask = pool.submit(() -> credits(id, TmdbWork.TYPE_TV));
            long deadline = System.currentTimeMillis() + TmdbSetting.TIMEOUT;
            try {
                List<TmdbWork> got = movieTask.get(Math.max(deadline - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
                if (got != null) movies.addAll(got);
            } catch (Exception ignored) {
            }
            try {
                List<TmdbWork> got = tvTask.get(Math.max(deadline - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
                if (got != null) series.addAll(got);
            } catch (Exception ignored) {
            }
        } finally {
            pool.shutdownNow();
        }
        if (movies.isEmpty() && series.isEmpty()) return null;
        List<TmdbWork> all = new ArrayList<>();
        all.addAll(movies);
        all.addAll(series);
        return all;
    }

    /** 按名字搜人，取最像的那个（优先「演员」而不是导演、编剧） */
    private static String findPerson(String name) throws Exception {
        Map<String, String> params = base();
        params.put("query", name);
        params.put("include_adult", "false");
        String body = fetch("/search/person", params);
        if (body == null) return null;
        JSONArray array = new JSONObject(body).optJSONArray("results");
        if (array == null || array.length() == 0) return null;
        String id = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            if (item.optBoolean("adult", false)) continue;
            String current = item.optString("id");
            if (current.isEmpty()) continue;
            if ("Acting".equals(item.optString("known_for_department"))) return current;
            if (id == null) id = current;
        }
        return id;
    }

    /** 演出经历（cast），导演编剧那些幕后（crew）不算，按上映/首播年份倒序 */
    private static List<TmdbWork> credits(String id, int type) throws Exception {
        String path = "/person/" + id + "/" + (type == TmdbWork.TYPE_MOVIE ? "movie_credits" : "tv_credits");
        String body = fetch(path, base());
        if (body == null) return null;
        JSONArray array = new JSONObject(body).optJSONArray("cast");
        if (array == null) return null;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.optBoolean("adult", false)) continue;
            String title = item.optString(type == TmdbWork.TYPE_MOVIE ? "title" : "name");
            if (title.isEmpty()) title = item.optString(type == TmdbWork.TYPE_MOVIE ? "original_title" : "original_name");
            if (title.isEmpty()) continue;
            String date = item.optString(type == TmdbWork.TYPE_MOVIE ? "release_date" : "first_air_date");
            rows.add(new Row(title, date, item.optString("character"), item.optString("poster_path"), item.optDouble("vote_average", 0)));
        }
        rows.sort((a, b) -> b.date.compareTo(a.date)); // 年份倒序；没日期的空串自然排最后
        List<TmdbWork> result = new ArrayList<>();
        for (Row row : rows) {
            if (result.size() >= LIMIT) break;
            TmdbWork work = new TmdbWork(row.title, row.date.length() >= 4 ? row.date.substring(0, 4) : "", row.role, type).with(row.poster, row.rating);
            if (result.contains(work)) continue;
            result.add(work);
        }
        return result;
    }

    private static Map<String, String> base() {
        Map<String, String> params = new HashMap<>();
        params.put("api_key", TmdbSetting.getKey());
        params.put("language", "zh-CN");
        return params;
    }

    /**
     * 几条路一起走（两个自建中转 + 直连），谁先带着内容回来就用谁。
     * 官方域名国内连不上是常态，直连域名又快又通是常态，Worker 冷启动偶尔十几秒也是常态——
     * 既然没有一条是稳的，那就一起发，让快的那个说了算。
     */
    private static String fetch(String path, Map<String, String> params) {
        List<String> proxies = TmdbSetting.getProxies();
        long deadline = System.currentTimeMillis() + TmdbSetting.TIMEOUT;
        ExecutorService pool = Executors.newFixedThreadPool(proxies.size() + 1);
        try {
            ExecutorCompletionService<String> service = new ExecutorCompletionService<>(pool);
            int total = 0;
            for (String proxy : proxies) {
                service.submit(() -> via(proxy, TmdbSetting.getApi() + path, params, true));
                total++;
            }
            service.submit(() -> via(TmdbSetting.getDirect(), TmdbSetting.getDirect() + path, params, false));
            total++;
            for (int i = 0; i < total; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    Future<String> future = service.poll(left, TimeUnit.MILLISECONDS);
                    if (future == null) break;
                    String body = future.get();
                    if (body != null && !body.trim().isEmpty()) return body;
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    /** viaProxy=true 时走 CORS 中转（目标地址塞进 url 参数），否则直连 */
    private static String via(String host, String target, Map<String, String> params, boolean viaProxy) throws Exception {
        StringBuilder url = new StringBuilder(host);
        if (viaProxy) url.append("/?url=").append(URLEncoder.encode(target, "UTF-8"));
        else url.append(target.substring(TmdbSetting.getDirect().length()));
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(url.indexOf("?") > 0 ? "&" : "?");
            url.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        Request request = new Request.Builder().url(url.toString()).header("User-Agent", "okhttp/4.12.0").build();
        try (Response response = http().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            return body.trim().startsWith("{") ? body : null; // 中转偶尔吐 HTML 错误页，别当 JSON 往下传
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim();
    }

    private static class Row {

        private final String title;
        private final String date;
        private final String role;
        private final String poster;
        private final double rating;

        Row(String title, String date, String role) {
            this(title, date, role, "", 0);
        }

        Row(String title, String date, String role, String poster, double rating) {
            this.title = title;
            this.date = date == null ? "" : date;
            this.role = role;
            this.poster = poster == null ? "" : poster;
            this.rating = rating;
        }
    }

    private static class Entry {

        private long at;
        private final List<TmdbWork> list;

        Entry(List<TmdbWork> list) {
            this.at = System.currentTimeMillis();
            this.list = list == null ? Collections.emptyList() : list;
        }
    }

    private static void load() {
        if (loaded) return;
        synchronized (Tmdb.class) {
            if (loaded) return;
            try {
                File file = file();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String name = it.next();
                            JSONObject item = json.optJSONObject(name);
                            if (item == null) continue;
                            Entry entry = new Entry(parseList(item.optJSONArray("list")));
                            entry.at = item.optLong("at", 0);
                            MEMO.put(name, entry);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    private static List<TmdbWork> parseList(JSONArray array) {
        List<TmdbWork> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            list.add(new TmdbWork(item.optString("n"), item.optString("y"), item.optString("r"), item.optInt("t")).with(item.optString("p"), item.optDouble("g", 0)));
        }
        return list;
    }

    private static synchronized void save() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Entry> entry : MEMO.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("at", entry.getValue().at);
                JSONArray array = new JSONArray();
                for (TmdbWork work : entry.getValue().list) {
                    JSONObject one = new JSONObject();
                    one.put("n", work.getName());
                    one.put("y", work.getYear());
                    one.put("r", work.getRole());
                    one.put("t", work.getType());
                    one.put("p", work.getPoster().isEmpty() ? "" : work.getPoster().substring(work.getPoster().lastIndexOf('/')));
                    one.put("g", work.getRating());
                    array.put(one);
                }
                item.put("list", array);
                json.put(entry.getKey(), item);
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), "tmdb_works.json");
    }
}

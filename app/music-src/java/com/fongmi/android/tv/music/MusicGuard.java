package com.fongmi.android.tv.music;

import com.fongmi.android.tv.utils.DebugLog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 假资源识别。
 *
 * 起因（马先生 2026-10-05）：验音源不能只看「有没有拿到 MP3」——平台可能把版权受限的歌
 * 换成一段广告/提示音返回，Content-Type 还是正经的 audio/mpeg、HTTP 还是 200、接口还报
 * success，肉眼和代码都看不出来。实测酷我 anti.s 就是典型：三首不同的歌返回的是同一个
 * 181,521 字节、11.28 秒的文件（路径固定为 .../588957081.mp3）。
 *
 * 两条判据：
 * ① 硬证据：同一个音频文件被两首不同的歌用到 —— 百分之百是统一替换音频，
 *    除了拉黑这条地址，还给该平台记一次，攒够两次就把这个平台临时停用（30 分钟）。
 * ② 软证据：实际时长跟歌单里的 interval 差太多（短 30% 以上，或不足 30 秒）——
 *    元数据可能有误差、版本也可能不同，所以只拉黑这一条地址，不动整个平台。
 */
public class MusicGuard {

    public static final int OK = 0;
    public static final int DUP = 1;      // 多首歌共用同一条音频
    public static final int JUNK = 2;     // 已经判过是替换音频
    public static final int SHORT = 3;    // 时长跟元数据对不上
    public static final int BLOCKED = 4;  // 这个平台刚给过替换音频

    private static final long MIN_MS = 30 * 1000L;   // 短于 30 秒基本不可能是正经歌曲
    private static final float SHORT_RATE = 0.7f;    // 短于元数据 70% 就算不对劲
    private static final int MAX_KEY = 512;
    private static final int BLOCK_LIMIT = 2;
    private static final long BLOCK_MS = 30 * 60 * 1000L;

    private static final Map<String, String> owner = new LinkedHashMap<>();  // 文件 → 歌曲 id
    private static final Set<String> junk = new HashSet<>();                 // 确认过的替换音频
    private static final Map<String, Integer> strikes = new HashMap<>();      // 平台 → 记过几次
    private static final Map<String, Long> blockUntil = new HashMap<>();      // 平台 → 解封时间

    public static class Result {

        private final int code;
        private final String actual;
        private final String expect;

        private Result(int code, String actual, String expect) {
            this.code = code;
            this.actual = actual;
            this.expect = expect;
        }

        private static Result ok() {
            return new Result(OK, null, null);
        }

        public int code() {
            return code;
        }

        public String actual() {
            return actual == null ? "" : actual;
        }

        public String expect() {
            return expect == null ? "" : expect;
        }

        public boolean bad() {
            return code != OK;
        }
    }

    /** 只看文件名：换 CDN 域名、换鉴权参数不影响判断 */
    private static String keyOf(String url) {
        if (url == null || url.isEmpty()) return "";
        int end = url.indexOf('?');
        String path = end > 0 ? url.substring(0, end) : url;
        int slash = path.lastIndexOf('/');
        String key = slash >= 0 ? path.substring(slash + 1) : path;
        return key.isEmpty() ? path : key;
    }

    /** 这个平台是不是正在停业整顿 */
    public static boolean blocked(String source) {
        if (source == null || source.isEmpty()) return false;
        Long until = blockUntil.get(source);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            blockUntil.remove(source);
            return false;
        }
        return true;
    }

    /** 起播前先过一遍：历史和当前所在的平台 */
    public static Result check(Music music, String url) {
        String key = keyOf(url);
        if (key.isEmpty()) return Result.ok();
        String id = music == null ? "" : music.getId();
        synchronized (MusicGuard.class) {
            if (junk.contains(key)) return new Result(JUNK, null, null);
            String who = owner.get(key);
            if (who != null && !who.equals(id)) {
                junk.add(key);
                strike(music, key, who);
                return new Result(DUP, null, null);
            }
            if (blocked(music == null ? "" : music.getSource())) return new Result(BLOCKED, null, null);
            if (owner.size() > MAX_KEY) owner.clear();
            owner.put(key, id);
        }
        return Result.ok();
    }

    /** 起播后拿到真实时长再过一遍 */
    public static Result length(Music music, long durationMs) {
        if (music == null || durationMs <= 0) return Result.ok();
        long expect = music.getIntervalMs();
        if (expect <= 0) return Result.ok();
        String act = clock(durationMs);
        String exp = clock(expect);
        if (durationMs < MIN_MS && expect > MIN_MS) return new Result(SHORT, act, exp);
        if (durationMs < expect * SHORT_RATE) return new Result(SHORT, act, exp);
        return Result.ok();
    }

    /** 确认是替换音频：拉黑这条地址（硬证据时顺带记平台一笔） */
    public static void ban(Music music, String url, boolean hard) {
        String key = keyOf(url);
        if (key.isEmpty()) return;
        synchronized (MusicGuard.class) {
            junk.add(key);
            owner.remove(key);
            if (hard) strike(music, key, null);
        }
        DebugLog.d("MusicGuard", "拉黑音频 " + key + (hard ? "（硬证据）" : ""));
    }

    private static void strike(Music music, String key, String other) {
        String source = music == null ? "" : music.getSource();
        if (source.isEmpty()) return;
        int count = (strikes.get(source) == null ? 0 : strikes.get(source)) + 1;
        strikes.put(source, count);
        DebugLog.d("MusicGuard", key + " 是替换音频，" + source + " 第 " + count + " 次"
                + (other == null ? "" : "（与 " + other + " 撞车）"));
        if (count >= BLOCK_LIMIT) {
            blockUntil.put(source, System.currentTimeMillis() + BLOCK_MS);
            DebugLog.d("MusicGuard", source + " 连续给替换音频，停用 30 分钟");
        }
    }

    private static String clock(long ms) {
        long total = ms / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }
}

package com.fongmi.android.tv.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 从播放地址里抠出**英文片名**，给在线搜字幕兜底用。
 *
 * 为什么要它：OpenSubtitles 那家的库里只有英文/原文件名（实测用「盗梦空间」「沙丘」这类中文名搜，
 * 返回全是 0 条），而 App 里显示的片名基本都是中文，直接拿去搜等于搜不到。
 * 好在播放地址几乎都是英文压制名（Dune.Part.Two.2024.2160p.WEB-DL...），
 * 从里面把片名那几个词抠出来再搜一次，外语片就搜得到了。
 *
 * 只做「从右往左找一段像片名的路径、遇到画质/音轨/年份/集数就停」这一件事，
 * 抠不出来就返回空，调用方照常走空结果提示，不会引入额外分支。
 */
public final class SubtitleName {

    /** 压制组/画质/音轨/容器标记，出现这些就说明片名已经结束了 */
    private static final String[] NOISE = {
            "1080p", "720p", "2160p", "480p", "360p", "2160", "1080", "720", "4k", "8k",
            "hdr", "hdr10", "hdr10plus", "dv", "dolby", "vision", "imax", "sdr",
            "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "mpeg", "av1",
            "web", "webdl", "webrip", "webmux", "bluray", "brrip", "bdrip", "bd", "remux",
            "hdtv", "hddvd", "dvd", "dvdrip", "dvdscr", "cam", "ts", "tc", "r5", "screener",
            "ddp5", "ddp", "dd5", "dd2", "ac3", "eac3", "aac", "mp3", "mp4", "mkv", "m3u8",
            "dts", "dtshd", "truehd", "atmos", "lpcm", "pcm", "flac", "opus", "51", "71", "20",
            "10bit", "8bit", "12bit", "hi10p", "amzn", "nf", "hmax", "dsnp", "hulu", "atvp", "stan",
            "proper", "repack", "rerip", "internal", "limited", "complete", "extended", "uncut",
            "remastered", "criterion", "directors", "cut", "open", "matte", "readnfo",
            "multi", "subbed", "dubbed", "chs", "cht", "eng", "jpn", "kor", "fre", "ger", "spa",
            "index", "play", "playlist", "stream", "video", "movie", "hls", "live", "default",
            "download", "file", "vod", "cdn", "api", "m3u", "dash", "master", "chunk", "seg",
            // 本机代理/服务那层路径：抠出来就是「proxy」，谁搜谁骂街
            "proxy", "cmd", "server", "local", "localhost",
            "https", "http", "www", "com", "net",
    };

    private static final int MAX_LENGTH = 48;
    private static final int MAX_WORD = 20;

    /** 代理壳里装真实地址用的参数名，各家叫法不一样，都试一遍 */
    private static final String[] INNER_KEYS = {"url", "u", "link", "src", "play", "video", "path", "file"};

    /** 本机服务那层的路径名：proxy / file / cmd 后面挂的才是真实地址 */
    private static final String[] SHELLS = {"proxy", "file", "cmd"};

    /** 视频扩展名：带这些的才算文件名，光秃秃一个词只是目录名 */
    private static final String[] MEDIA_EXT = {"mp4", "mkv", "m3u8", "ts", "avi", "mov", "flv", "webm", "rmvb", "rm", "wmv", "mpg", "mpeg", "m2ts", "iso", "strm", "m4v", "asf", "vob", "divx"};

    private SubtitleName() {
    }

    /**
     * 播放地址 → 英文片名；抠不出来返回空串。
     *
     * ⚠️ 播放地址经常不是真实地址，而是**本机代理**那一层：
     * {@code http://127.0.0.1:9978/proxy?url=https://xxx/Dune.Part.Two.2024.2160p...}
     * 直接按路径抠，抠出来的就是路径里那个「proxy」——搜索框里冒出 proxy 几个字母、搜回来的也全是
     * 带 proxy 的垃圾，就是这么来的（马先生 2026-10-15 亲历）。所以先看是不是代理壳，是就扒出真实地址再抠。
     */
    public static String fromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        String target = url.trim();
        for (int i = 0; i < 3; i++) { // 理论上只套一层，容错多剥两层，别把自己绕进去
            String inner = unwrapProxy(target);
            if (inner.isEmpty()) break;
            target = inner;
        }
        return parseUrl(target);
    }

    private static String parseUrl(String url) {
        String[] segs = segments(url);
        for (int i = segs.length - 1; i >= 0 && i >= segs.length - 3; i--) { // 最多往前看三级，别把根目录也算上
            // 光秃秃一个词（share / proxy 这种分类名、服务端路径）不认，
            // 得带扩展名（Dune.2024.mkv）或带分隔符（Dune.Part.Two）才像片名。
            if (!looksName(segs[i])) continue;
            String name = parse(segs[i]);
            if (!name.isEmpty()) return name;
        }
        // 路径里抠不出来（比如整个地址就一串带参数的查询），再去参数里碰碰运气
        int q = url.indexOf('?');
        if (q > 0) for (String value : url.substring(q + 1).split("&")) {
            int eq = value.indexOf('=');
            if (eq <= 0) continue;
            String name = parse(decode(value.substring(eq + 1)));
            if (!name.isEmpty()) return name;
        }
        return "";
    }

    /** 去掉「协议://域名:端口」之后剩下的路径分级，免得把 host.com 当成片名 */
    private static String[] segments(String url) {
        String path = url.split("[?#]", 2)[0];
        int scheme = path.indexOf("://");
        String rest = scheme >= 0 ? path.substring(scheme + 3) : path;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(slash + 1).split("/") : new String[0];
    }

    /** 本机代理壳（ .../proxy?url=真实地址 、 /file/真实地址 ）→ 真实地址；不是代理壳就返回空 */
    private static String unwrapProxy(String url) {
        int q = url.indexOf('?');
        String head = q > 0 ? url.substring(0, q) : url;
        for (String shell : SHELLS) { // 路径式：/file/https%3A%2F%2F...
            String needle = "/" + shell + "/";
            int i = head.indexOf(needle);
            if (i < 0) continue;
            String inner = decode(head.substring(i + needle.length()));
            if (isAddress(inner)) return inner;
        }
        String[] segs = head.split("/");
        if (segs.length == 0 || q <= 0) return "";
        if (!isShell(segs[segs.length - 1])) return "";
        String query = url.substring(q + 1); // 参数式：/proxy?url=...
        for (String key : INNER_KEYS) {
            String value = decode(param(query, key));
            if (isAddress(value)) return value;
        }
        return "";
    }

    private static boolean isShell(String seg) {
        String lower = seg.toLowerCase(Locale.ROOT);
        for (String shell : SHELLS) if (shell.equals(lower)) return true;
        return false;
    }

    private static boolean isAddress(String text) {
        return text.startsWith("http") || text.contains("/");
    }

    /** 这一段像不像个片名：带视频扩展名，或者带分隔符（Dune.Part.Two） */
    private static boolean looksName(String seg) {
        return hasMediaExt(seg) || hasSeparator(seg);
    }

    /** 从查询串里取指定参数；没这个参数返回空 */
    private static String param(String query, String key) {
        String needle = key + "=";
        int from = 0;
        while (from < query.length()) {
            int i = query.indexOf(needle, from);
            if (i < 0) return "";
            if (i == 0 || query.charAt(i - 1) == '&') {
                int end = query.indexOf('&', i);
                return end < 0 ? query.substring(i + needle.length()) : query.substring(i + needle.length(), end);
            }
            from = i + needle.length();
        }
        return "";
    }

    private static String decode(String text) {
        if (text.isEmpty() || text.indexOf('%') < 0) return text;
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return text;
        }
    }

    private static String parse(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        String base = stripExt(seg);
        if (isHash(base)) return "";
        StringBuilder result = new StringBuilder();
        for (String word : base.split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) break;
            String lower = word.toLowerCase(Locale.ROOT);
            if (isYear(lower) || isEpisode(lower) || isNoise(lower)) break;
            if (word.length() > MAX_WORD) break; // 超长词只可能是网盘的随机名，不是片名
            if (!isWord(word)) break;
            if (result.length() > 0) result.append(' ');
            result.append(word);
            if (result.length() >= MAX_LENGTH) break;
        }
        String name = result.toString().trim();
        return name.length() >= 3 && countLetter(name) >= 3 ? name : "";
    }

    private static boolean hasSeparator(String seg) {
        return seg.indexOf('.') > 0 || seg.indexOf('_') > 0 || seg.indexOf('-') > 0;
    }

    private static boolean hasMediaExt(String seg) {
        int dot = seg.lastIndexOf('.');
        if (dot <= 0) return false;
        String ext = seg.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String known : MEDIA_EXT) if (known.equals(ext)) return true;
        return false;
    }

    private static String stripExt(String seg) {
        int dot = seg.lastIndexOf('.');
        if (dot > 0 && seg.length() - dot <= 5) seg = seg.substring(0, dot);
        return seg;
    }

    /** 32 位 md5 / 长串十六进制：不是片名，是网盘或转码服务的文件名 */
    private static boolean isHash(String text) {
        if (text.length() < 16) return false;
        return text.matches("[0-9a-fA-F]{16,}");
    }

    private static boolean isYear(String word) {
        if (word.length() != 4 || !word.matches("\\d{4}")) return false;
        int year = Integer.parseInt(word);
        return year >= 1900 && year <= 2099;
    }

    /** 剧集标记：S01E01 / E01 / S01 / 1x01 —— 后面就不再是片名了 */
    private static boolean isEpisode(String word) {
        return word.matches("s\\d{1,2}e\\d{1,3}") || word.matches("e\\d{1,3}") || word.matches("s\\d{1,2}") || word.matches("\\d{1,2}x\\d{1,3}");
    }

    private static boolean isNoise(String word) {
        for (String noise : NOISE) if (noise.equals(word)) return true;
        return false;
    }

    /** 片名词：字母开头、字母数字组成 */
    private static boolean isWord(String word) {
        char first = word.charAt(0);
        if (!Character.isLetter(first)) return false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!Character.isLetterOrDigit(c)) return false;
        }
        return true;
    }

    private static int countLetter(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) count++;
        return count;
    }
}

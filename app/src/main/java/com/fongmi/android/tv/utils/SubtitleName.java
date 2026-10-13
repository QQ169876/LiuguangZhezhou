package com.fongmi.android.tv.utils;

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
    };

    private static final int MAX_LENGTH = 48;
    private static final int MAX_WORD = 20;

    private SubtitleName() {
    }

    /** 播放地址 → 英文片名；抠不出来返回空串 */
    public static String fromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        String path = url.split("[?#]", 2)[0];
        String[] segs = path.split("/");
        for (int i = segs.length - 1; i >= 0 && i >= segs.length - 3; i--) { // 最多往前看三级，别把域名/根目录也算上
            // 倒数第一级直接认（文件名本身就带片名）；再往上一级的目录名可能是 share / movie 这种分类名，
            // 要求它至少带分隔符（Dune.Part.Two 这种），否则跳过，免得把「share」当成片名去搜。
            if (i < segs.length - 1 && !hasSeparator(segs[i])) continue;
            String name = parse(segs[i]);
            if (!name.isEmpty()) return name;
        }
        return "";
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

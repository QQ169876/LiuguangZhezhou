package com.fongmi.android.tv.music;

/**
 * 取播放地址和歌词的总入口。
 *
 * 顺序（马先生 2026-10-05 定）：以 music_jx 那套 PHP 项目的算法为主——
 * ① 自建解析服务（设置里填了 music_jx 地址才走）
 * ② 内置直连（wy eapi / kw anti.s，同一套算法的 Java 自持版，不依赖任何第三方）
 * ③ 音源脚本（野花，覆盖 tx/kg/mg 等内置直连没有的源）
 * 哪一环先出结果就用哪个，界面上看不出来走了哪条路。
 */
public class MusicSource {

    public static String url(Music music) {
        if (music == null) return "";
        String quality = MusicSetting.getQuality();
        String jx = MusicDirect.jxUrl(music, quality);
        if (jx.startsWith("http")) return jx;
        String direct = MusicDirect.url(music, quality);
        if (direct.startsWith("http")) return direct;
        MusicApi api = MusicApi.get();
        if (api.isReady() && api.getSources().contains(music.getSource())) {
            return api.url(music.getSource(), music.toInfo(quality), quality);
        }
        return "";
    }

    public static String lyric(Music music) {
        if (music == null) return "";
        if (!MusicSetting.isLyric()) return "";
        String jx = MusicDirect.jxLyric(music);
        if (!jx.isEmpty()) return jx;
        String direct = MusicDirect.lyric(music);
        if (!direct.isEmpty()) return direct;
        MusicApi api = MusicApi.get();
        if (api.isReady() && api.getSources().contains(music.getSource())) {
            return api.lyric(music.getSource(), music.toInfo(MusicSetting.getQuality()));
        }
        return "";
    }
}

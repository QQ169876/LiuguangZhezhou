package com.fongmi.android.tv.music;

/**
 * 取播放地址和歌词的总入口。
 *
 * 顺序：音源脚本（社区的 lx 脚本，能覆盖五源）→ 自建解析服务 → 内置直连（wy / kw）。
 * 哪一环先出结果就用哪个，界面上看不出来走了哪条路。
 */
public class MusicSource {

    public static String url(Music music) {
        if (music == null) return "";
        String quality = MusicSetting.getQuality();
        MusicApi api = MusicApi.get();
        if (api.isReady() && api.getSources().contains(music.getSource())) {
            String url = api.url(music.getSource(), music.toInfo(quality), quality);
            if (url.startsWith("http")) return url;
        }
        String jx = MusicDirect.jxUrl(music, quality);
        if (jx.startsWith("http")) return jx;
        return MusicDirect.url(music, quality);
    }

    public static String lyric(Music music) {
        if (music == null) return "";
        if (!MusicSetting.isLyric()) return "";
        MusicApi api = MusicApi.get();
        if (api.isReady() && api.getSources().contains(music.getSource())) {
            String text = api.lyric(music.getSource(), music.toInfo(MusicSetting.getQuality()));
            if (!text.isEmpty()) return text;
        }
        String jx = MusicDirect.jxLyric(music);
        if (!jx.isEmpty()) return jx;
        return MusicDirect.lyric(music);
    }
}

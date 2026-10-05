package com.fongmi.android.tv.music;

import com.fongmi.android.tv.utils.DebugLog;

/**
 * 取播放地址和歌词的总入口。
 *
 * 取地址的路线（马先生 2026-10-05 中午改的）：
 *
 * ① 自建解析服务（设置里填了 music_jx 地址才走）
 * ② 网易 eapi —— music_jx 那套 PHP 项目算法的 Java 自持版，不依赖任何第三方服务器
 * ③ 音源脚本（野花，覆盖 tx/kg/mg，也能兜 wy/kw）
 * ④ 内置直连兜底 —— **酷我 anti.s 官方已经限制非客户端访问**（会回「下载酷我音乐客户端」的提示页），
 *    马先生实机上确认播不成，所以从主路撤下来，只在前面三条都没出来时试最后一次。
 *
 * 走了哪条路会记在 {@link #getRoute()} 里，播放页歌词下面那行状态就是从这来的，
 * 取不到地址时一眼能看出是脚本那头不通、还是这首歌本身没有资源。
 */
public class MusicSource {

    public static final int NONE = 0;
    public static final int JX = 1;
    public static final int DIRECT = 2;
    public static final int SCRIPT = 3;

    private static volatile int route = NONE;
    private static volatile String note = "";

    /** 脚本那条路连着失败的次数；到阈值就先把它挂起一阵，别每首歌都白等一次超时 */
    private static final int FAIL_LIMIT = 3;
    private static final long PAUSE_MS = 10 * 60 * 1000L;
    private static volatile int scriptFail;
    private static volatile long scriptPauseUntil;

    public static int getRoute() {
        return route;
    }

    /** 脚本音源现在是不是"歇着"（刚连续失败过，先别走它） */
    public static boolean scriptPaused() {
        return System.currentTimeMillis() < scriptPauseUntil;
    }

    private static void scriptOk() {
        scriptFail = 0;
        scriptPauseUntil = 0;
    }

    private static void scriptBad() {
        if (++scriptFail >= FAIL_LIMIT) {
            DebugLog.d("MusicSource", "脚本音源连着失败 " + scriptFail + " 次，先挂起 10 分钟，优先走内置接口");
            scriptPauseUntil = System.currentTimeMillis() + PAUSE_MS;
            scriptFail = 0;
        }
    }

    /** 备注：成功时是那条路的说明，失败时是原因（可能为空） */
    public static String getNote() {
        return note;
    }

    public static String url(Music music) {
        route = NONE;
        note = "";
        if (music == null) return "";
        String quality = MusicSetting.getQuality();

        String jx = MusicDirect.jxUrl(music, quality);
        if (jx.startsWith("http")) {
            route = JX;
            note = music.getSource();
            return jx;
        }

        // 网易走自持算法，不依赖作者那台服务器
        if (MusicDirect.isWy(music)) {
            String direct = MusicDirect.url(music, quality);
            if (direct.startsWith("http")) {
                route = DIRECT;
                note = music.getSource();
                return direct;
            }
        }

        MusicApi api = MusicApi.get();
        boolean ready = api.isReady() && api.getSources().contains(music.getSource());
        if (ready && !scriptPaused()) {
            String script = api.url(music.getSource(), music.toInfo(quality), quality);
            if (script.startsWith("http")) {
                scriptOk();
                route = SCRIPT;
                note = music.getSource();
                return script;
            }
            scriptBad();
        }

        // 前面的路都断了，再试一次内置直连（主要是给酷我留最后一次机会）。
        // 这个平台要是刚给过替换音频（MusicGuard 认出来的），这次就别再试它了。
        if (!MusicDirect.isWy(music) && !MusicGuard.blocked(music.getSource())) {
            String last = MusicDirect.url(music, quality);
            if (last.startsWith("http")) {
                route = DIRECT;
                note = music.getSource();
                return last;
            }
        }

        note = api.isReady() ? "" : api.getLastError();
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

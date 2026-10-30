package com.fongmi.android.tv.cast;

import java.util.Collections;
import java.util.Map;

/**
 * 播放页要「被问」的东西。
 *
 * 广播端不主动去动播放器，只每隔半秒照着这个接口问一遍：
 * 现在放到第几毫秒、是播还是停、倍速多少、URL 和请求头是什么。
 * 这样广播端这一侧永远是**只读**的，不会出现「为了广播把本机播放改乱了」这种事。
 *
 * 实现端（两端的 VideoActivity）只要把现成的播放器状态递出来就行，
 * 拿不到某项就返回默认值，宁可信息少一点也不让广播挂掉。
 */
public interface CastSource {

    /** 剧名，列表上显示用 */
    String getCastTitle();

    /** 当前集数 / 第几部，列表上显示用 */
    String getCastEpisode();

    /** 取流地址。拿不到（比如在缓冲、解析中）就返回空串，这一轮不更新地址 */
    String getCastUrl();

    /** 取流要带的头（Referer / UA 之类），没有就返回空表 */
    Map<String, String> getCastHeaders();

    /** 外挂字幕：语言 -> 地址。目前阶段还没接通，返回空表即可 */
    Map<String, String> getCastSubs();

    /** 当前播放位置（毫秒） */
    long getCastPosition();

    /** 倍速，1 = 正常 */
    float getCastSpeed();

    /** 是不是正在播 */
    boolean isCastPlaying();

    /** 播放器还在不在（退了播放页就 false） */
    boolean isCastAlive();

    /** 兜底实现：谁也不实现接口时不会崩 */
    CastSource EMPTY = new CastSource() {

        @Override
        public String getCastTitle() {
            return "";
        }

        @Override
        public String getCastEpisode() {
            return "";
        }

        @Override
        public String getCastUrl() {
            return "";
        }

        @Override
        public Map<String, String> getCastHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, String> getCastSubs() {
            return Collections.emptyMap();
        }

        @Override
        public long getCastPosition() {
            return 0L;
        }

        @Override
        public float getCastSpeed() {
            return 1f;
        }

        @Override
        public boolean isCastPlaying() {
            return false;
        }

        @Override
        public boolean isCastAlive() {
            return false;
        }
    };
}

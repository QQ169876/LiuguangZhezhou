package com.fongmi.android.tv.player.exo;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DebugLog;

import java.util.Locale;

/**
 * 播放诊断（马先生 2026-11-20）：专查「短剧画面像慢动作、声音却正常、整集持续」。
 *
 * 只在调试模式开启时工作，**不改任何播放行为**，只把媒体层数据打进流水账：
 *   视频轨标称帧率 / 分辨率 / 编码、音频轨采样率 / 声道、首帧耗时、丢帧、音频欠载，
 *   以及最关键的「播放位置推进 ÷ 墙钟推进」比 —— 这一项能一锤定音地区分
 *     · 比值 ≈ 1、画面却慢：音频主时钟照常，是视频帧时间戳/帧率被拉长（片源封装问题）；
 *     · 比值明显 < 1：播放时钟本身被拖慢（倍速/时钟/渲染阻塞）。
 *
 * 跟 ExoPlayerEngine 同包，随它构造、起播、释放；调试模式一关（或跨天自动关）立即停，
 * 不常驻、不空转。诊断版只发到出问题的那条通道，其他端线上不受影响。
 */
final class ExoDiag {

    private static final String TAG = "Diag";
    private static final long BEAT = 2000L;

    private final ExoPlayer player;
    private final Runnable tick = this::beat;
    private long wallMark;
    private long posMark;
    private long startWall;
    private long frameOffsetMarkUs;
    private long frameCountMark;
    private boolean running;
    private boolean firstFrameLogged;

    ExoDiag(@NonNull ExoPlayer player) {
        this.player = player;
    }

    /** 每次起播/换源调用：重置基准，打一条起播信息，并开始按节拍记录时钟 */
    void start(String info) {
        if (!DebugLog.isEnabled()) return;
        startWall = wallMark = SystemClock.elapsedRealtime();
        try {
            posMark = player.getCurrentPosition();
        } catch (Throwable ignored) {
            posMark = 0;
        }
        firstFrameLogged = false;
        frameOffsetMarkUs = 0;
        frameCountMark = 0;
        running = true;
        DebugLog.d(TAG, "起播 " + info);
        App.post(tick, BEAT);
    }

    /** 选中轨道：帧率、分辨率、编码、采样率——短剧「慢动作」最可能藏在这里 */
    void tracks(@NonNull Tracks tracks) {
        if (!DebugLog.isEnabled()) return;
        for (int g = 0; g < tracks.getGroups().size(); g++) {
            Tracks.Group group = tracks.getGroups().get(g);
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format f = group.getTrackFormat(i);
                String mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
                if (MimeTypes.isVideo(mime)) {
                    DebugLog.d(TAG, String.format(Locale.US,
                            "视频轨 mime=%s codecs=%s 帧率=%.3f 分辨率=%dx%d 码率=%d 旋转=%d 像素比=%.3f",
                            mime, f.codecs, f.frameRate, f.width, f.height, f.bitrate,
                            f.rotationDegrees, f.pixelWidthHeightRatio));
                } else if (MimeTypes.isAudio(mime)) {
                    DebugLog.d(TAG, String.format(Locale.US,
                            "音频轨 mime=%s codecs=%s 采样率=%d 声道=%d 码率=%d",
                            mime, f.codecs, f.sampleRate, f.channelCount, f.bitrate));
                }
            }
        }
    }

    void firstFrame() {
        if (!DebugLog.isEnabled() || firstFrameLogged) return;
        firstFrameLogged = true;
        long pos = 0;
        try {
            pos = player.getCurrentPosition();
        } catch (Throwable ignored) {
        }
        DebugLog.d(TAG, "首帧渲染 起播耗时=" + (SystemClock.elapsedRealtime() - startWall)
                + "ms 位置=" + pos);
    }

    void dropped(int frames, long elapsedMs) {
        if (!DebugLog.isEnabled()) return;
        DebugLog.d(TAG, "丢帧 " + frames + " 帧 / " + elapsedMs + "ms");
    }

    void audioUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
        if (!DebugLog.isEnabled()) return;
        DebugLog.d(TAG, "音频欠载 缓冲=" + bufferSizeMs + "ms 距上次写入=" + elapsedSinceLastFeedMs + "ms");
    }

    /** 视频解码器实际用的哪个：OMX./c2. 是硬解，ffmpeg 是软解——名字比设置项可信 */
    void videoDecoder(String name) {
        if (!DebugLog.isEnabled()) return;
        DebugLog.d(TAG, "视频解码器 " + decoderKind(name) + " name=" + name);
    }

    /** 音频解码器：音频欠载/不同步时对照用 */
    void audioDecoder(String name) {
        if (!DebugLog.isEnabled()) return;
        DebugLog.d(TAG, "音频解码器 " + decoderKind(name) + " name=" + name);
    }

    private static String decoderKind(String name) {
        if (name == null) return "未知";
        String n = name.toLowerCase();
        if (n.contains("ffmpeg")) return "软解";
        if (n.contains("omx") || n.contains("c2.") || n.contains("codec2")) return "硬解";
        return name;
    }

    /**
     * media3 每批回调会给出「本批累计处理偏移(us) + 帧数」，回调后归零。
     * 处理偏移 = 实际渲染时刻 - 目标时刻：持续为负说明画面渲染跟不上音频时钟，
     * 这正是「声音正常、画面像慢动作」的直接证据。这里只累加，心跳里汇总，别逐帧打日志。
     */
    void frameOffset(long processingOffsetUs, int frameCount) {
        if (!DebugLog.isEnabled()) return;
        try {
            frameOffsetMarkUs += processingOffsetUs;
            frameCountMark += frameCount;
        } catch (Throwable ignored) {
        }
    }

    /** 播放状态切换打点：卡顿时看是停在 BUFFERING 还是 READY 一直不走 */
    void playbackState(int state) {
        if (!DebugLog.isEnabled()) return;
        String at = startWall > 0 ? "@起播后" + (SystemClock.elapsedRealtime() - startWall) + "ms " : "";
        DebugLog.d(TAG, "状态切换 " + stateName(state) + " " + at + "位置=" + safePosition());
    }

    private String safePosition() {
        try {
            return String.valueOf(player.getCurrentPosition());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> "STATE_" + state;
        };
    }

    private void beat() {
        if (!running) return;
        if (!DebugLog.isEnabled()) { // 跨天自动关了：停掉，别空转
            stop();
            return;
        }
        // 诊断代码绝不能反过来影响播放：player 可能刚好被释放/未就绪，
        // 这里取数整体兜住，出了任何意外就停掉自己，绝不让日志把播放线程带崩。
        try {
            long wall = SystemClock.elapsedRealtime();
            long pos = player.getCurrentPosition();
            long dWall = wall - wallMark;
            long dPos = pos - posMark;
            double ratio = dWall > 0 ? (double) dPos / dWall : 0;
            DebugLog.d(TAG, String.format(Locale.US,
                    "时钟 墙钟+%dms 位置+%dms 推进比=%.2f 位置=%d/%d state=%d buffered=%d 倍速=%.2f",
                    dWall, dPos, ratio, pos, player.getDuration(), player.getPlaybackState(),
                    player.getBufferedPosition(), player.getPlaybackParameters().speed));
            // 真实渲染帧率与帧处理偏移：标称帧率看着正常，但实际渲染慢/偏移持续为负，
            // 就是画面被拖成慢动作的直接证据。只在攒够一段帧后汇总一条。
            if (frameCountMark > 0 && dWall > 0) {
                double renderFps = (double) frameCountMark * 1000 / dWall;
                double avgOffsetMs = (double) frameOffsetMarkUs / frameCountMark / 1000;
                DebugLog.d(TAG, String.format(Locale.US,
                        "帧渲染 实际帧率=%.2ffps 帧数=%d 平均处理偏移=%.2fms/帧",
                        renderFps, frameCountMark, avgOffsetMs));
            }
            wallMark = wall;
            posMark = pos;
            frameOffsetMarkUs = 0;
            frameCountMark = 0;
        } catch (Throwable ignored) {
            stop();
            return;
        }
        App.post(tick, BEAT);
    }

    void stop() {
        running = false;
        App.removeCallbacks(tick);
    }
}

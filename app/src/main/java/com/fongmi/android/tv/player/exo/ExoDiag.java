package com.fongmi.android.tv.player.exo;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
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
            wallMark = wall;
            posMark = pos;
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

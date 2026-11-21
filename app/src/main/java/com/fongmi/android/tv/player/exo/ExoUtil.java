package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.sync.SubtitleAutoSync;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SpeedSetting;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(Player.Listener listener, DefaultPreloadManager.Builder preloadManagerBuilder) {
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(App.get()).setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = preloadManagerBuilder.buildExoPlayer(playerBuilder);
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.APPLICATION_OCTET_STREAM;
        return null;
    }

    static LoadControl buildLoadControl(int maxPreloadBufferBytes) {
        int buffer = PlayerSetting.effectiveBuffer(); // 自动模式会带上本视频卡顿攒出来的临时增益
        return new DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, maxPreloadBufferBytes).build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    static DecodeTrackSelector buildTrackSelector(int decode) {
        DecodeTrackSelector trackSelector = new DecodeTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (DecodeSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled());
        // 最高码率那一路无条件顶住：竖屏短剧元数据不规范，让弱机按能力自选容易挑到标称帧率与实际对不上的轨。
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        setDecodePreferences(trackSelector, decode);
        return trackSelector;
    }

    static void setDecodePreferences(DecodeTrackSelector trackSelector, int decode) {
        // 软硬解只认播放页的手动选择，不再看设置里的「软解偏好」，也不自动来回切
        trackSelector.setRendererDecodePreferences(decode, decode);
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(PlayerEngine.HARD, null);
    }

    /**
     * 软硬解只认播放页的手动选择：
     * 硬解 = 完全不创建 ffmpeg 扩展 renderer，并关掉 decoder fallback。MediaCodec 起不来就直接报错，
     *       绝不偷偷退到软解（HEVC Level 虚标的短剧就是栽在这条后门上，日志里全是 ffmpeg-hevc）。
     * 软解 = ffmpeg 扩展 renderer 优先，同样关掉 fallback，不自动往硬解上切。
     * RenderersFactory 只在 player 创建时绑定，所以手动切换解码方式时必须重建 engine。
     */
    static RenderersFactory buildRenderersFactory(int decode, AudioProcessor audioProcessor) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessor);
            }
        };
        return factory.setEnableDecoderFallback(false)
                .setDolbyVisionOutputPolicy(DecodeSetting.getDolbyVisionOutputPolicy())
                .setExtensionRendererMode(decode == PlayerEngine.SOFT
                        ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
    }

    /**
     * 变速（尤其**慢速**）能不能真的生效，全看音频走哪条路：
     * <p>
     * media3 里 {@code DefaultAudioSink} 是二选一：
     *   1. **软件变速（Sonic）** —— 条件是 `!tunneling && 输入是PCM && 不走高分辨率浮点输出`。
     *      Sonic 是纯算力的时间拉伸，0.1x~8x 全都按你说的来，慢速快不了也慢得下去；
     *   2. **硬件变速（AudioTrack.setPlaybackParams）** —— 剩下情况走这条。
     *      这玩意儿在大量安卓设备/盒子上**只认加速不认减速**：speed > 1 好使，
     *      speed < 1 要么被平台直接忽略（听感纹丝不动），要么把速度弹回 1.0。
     *      所以「加快有效、放慢无效」就是它干的。
     * <p>
     * 原来这里照单全收了 {@code enableFloatOutput}：一旦音轨走高分辨率浮点输出，
     * 条件 1 的第三条就不成立 → Sonic 被跳过 → 慢速交给 AudioTrack → 慢速失效。
     * 所以这里**主动关掉浮点输出**，把变速稳稳按在 Sonic 这条路上；
     * 只有 tunneling（隧道模式，4K/HDR 常用）下 Sonic 本来就上不了场，
     * 才放行硬件变速当唯一退路。
     */
    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, AudioProcessor audioProcessor) {
        boolean tunneling = DecodeSetting.isTunnelingEnabled();
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams || tunneling);
        if (!DecodeSetting.isAudioPassThrough()) builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        if (audioProcessor != null) builder.setAudioProcessors(new AudioProcessor[]{audioProcessor, new TeeAudioProcessor(SubtitleAutoSync.get())});
        return builder.build();
    }
}

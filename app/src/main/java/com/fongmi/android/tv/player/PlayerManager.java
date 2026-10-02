package com.fongmi.android.tv.player;
import java.util.Collections;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.effect.PlayerEffectManager;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.DecodeGuard;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PlayWatchdog;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final PlayerEffectManager effects;
    private final Runnable runnable;
    private final Callback callback;
    private PendingPreload pendingPreload;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;

    private DanmakuConfig danmakuConfig;
    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private int retry;
    private int decode;
    private long lastStop; // 上次停播时间：换源/切线后的下一次起播要和它拉开距离

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.decode = DecodeGuard.softForced() ? PlayerEngine.SOFT : PlayerEngine.HARD; // 这台机器硬解撞翻过就默认软解，别再反复撞
        this.runnable = this::onPlayTimeout;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.engine = PlayerEngineFactory.create(decode, listener);
        this.effects = new PlayerEffectManager(() -> engine);
        this.danmakuConfig = DanmakuSetting.getConfig();
        this.danmakuEnabled = DanmakuSetting.isShow();
        this.player = engine.getPlayer();
    }

    public void release() {
        App.removeCallbacks(runnable);
        PlayWatchdog.stop();
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    private void setPlayer(Player player) {
        this.player = player;
        callback.onPlayerRebuild(player);
    }

    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    public int getAudioChannelCount() {
        return engine == null ? Format.NO_VALUE : engine.getAudioChannelCount();
    }

    public List<MediaChapter> getCurrentMediaChapters() {
        return player.getCurrentMediaChapters();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : Collections.emptyList();
    }

    private void notifyDanmakuSourceChanged() {
        callback.onDanmakuSourceChanged(getSelectedDanmakuUri());
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public String getMediaTitle() {
        MediaMetadata metadata = getMetadata();
        if (metadata == null) return "";
        CharSequence title = !TextUtils.isEmpty(metadata.displayTitle) ? metadata.displayTitle : metadata.title;
        if (TextUtils.isEmpty(title)) title = metadata.artist;
        return TextUtils.isEmpty(title) ? "" : title.toString();
    }

    public void setMetadata(@NonNull MediaMetadata metadata) {
        if (spec == null || metadata.equals(spec.getMetadata())) return;
        spec.setMetadata(metadata);
        if (TextUtils.isEmpty(spec.getUrl())) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(metadata).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean hasPlaySpec() {
        return spec != null;
    }

    public boolean canPreloadNext() {
        return PreloadSetting.isEnabled() && PreloadSetting.isNextEpisodeEnabled() && engine != null && engine.getType() == PlayerEngine.Type.EXO;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return player.getCurrentMediaItem() != null && player.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        return player.getCurrentMediaItem() != null && !player.isCurrentMediaItemLive();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return !getCurrentMediaEditions().isEmpty();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public boolean haveDanmaku() {
        return spec != null && spec.getSelectedDanmaku() != null;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public boolean canSetAudioSetting() {
        return effects.canSetAudioSetting();
    }

    public AudioEffectBands getAudioSettingBands() {
        return effects.getAudioSettingBands();
    }

    public int getAudioSettingError() {
        return effects.getAudioSettingError();
    }

    public boolean canSetVideoSetting() {
        return effects.canSetVideoSetting();
    }

    public int getVideoSettingError() {
        return effects.getVideoSettingError();
    }

    public boolean supportsVideoSharpness() {
        return effects.supportsVideoSharpness();
    }

    public int getEngine() {
        return isMpvEngine() ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
    }

    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);
        if (isEmpty() || PlayerEngineFactory.matches(engine, spec)) return;
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (sub == null || sub.isEmpty()) return;
        if (spec != null) spec.setSub(sub);
        if (engine.addSubtitle(sub)) play();
        else startCurrent();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        startCurrent();
    }

    public void selectChapter(MediaChapter chapter) {
        player.selectChapter(chapter);
    }

    public void selectEdition(MediaEdition edition) {
        player.selectEdition(edition);
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        danmakuConfig = config;
        callback.onDanmakuConfigChanged(danmakuConfig);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuEnabled == enabled) return;
        danmakuEnabled = enabled;
        callback.onDanmakuEnabledChanged(danmakuEnabled);
    }

    public void setSubtitleSettingStyle() {
        if (engine != null) engine.setSubtitleStyle();
    }

    public void sendDanmaku(String text) {
        callback.onDanmakuSent(text);
    }

    public float setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeed();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(SpeedSetting.clamp(speed)));
        return getSpeed();
    }

    public float toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? SpeedSetting.getLongPress() : 1);
    }

    public boolean supportsSkipSilence() {
        return effects.supportsSkipSilence();
    }

    public boolean isSkipSilence() {
        return effects.isSkipSilence();
    }

    public void setSkipSilenceEnabled(boolean enabled) {
        effects.setSkipSilenceEnabled(enabled);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) TrackUtil.setTrackSelection(player, tracks);
    }

    public void setVideoSetting(int preset) {
        effects.setVideoSetting(preset);
    }

    public void refreshVideoSetting() {
        effects.refreshVideoSetting();
    }

    public void previewVideoSetting(boolean original) {
        effects.previewVideoSetting(original);
    }

    public void setAudioSetting(int preset) {
        effects.setAudioSetting(preset);
    }

    public void refreshAudioSetting() {
        effects.refreshAudioSetting();
    }

    public void previewAudioSetting(boolean original) {
        effects.previewAudioSetting(original);
    }

    private boolean isMpvEngine() {
        return engine != null && engine.getType() == PlayerEngine.Type.MPV;
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        App.removeCallbacks(runnable); // 起播超时必须一起取消，否则退出后计时一到照样报「播放超时」去自动换源
        // 这里不能撤看门狗标记：换线路/换源都会走 stop，撤了就认不出「播着播着没了」
        lastStop = System.currentTimeMillis();
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public boolean preload(PlaySpec spec, long startPositionMs) {
        if (!canPreloadNext() || spec == null || !PlayerEngineFactory.matches(engine, spec)) return false;
        pendingPreload = new PendingPreload(spec.checkUa(), Math.max(0, startPositionMs));
        startPreloadIfReady();
        return true;
    }

    public void clearPreload() {
        pendingPreload = null;
        if (engine != null) engine.clearPreload();
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public void toggleDecode() {
        int next = isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        if (next == PlayerEngine.HARD) DecodeGuard.unforce(); // 用户手动选回硬解 = 解除强制
        setDecode(next);
    }

    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }

    private void retryDecode(int decode) {
        setDecode(decode);
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    private void setDecode(int decode) {
        this.decode = decode;
        engine.setDecode(decode);
        callback.onDecodeChanged();
    }

    private boolean isHard() {
        return decode == PlayerEngine.HARD;
    }

    /**
     * 把播放异常里的解码器信息挖出来：哪个 codec、什么格式、OMX 诊断码。
     * 老盒子（MStar 这类）的 OMX 崩起来不看这个就是在猜。
     */
    private static String codecDiag(PlaybackException e) {
        StringBuilder sb = new StringBuilder(e.getErrorCodeName());
        try {
            Throwable t = e;
            while (t != null) {
                if ("MediaCodecRendererException".equals(t.getClass().getSimpleName())) {
                    sb.append(" codec=").append(field(t, "codecInfo")); // 包私有类，只能反射挖
                    sb.append(" mime=").append(field(t, "mimeType"));
                    sb.append(" diag=").append(field(t, "diagnosticInfo"));
                } else if (t instanceof android.media.MediaCodec.CodecException ce) {
                    sb.append(" codecErr=0x").append(Integer.toHexString(ce.getErrorCode()));
                    if (ce.getDiagnosticInfo() != null) sb.append(" diag=").append(ce.getDiagnosticInfo());
                }
                t = t.getCause();
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 反射读字段：codecInfo 再往里挖一层 name，别的直接 toString */
    private static Object field(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v == null) return "?";
            if ("codecInfo".equals(name)) {
                try {
                    java.lang.reflect.Field nf = v.getClass().getDeclaredField("name");
                    nf.setAccessible(true);
                    return nf.get(v);
                } catch (Throwable ignored) {
                }
            }
            return v;
        } catch (Throwable e) {
            return "?";
        }
    }

    private void onPlayTimeout() {
        DebugLog.d("Player", "起播超时");
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
        stop();
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
        old.release();
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null) return;
        long delta = System.currentTimeMillis() - lastStop;
        if (delta < 400) { // 刚停过：老 codec 的 release 还在路上，缓一缓再压下一个
            DebugLog.d("Player", "距上次停播 " + delta + "ms，缓到 400ms 再起播");
            App.post(() -> setMediaItem(timeout, startPositionMs), 400 - delta);
            return;
        }
        ensureEngine(spec.checkUa());
        pendingPreload = null;
        initTrack = false;
        engine.start(spec, startPositionMs);
        notifyDanmakuSourceChanged();
        PlayWatchdog.start(engine.getType().name(), spec.getUrl()); // 播放留下痕迹，native 崩也能事后认出来
        DebugLog.d("Player", "起播 engine=" + engine.getType().name() + " url=" + spec.getUrl());
        App.post(runnable, timeout);
        callback.onPrepare();
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    private void startPreloadIfReady() {
        PendingPreload preload = pendingPreload;
        if (preload == null || player.getPlaybackState() != Player.STATE_READY) return;
        pendingPreload = null;
        engine.preload(preload.spec(), preload.startPositionMs());
    }

    @Nullable
    public Uri getSelectedDanmakuUri() {
        Danmaku item = spec != null ? spec.getSelectedDanmaku() : null;
        return item == null ? null : item.getUri();
    }

    public void setDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.selectDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void toggleDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.toggleDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void addDanmaku(Danmaku item) {
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        startCurrent(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);

        void onDanmakuSourceChanged(Uri uri);

        void onDanmakuConfigChanged(DanmakuConfig config);

        void onDanmakuEnabledChanged(boolean enabled);

        void onDanmakuSent(String text);
    }

    private record PendingPreload(PlaySpec spec, long startPositionMs) {
    }

    private record PlaybackSnapshot(long positionMs, boolean playWhenReady, PlaybackParameters playbackParameters, int repeatMode, float volume, long audioOffsetMs, long textOffsetMs) {

        private static PlaybackSnapshot capture(Player player) {
            float volume = player.isCommandAvailable(Player.COMMAND_GET_VOLUME) ? player.getVolume() : Float.NaN;
            long audioOffsetMs = player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : C.TIME_UNSET;
            long textOffsetMs = player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : C.TIME_UNSET;
            return new PlaybackSnapshot(player.getCurrentPosition(), player.getPlayWhenReady(), player.getPlaybackParameters(), player.getRepeatMode(), volume, audioOffsetMs, textOffsetMs);
        }

        private void restore(Player player) {
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackParameters(playbackParameters);
            if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) player.setRepeatMode(repeatMode);
            if (!Float.isNaN(volume) && player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) player.setVolume(volume);
            if (audioOffsetMs != C.TIME_UNSET && player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(audioOffsetMs);
            if (textOffsetMs != C.TIME_UNSET && player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(textOffsetMs);
            if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.setPlayWhenReady(playWhenReady);
        }
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable);
            if (state == Player.STATE_READY) {
                startPreloadIfReady();
                DecodeGuard.noteReady(); // 能播起来，说明之前的硬解失败是片源的事，不是机器的事
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        @Override
        public void onMediaChaptersChanged(@NonNull List<MediaChapter> chapters) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            if (spec == null) return;
            DebugLog.d("Player", "播放异常 " + codecDiag(e));
            if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) DecodeGuard.noteInitFail();
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action != PlayerEngine.ErrorAction.RECOVERED) App.removeCallbacks(runnable);
            switch (action) {
                case DECODE -> handleDecodeError(e);
                case RECOVERED -> notifyDanmakuSourceChanged();
                case FATAL -> callback.onError(engine.getErrorMessage(e));
            }
        }
    };
}

package com.fongmi.android.tv.setting;

import androidx.media3.common.DolbyVisionOutputPolicy;

import com.github.catvod.utils.Prefers;

public class DecodeSetting {

    public static boolean isAudioPassThrough() {
        // 弱机（32 位老盒子）音频直通容易和硬解视频时钟对不上、造成音画不同步，
        // 默认改成关（走 PCM）。只在用户从没设过时才用这个默认值，手动设过的照旧尊重（马先生 2026-11-19）。
        boolean fallback = Prefers.getBoolean("audio_pass_through", !com.fongmi.android.tv.utils.DecodeGuard.isWeakDevice());
        return Prefers.getBoolean("decode_audio_pass_through", fallback);
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
        Prefers.put("decode_audio_pass_through", audioPassThrough);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("decode_audio_prefer", Prefers.getBoolean("audio_prefer"));
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("decode_audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("decode_video_prefer", Prefers.getBoolean("video_prefer"));
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("decode_video_prefer", videoPrefer);
    }

    public static @DolbyVisionOutputPolicy.Mode int getDolbyVisionOutputPolicy() {
        int mode = Prefers.getInt("decode_dolby_vision_output_policy", DolbyVisionOutputPolicy.AUTO);
        return mode >= DolbyVisionOutputPolicy.AUTO && mode <= DolbyVisionOutputPolicy.ASSUME_UNSUPPORTED ? mode : DolbyVisionOutputPolicy.AUTO;
    }

    public static void putDolbyVisionOutputPolicy(@DolbyVisionOutputPolicy.Mode int mode) {
        Prefers.put("decode_dolby_vision_output_policy", mode);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("decode_prefer_aac", Prefers.getBoolean("prefer_aac"));
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("decode_prefer_aac", preferAAC);
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("decode_tunnel", Prefers.getBoolean("tunnel"));
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("decode_tunnel", tunnel);
        if (PlayerSetting.isExo() && tunnel) PlayerSetting.putRender(PlayerSetting.RENDER_SURFACE);
    }

    public static boolean isTunnelingEnabled() {
        // 画面旋转要走 TextureView，隧道模式只认 SurfaceView，转着画面的时候别开隧道
        return isTunnel() && PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE && PlayerSetting.getVideoRotate() == 0;
    }
}

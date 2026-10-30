package com.fongmi.android.tv.cast;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.nio.ByteBuffer;

/**
 * 屏幕广播：把这台设备的整个画面同步给同局域网里别的设备。
 *
 * ⚠️ 它必须跑在**前台服务**里 —— 这不是形式：屏幕广播是设备级的开关，
 * 用户在首页翻巡检员、切设置、甚至退回桌面都不能断，只有服务能保证这些。
 *
 * 跟视频广播的区别要说清楚：
 * - 视频广播发的是**原始片源**，画质是原样，也不占什么 CPU；
 * - 屏幕广播是重新压缩过的画面（H.264 硬编码），清晰度必然降一档，还会吃一点 CPU、多一点延迟（约 0.5~1.5 秒）。
 * 所以它是「实在拿不到流时的兜底」，不是日常追剧的主力通道。
 *
 * 依赖全是系统自带的：MediaProjection 取画面、MediaCodec 硬编码、
 * {@link CastMux} 打包成 TS、{@link CastTx} 发出去。不引第三方库、不加 .so。
 */
public class ScreenCastService extends Service {

    private static final int NOTIFY_ID = 3152;
    private static final String CHANNEL = Notify.DEFAULT;

    private static volatile ScreenCastService instance;

    private CastOutlet outlet;
    private CastMux mux;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec video;
    private Surface surface;
    private Thread drainThread;

    private AudioRecord record;
    private MediaCodec audio;
    private Thread audioThread;
    private long audioPtsUs;

    private volatile boolean casting;
    private volatile byte[] spsPps;
    private int shotWidth;
    private int shotHeight;

    public static boolean running() {
        ScreenCastService self = instance;
        return self != null && self.casting;
    }

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCastService.class);
        intent.putExtra("code", resultCode);
        intent.putExtra("data", data);
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ignored) {
            // 起不来就算了 —— 多半是系统不让后台起前台服务，用户在界面上会有别的入口再试
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int code = intent == null ? 0 : intent.getIntExtra("code", 0);
        Intent data = intent == null ? null : intent.getParcelableExtra("data");
        if (casting) {
            release();
        }
        if (code <= 0 || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 前台服务的类型在 Manifest 里声明过了（安卓 14 起必须），这里不用再单独指定
        try {
            startForeground(NOTIFY_ID, buildNotify());
        } catch (Throwable ignored) {
        }
        if (open(code, data)) return START_STICKY;
        casting = false;
        release();
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏之后虚拟屏的比例不对了：重搭一遍编码器，接收端会在一秒内重新出现画面
        if (!casting || projection == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int height = targetHeight(Math.max(metrics.widthPixels, metrics.heightPixels), Math.min(metrics.widthPixels, metrics.heightPixels));
        if (height / 16 * 16 == shotHeight) return;
        closeEncode();
        startEncode(height);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        release();
        instance = null;
    }

    /* ---------------- 开工 ---------------- */

    private boolean open(int resultCode, Intent data) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) return false;
        try {
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Throwable e) {
            return false;
        }
        if (projection == null) return false;
        casting = true; // 必须在取帧线程起来之前置位，不然线程一进去就退出
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf(); // 用户在系统里点了「停止共享」
            }
        }, new Handler(Looper.getMainLooper()));
        outlet = new CastOutlet(CastPacket.KIND_SCREEN);
        if (!outlet.open(Util.getDeviceName(), "")) return false;
        mux = new CastMux(outlet::write);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return startEncode(targetHeight(Math.max(metrics.widthPixels, metrics.heightPixels), Math.min(metrics.widthPixels, metrics.heightPixels)));
    }

    private boolean startEncode(int height) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenW = Math.max(metrics.widthPixels, metrics.heightPixels);
        int screenH = Math.min(metrics.widthPixels, metrics.heightPixels);
        if (height <= 0 || screenH <= 0) return false;
        int target = Math.min(height, screenH);
        target = target / 16 * 16;
        if (target < 144) return false;
        int width = screenW * target / screenH;
        width = Math.min(width, 1920) / 16 * 16;
        if (width < 176) return false;
        shotWidth = width;
        shotHeight = target;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, target);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate(width, target));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L); // 画面不动就少发点帧
            }
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = video.createInputSurface();
            video.start();
            surface = input;
            display = projection.createVirtualDisplay("oktv-cast", width, target, metrics.densityDpi, 0, input, null, null);
            startAudio();
            drainThread = new Thread(this::drain, "cast-screen");
            drainThread.setDaemon(true);
            drainThread.start();
            return true;
        } catch (Throwable e) {
            closeEncode();
            return false;
        }
    }

    /** 把两段字节拼成一段（SPS/PPS 贴在关键帧前面） */
    private static byte[] join(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private int bitrate(int width, int height) {
        long value = (long) width * height * 30L / 10L; // 0.1 bit/像素：720p30 约 2.7Mbps，够看清了
        return (int) Math.max(1_000_000L, Math.min(8_000_000L, value));
    }

    private int targetHeight(int longSide, int shortSide) {
        int value = CastSetting.targetHeight();
        // 横竖都没关系：短边决定高度上限，别让接收端看到一张被拉扁的画面
        return Math.min(value, shortSide > 0 ? shortSide : value);
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec codec = video;
        if (codec == null) return;
        int frames = 0;
        while (casting && codec != null) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 10_000L);
            } catch (Throwable e) {
                return; // 编码器被释放了
            }
            if (index < 0) continue;
            ByteBuffer buffer;
            try {
                buffer = codec.getOutputBuffer(index);
            } catch (Throwable e) {
                buffer = null;
            }
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (buffer != null && info.size > 0 && mux != null) {
                byte[] frame = new byte[info.size];
                buffer.get(frame);
                if (config) {
                    // SPS/PPS 单独给了一包：留着，贴在下一个关键帧前面一起发出去。
                    // ⚠️ 直接丢掉的话，中途才接进来的接收端拿不到画面参数，永远出不了图。
                    spsPps = frame;
                } else {
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    mux.video(key && spsPps != null ? join(spsPps, frame) : frame, info.presentationTimeUs, key);
                    if (++frames % 6 == 0) mux.flush(); // 攒满就自动发了，这里只是防止最后半包卡在手里
                }
            }
            try {
                codec.releaseOutputBuffer(index, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 声音（可选：拿不到就只有画面） ---------------- */

    private void startAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        MediaProjection target = projection;
        if (target == null) return;
        try {
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(target)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int size = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(size, 8192) * 2)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return;
            }
            MediaFormat mpeg = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
            mpeg.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mpeg.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            mpeg.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audio.configure(mpeg, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.start();
            record.startRecording();
            audioPtsUs = 0L;
            audioThread = new Thread(this::drainAudio, "cast-audio");
            audioThread.setDaemon(true);
            audioThread.start();
        } catch (Throwable e) {
            // 这台机器上抓不到回放声音（盒子常见）：安静地退化成「只广播画面」
            stopAudio();
        }
    }

    private void drainAudio() {
        MediaCodec codec = audio;
        AudioRecord source = record;
        if (codec == null || source == null) return;
        byte[] buf = new byte[4096];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (casting) {
            int read;
            try {
                read = source.read(buf, 0, buf.length);
            } catch (Throwable e) {
                return;
            }
            if (read <= 0) continue;
            int index = codec.dequeueInputBuffer(1000L);
            if (index < 0) continue;
            ByteBuffer input;
            try {
                input = codec.getInputBuffer(index);
            } catch (Throwable e) {
                input = null;
            }
            if (input == null) continue;
            input.clear();
            input.put(buf, 0, read);
            long pts = audioPtsUs;
            audioPtsUs += read * 1_000_000L / (48000L * 4L);
            try {
                codec.queueInputBuffer(index, 0, read, pts, 0);
            } catch (Throwable e) {
                continue;
            }
            int out = codec.dequeueOutputBuffer(info, 0L);
            while (out >= 0) {
                ByteBuffer encoded;
                try {
                    encoded = codec.getOutputBuffer(out);
                } catch (Throwable e) {
                    encoded = null;
                }
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (encoded != null && info.size > 0 && !config && mux != null) {
                    byte[] raw = new byte[info.size];
                    encoded.get(raw);
                    mux.audio(CastAdts.frame(raw, 48000, 2), info.presentationTimeUs);
                }
                try {
                    codec.releaseOutputBuffer(out, false);
                } catch (Throwable ignored) {
                }
                out = codec.dequeueOutputBuffer(info, 0L);
            }
        }
    }

    /* ---------------- 收摊 ---------------- */

    private void closeEncode() {
        Thread thread = drainThread;
        drainThread = null;
        if (thread != null) thread.interrupt();
        stopAudio();
        VirtualDisplay target = display;
        display = null;
        if (target != null) {
            try {
                target.release();
            } catch (Throwable ignored) {
            }
        }
        MediaCodec codec = video;
        video = null;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
        }
        Surface input = surface;
        surface = null;
        if (input != null) input.release();
    }

    private void stopAudio() {
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null) thread.interrupt();
        AudioRecord source = record;
        record = null;
        if (source != null) {
            try {
                source.stop();
            } catch (Throwable ignored) {
            }
            try {
                source.release();
            } catch (Throwable ignored) {
            }
        }
        MediaCodec codec = audio;
        audio = null;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void release() {
        casting = false;
        spsPps = null;
        closeEncode();
        if (mux != null) {
            mux.flush();
            mux = null;
        }
        CastOutlet target = outlet;
        outlet = null;
        if (target != null) target.close();
        MediaProjection projection = this.projection;
        this.projection = null;
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private android.app.Notification buildNotify() {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_lan_tv)
                .setContentTitle(ResUtil.getString(R.string.app_name))
                .setContentText(ResUtil.getString(R.string.cast_screen_running))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}

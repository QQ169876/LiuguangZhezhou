package com.fongmi.android.tv.cast;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.DebugLog;
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

    private static final String TAG = "ScreenCast";
    private static final int NOTIFY_ID = 3152;
    private static final String CHANNEL = Notify.DEFAULT;

    private static volatile ScreenCastService instance;

    private CastOutlet outlet;
    private CastMux mux;
    private ScreenStream stream;
    private PowerManager.WakeLock wake;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec video;
    private Surface surface;
    private Thread drainThread;

    private AudioRecord record;
    private MediaCodec audio;
    private Thread audioThread;
    private long audioPtsUs;
    /**
     * 时间戳锚点（微秒，开机时钟）：**音视频和 PCR 都得挂在这一条时间轴上**。
     * <p>
     * 之前三者各走各的 —— 视频用 MediaCodec 给的 `presentationTimeUs`（VirtualDisplay 打的是
     * 开机至今的时刻，这台手机已开机 17 小时，实测第一帧 PTS = 5,564,112,011 ≈ 17.2h）；
     * 音频是我们自己从 0 累加的计数器；PCR 又由视频 pts 派生，乘完 300 直接撑爆 33 位回绕。
     * 接收端拿 PCR 当主时钟一对照：音频「早该播了」，视频「15 小时后才该播」——
     * 于是它解出第一帧就一直干等，屏幕上就是一张不动的静态图（马先生 2026-11-01 实测）。
     */
    private volatile long anchorUs = -1L;
    private volatile boolean anchored;   // 视频时间轴的原点定下来了没
    /** 最长多久必须出一个关键帧（毫秒）：中途才接进来的接收端就靠它出画 */
    private static final long KEY_MS = 1500L;

    private volatile boolean casting;
    private volatile byte[] spsPps;
    private volatile boolean wantKey;   // 有新接收端接入：立刻催一个关键帧
    private volatile long lastKeyMs;    // 上一个关键帧是什么时候出的
    private volatile long lastAskMs;    // 上一次催是什么时候（别每帧都催）
    private int shotWidth;
    private int shotHeight;

    public static boolean running() {
        ScreenCastService self = instance;
        return self != null && self.casting;
    }

    /** 服务在不在（已经取到画面算 {@link #running()}，仅仅活着算这个） */
    public static boolean started() {
        return instance != null;
    }

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCastService.class);
        intent.putExtra("code", resultCode);
        intent.putExtra("data", data);
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
            DebugLog.d(TAG, "已请求启动前台服务");
        } catch (Throwable e) {
            // 前台这条路被系统拦了（多半是不许从后台起）：先记下来，再用普通方式试一次，
            // 哪怕只是保住「画面能出去」这件事，也比什么都不做强
            DebugLog.d(TAG, "前台启动被拒：" + e.getClass().getSimpleName() + " " + e.getMessage());
            try {
                context.startService(intent);
                DebugLog.d(TAG, "退一步用普通 startService 尝试");
            } catch (Throwable e2) {
                DebugLog.d(TAG, "普通启动也失败：" + e2.getClass().getSimpleName() + " " + e2.getMessage());
            }
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
        // ⚠️ 前台通知必须在 onCreate 里、趁 service 刚起来就打出去。
        // 之前放在 onStartCommand 里，而那里开头还有一段「参数不全就 stopSelf」的分支：
        // 一旦走进那条分支，就是「调了 startForegroundService() 却没调 startForeground()」，
        // 系统会直接抛 ForegroundServiceDidNotStartInTimeException 把 App 带走。
        // 先在这把最该做的事做完，后面再怎么出错也不会连坐整个进程。
        //
        // 而且安卓 10 起要把服务类型一并告诉系统（这里是 mediaProjection）——
        // 只写在清单里、调用时不传，轻则这条通知不被承认为前台，重则直接判违规。
        try {
            Notify.createChannel(); // 通道万一还没建（冷启动、进程被杀过），现场补一个
        } catch (Throwable ignored) {
        }
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIFY_ID, buildNotify(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            DebugLog.d(TAG, "已进前台（带 mediaProjection 类型）");
        } catch (Throwable e) {
            DebugLog.d(TAG, "带类型进前台失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            try {
                startForeground(NOTIFY_ID, buildNotify());
                DebugLog.d(TAG, "已进前台（老式调用）");
            } catch (Throwable e2) {
                DebugLog.d(TAG, "进前台彻底失败：" + e2.getClass().getSimpleName() + " " + e2.getMessage());
                stopSelf(); // 进不了前台就别赖着，留着只会被系统判违规
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 缺省值给 RESULT_OK：调用方只在拿到授权时才会开这个服务，
        // 万一 extra 丢了（进程被杀后系统重拉），也不能拿 0 去 MediaProjection 那边碰壁
        int code = intent == null ? Activity.RESULT_OK : intent.getIntExtra("code", Activity.RESULT_OK);
        Intent data = intent == null ? null : intent.getParcelableExtra("data");
        if (casting) {
            release();
        }
        // ⚠️ 别再用 code 判成败：系统回调给的 resultCode 就是 Activity.RESULT_OK == -1，
        // 拿它跟 0 比大小会把「授权成功」当成「授权失败」，白白停掉服务（5.6.127 上必崩就是这个）。
        // 录屏授权成不成功，看的是有没有那张授权 Intent（data），不是那个数字。
        if (data == null || intent == null || !intent.hasExtra("data")) {
            DebugLog.d(TAG, "缺授权 Intent，不干");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (open(code, data)) return START_STICKY;
        casting = false;
        DebugLog.d(TAG, "链路没搭起来，收工");
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
        // 收摊时一定要把通知撤掉，不然服务已经没了、那条「屏幕广播进行中」还挂在通知栏，
        // 用户点进去是个死链接。
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 开工 ---------------- */

    private boolean open(int resultCode, Intent data) {
        DebugLog.d(TAG, "开始搭链路 resultCode=" + resultCode + " data=" + (data == null ? "空" : "有"));
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            DebugLog.d(TAG, "拿不到 MediaProjectionManager");
            return false;
        }
        try {
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Throwable e) {
            DebugLog.d(TAG, "取 MediaProjection 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
        if (projection == null) {
            DebugLog.d(TAG, "MediaProjection 是空（多半是授权 Intent 已经用过一次）");
            return false;
        }
        casting = true; // 必须在取帧线程起来之前置位，不然线程一进去就退出
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf(); // 用户在系统里点了「停止共享」
            }
        }, new Handler(Looper.getMainLooper()));
        outlet = new CastOutlet(CastPacket.KIND_SCREEN);
        // 先把本机 HTTP 拉流地址准备好：屏幕广播首选这一档（接收端主动连，不依赖 AP 组播）
        try {
            com.fongmi.android.tv.server.Server.get().start();
            // ⚠️ 必须用**局域网 IP**：Server.getAddress("路径") 那个重载是给本机自己用的，
            // 拼出来是 127.0.0.1 —— 报给接收端就成了「请连你自己」，接收端一秒后 Source error 黑屏。
            String lan = com.fongmi.android.tv.server.Server.get().getAddress();
            String screen = lan + "/screen";
            // 拿不到真实局域网 IP（没连 Wi-Fi / 地址还是回环）就别报这一档，退回 UDP
            if (lan.isEmpty() || lan.contains("127.") || lan.contains("localhost")) {
                DebugLog.d(TAG, "本机没有可用的局域网地址（" + lan + "），屏幕广播退回 UDP");
                outlet.setScreenHttp("");
            } else {
                outlet.setScreenHttp(screen);
                DebugLog.d(TAG, "屏幕拉流地址 " + screen);
            }
        } catch (Throwable e) {
            DebugLog.d(TAG, "本机 HTTP 服务起不来，退回 UDP：" + e.getClass().getSimpleName());
        }
        if (!outlet.open(Util.getDeviceName(), "")) {
            DebugLog.d(TAG, "广播端服务没能开起来（端口被占？）");
            return false;
        }
        DebugLog.d(TAG, "广播服务已就位 设备=" + Util.getDeviceName());
        // 息屏保活：CPU 一睡，VirtualDisplay 就不再往编码器喂帧，广播等于悄悄停摆。
        // 只在广播期间持有，收摊立刻还回去。撑不住系统杀后台（那是 MIUI 的规矩），
        // 但能保证「只要进程还活着，息屏后画面照发」。
        try {
            wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oktv:screencast");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Throwable e) {
            DebugLog.d(TAG, "保活锁没拿到：" + e.getClass().getSimpleName());
        }
        // HTTP 主动拉流分发器：新客户端连上时让编码线程补一份节目表
        // 有新接收端通过 HTTP 接进来：补节目表之外，**必须再催一个关键帧** ——
        // SPS/PPS 只贴在关键帧前面，不给关键帧的话对方拿着一堆 P 帧照样解不出画面
        stream = new ScreenStream(() -> {
            CastMux m = mux;
            if (m != null) m.requestHeader();
            wantKey = true;
            DebugLog.d(TAG, "新客户端接入，催关键帧 + 补节目表");
        });
        ScreenStream.set(stream);
        // TS 同时走两路口：原来的 UDP（组播/单播）+ HTTP 分发（不依赖 AP 组播）
        mux = new CastMux((chunk, len) -> {
            outlet.write(chunk, len);
            ScreenStream s = stream;
            if (s != null) s.publish(chunk, len);
        });
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return startEncode(targetHeight(Math.max(metrics.widthPixels, metrics.heightPixels), Math.min(metrics.widthPixels, metrics.heightPixels)));
    }

    private boolean startEncode(int height) {
        anchorUs = System.nanoTime() / 1000L; // 这一轮广播的时间原点：音频先按它对齐
        anchored = false;                     // 视频那条轴等第一帧到手再定
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
            // ⚠️ flags 必须带 AUTO_MIRROR：MediaProjection 录屏的标准用法。
            // 之前传 0，小米等设备在画面「不是本 surface 主动刷新」时就不往编码器喂帧
            // ——日志里表现为「编码开始」之后永远没有「首帧已发出」。
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
            display = projection.createVirtualDisplay("oktv-cast", width, target, metrics.densityDpi, flags, input, null, null);
            startAudio();
            lastKeyMs = System.currentTimeMillis();
            wantKey = true;
            drainThread = new Thread(this::drain, "cast-screen");
            drainThread.setDaemon(true);
            drainThread.start();
            DebugLog.d(TAG, "编码开始 " + width + "x" + target + " @" + bitrate(width, target) / 1000 + "kbps");
            return true;
        } catch (Throwable e) {
            DebugLog.d(TAG, "起编码器失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
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

    /**
     * 把编码器的开机时钟换算成「本轮广播的第几微秒」。
     * 原点取**第一帧视频自己的时刻**，音频那边本来就是从 0 累加的，两边才对得齐；
     * 万一这台机器的两个时钟不是一个基准（差值几小时），这么取也照样各从 0 开始、互相对齐。
     */
    private long stamp(long ptsUs) {
        if (!anchored) {
            anchored = true;
            anchorUs = ptsUs;
        }
        long value = ptsUs - anchorUs;
        return value > 0L ? value : 0L;
    }

    /**
     * 催关键帧。KEY_I_FRAME_INTERVAL 在很多机器上（实测这台小米就是）根本不生效，
     * 编码器只出开头那一个 IDR，之后全是 P 帧 —— 中途接进来的玩家拿到 P 帧没有 SPS/PPS，
     * 解不出画面（PotPlayer 实测：有声音没图像）。所以这里自己按时间催。
     */
    private void askKeyIfNeeded(MediaCodec codec) {
        long now = System.currentTimeMillis();
        if (!wantKey && now - lastKeyMs < KEY_MS) return;
        if (now - lastAskMs < 400) return; // 别一帧一催，催太密反而拖慢编码
        lastAskMs = now;
        wantKey = false;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        } catch (Throwable ignored) {
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec codec = video;
        if (codec == null) return;
        int frames = 0;
        while (casting && codec != null) {
            askKeyIfNeeded(codec);
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
                    if (key) {
                        lastKeyMs = System.currentTimeMillis();
                        if (frames > 0 && spsPps == null) DebugLog.d(TAG, "关键帧缺 SPS/PPS（新接入的接收端会出不了画）");
                    }
                    mux.video(key && spsPps != null ? join(spsPps, frame) : frame, stamp(info.presentationTimeUs), key);
                    if (frames == 0) DebugLog.d(TAG, "首帧已发出 size=" + info.size + " key=" + key + " spsPps=" + (spsPps != null));
                    frames++;
                    if (frames % 300 == 0) DebugLog.d(TAG, "已发 " + frames + " 帧");
                    if (frames % 6 == 0) mux.flush(); // 攒满就自动发了，这里只是防止最后半包卡在手里
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
            // 音频那条计数器是从 0 自己累加的，得先抬到跟视频同一条时间轴上
            // （录音比开编码晚一点点，补上这点差，音画才对得上）
            audioPtsUs = anchorUs < 0L ? 0L : Math.max(0L, System.nanoTime() / 1000L - anchorUs);
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
            int index;
            try {
                index = codec.dequeueInputBuffer(1000L);
            } catch (Throwable e) {
                return; // 编码器已经被收摊了（转屏、退出广播都会走到这儿）
            }
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
            int out;
            try {
                out = codec.dequeueOutputBuffer(info, 0L);
            } catch (Throwable e) {
                // ⚠️ 这里以前没有兜底：音频编码器被 release 之后这个线程再问一句
                // 就是 IllegalStateException: codec is released already，
                // 而它在裸线程里跑，异常直接顶到全局把整个 App 带走（日志里 19:55 那次）。
                return;
            }
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
                try {
                    out = codec.dequeueOutputBuffer(info, 0L);
                } catch (Throwable e) {
                    return;
                }
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
        if (stream != null) {
            stream.close();
            stream = null;
        }
        ScreenStream.set(null);
        if (wake != null) {
            try {
                wake.release();
            } catch (Throwable ignored) {
            }
            wake = null;
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

    /**
     * 前台那条常驻通知。
     *
     * ⚠️ 这地方抛不得异常：它是 {@code startForeground} 的入参，
     * 出一点岔子就等于「答应了系统要进前台却没进」，进程会被系统判违规杀掉。
     * 所以失败就退到一个最朴素的 Notification —— 难看点没关系，先保命。
     */
    private android.app.Notification buildNotify() {
        try {
            return new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_lan_tv)
                    .setContentTitle(ResUtil.getString(R.string.app_name))
                    .setContentText(ResUtil.getString(R.string.cast_screen_running))
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();
        } catch (Throwable e) {
            return new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_lan_tv)
                    .setContentTitle("流光褶皱")
                    .setContentText("屏幕广播进行中")
                    .build();
        }
    }
}

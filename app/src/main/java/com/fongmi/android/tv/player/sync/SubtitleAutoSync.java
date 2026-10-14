package com.fongmi.android.tv.player.sync;

import android.media.AudioFormat;
import android.text.TextUtils;

import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 字幕时间轴**自动校准**：拿"人声出现在哪一刻"去对"字幕出现在哪一刻"，算出差多少，自动补齐。
 * <p>
 * 原理（不需要任何云端/大模型，本地就能算）：
 *   1. 从播放器音频链上挂一个 {@link TeeAudioProcessor}，把 PCM 抄一份出来，
 *      每 50ms 算一个能量值 → 得到一条"声音强度曲线"，超过底噪的当作有人在说话；
 *   2. 字幕这边靠 {@code onCues} 的进出时刻，拼出一条"字幕在屏曲线"；
 *   3. 两条曲线在 ±20 秒里滑着做相关（phi 系数），相关性最高的那个位移就是字幕差了多少；
 *   4. 差值可信就写进「按片子记忆的字幕偏移」，当场生效。
 * <p>
 * 三条自保规矩，免得帮倒忙：
 *   - **每部片子只校准一次**（记在 `subtitle_autosync`），以后都用记忆值，不会每次进播放页都重算；
 *   - 差值小于 400ms 就当"本来就对得上"（采样本身有几十毫秒误差），**不动**；
 *   - 相关性不够（音乐片、一直有人在说话、字幕太稀）就**放弃**，绝不乱调。
 * <p>
 * 线程：{@code flush/handleBuffer} 跑在音频线程，{@code onCues/start/stop} 跑在主线程，
 * 所以碰共享状态的地方一律上同一把锁，别让两边抢出 ConcurrentModificationException。
 */
public final class SubtitleAutoSync implements TeeAudioProcessor.AudioBufferSink {

    private static final String TAG = "SubSync";
    private static final long BIN_MS = 50;
    private static final int MAX_BINS = 3600;              // 攒够 180 秒就停
    private static final long MIN_WINDOW_MS = 45_000;      // 至少攒够这么久才敢算
    private static final long MIN_SUB_INTERVALS_MS = 20_000; // 字幕在屏累计时长不够也别算
    private static final long MAX_SHIFT_MS = 20_000;       // 只认 ±20 秒以内的偏差
    private static final long MIN_APPLY_MS = 400;          // 差这么点就别折腾了
    private static final int MIN_SUB_INTERVALS = 6;        // 字幕段太少没统计意义
    private static final double MIN_PHI = 0.22;            // 相关性门槛

    private static final SubtitleAutoSync INSTANCE = new SubtitleAutoSync();

    public static SubtitleAutoSync get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final float[] energy = new float[MAX_BINS];
    private final List<long[]> subtitle = new ArrayList<>();

    private volatile Player player;
    private volatile boolean armed;
    private volatile boolean busy;

    private String key;
    private long baseOffsetMs;
    private double anchorMs;
    private long samplesPerBin;
    private long binSamples;
    private double binSum;
    private int bins;
    private int encoding;
    private boolean subOn;
    private long subFrom = -1;

    /** 起播时开一轮采集；这部片子校准过、或者没有片名（直播/本地文件）就直接跳过 */
    public void start(Player player, String key, long baseOffsetMs) {
        synchronized (lock) {
            reset();
            if (player == null || TextUtils.isEmpty(key)) return;
            if (SubtitleSetting.isAutoSynced(key)) return;
            this.player = player;
            this.key = key;
            this.baseOffsetMs = baseOffsetMs;
            this.armed = true;
        }
    }

    public void stop() {
        synchronized (lock) {
            reset();
        }
    }

    private void reset() {
        armed = false;
        busy = false;
        player = null;
        key = null;
        bins = 0;
        binSamples = 0;
        binSum = 0;
        subtitle.clear();
        subOn = false;
        subFrom = -1;
    }

    public void onCues(CueGroup group) {
        if (group == null || !armed || busy) return;
        onCues(group.cues);
    }

    public void onCues(List<Cue> cues) {
        synchronized (lock) {
            if (!armed || busy || player == null) return;
            boolean on = cues != null && !cues.isEmpty();
            long now = position();
            if (on == subOn) return;
            if (on) {
                subFrom = now;
            } else if (subFrom >= 0 && now > subFrom) {
                subtitle.add(new long[]{subFrom, now});
                subFrom = -1;
            }
            subOn = on;
        }
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, int encoding) {
        synchronized (lock) {
            if (!armed || busy || sampleRateHz <= 0) return;
            this.encoding = encoding;
            this.samplesPerBin = Math.max(1, (long) sampleRateHz * Math.max(1, channelCount) * BIN_MS / 1000);
            binSamples = 0;
            binSum = 0;
            // 换段（起播/拖动/换源）都要重新锚：这一段音频是从哪个媒体时间开始的
            anchorMs = position();
            // 前面的样本跟新锚点对不上，作废重攒
            bins = 0;
            subOn = false;
            subFrom = -1;
            subtitle.clear();
            DebugLog.d(TAG, "音频段开始 sr=" + sampleRateHz + " ch=" + channelCount + " enc=" + encoding + " 锚点=" + anchorMs + "ms");
        }
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
        synchronized (lock) {
            if (!armed || busy || buffer == null) return;
            if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) return;
            if (bins >= MAX_BINS) {
                finish();
                return;
            }
            int width = encoding == AudioFormat.ENCODING_PCM_16BIT ? 2 : 4;
            while (buffer.remaining() >= width) {
                double value = encoding == AudioFormat.ENCODING_PCM_16BIT ? buffer.getShort() : buffer.getFloat();
                binSum += value * value;
                if (++binSamples >= samplesPerBin) {
                    energy[bins++] = (float) Math.sqrt(binSum / binSamples);
                    binSamples = 0;
                    binSum = 0;
                    if (bins >= MAX_BINS) break;
                }
            }
            finish();
        }
    }

    private void finish() {
        if (!armed || busy) return;
        long span = (long) bins * BIN_MS;
        if (span < MIN_WINDOW_MS) return;
        if (subtitle.size() < MIN_SUB_INTERVALS || onScreenMs() < MIN_SUB_INTERVALS_MS) {
            // 听了三分钟一条字幕都没出来：这集压根没挂字幕，收工但不记账 ——
            // 记账会导致用户后来挂上字幕时反而不校准了
            if (bins >= MAX_BINS) armed = false;
            return;
        }
        compute();
    }

    private long onScreenMs() {
        long total = 0;
        for (long[] span : subtitle) total += Math.max(0, span[1] - span[0]);
        return total;
    }

    private void compute() {
        busy = true;
        armed = false;
        int count = bins;
        float[] audio = Arrays.copyOf(energy, count);
        List<long[]> spans = new ArrayList<>(subtitle);
        double start = anchorMs;
        long base = baseOffsetMs;
        String title = key;
        Task.execute(() -> {
            long delta = correlate(audio, count, spans, start);
            App.post(() -> apply(title, base, delta));
        });
    }

    /**
     * 两条曲线滑着比：返回字幕该挪多少毫秒（正=字幕要往后推）。返回 {@code Long.MAX_VALUE} 表示没算出可信结果。
     */
    private static long correlate(float[] energy, int bins, List<long[]> spans, double start) {
        if (bins < 100 || spans.isEmpty()) return Long.MAX_VALUE;
        boolean[] subOn = new boolean[bins];
        int subCount = 0;
        for (long[] span : spans) {
            int from = (int) Math.floor((span[0] - start) / BIN_MS);
            int to = (int) Math.ceil((span[1] - start) / BIN_MS);
            for (int i = Math.max(0, from); i < Math.min(bins, to); i++) if (!subOn[i]) {
                subOn[i] = true;
                subCount++;
            }
        }
        if (subCount < bins * 0.03) return Long.MAX_VALUE; // 字幕太稀，比不出东西
        float[] sorted = Arrays.copyOf(energy, bins);
        Arrays.sort(sorted);
        float floor = sorted[(int) (sorted.length * 0.35)];
        float peak = sorted[sorted.length - 1];
        if (peak <= 0) return Long.MAX_VALUE;
        float threshold = Math.max(floor * 2.2f, peak * 0.08f);
        boolean[] audioOn = new boolean[bins];
        int audioCount = 0;
        for (int i = 0; i < bins; i++) if (energy[i] > threshold) {
            audioOn[i] = true;
            audioCount++;
        }
        if (audioCount < bins * 0.10 || audioCount > bins * 0.92) return Long.MAX_VALUE; // 全程静音 or 全程响，没法对齐

        int maxK = (int) (MAX_SHIFT_MS / BIN_MS);
        double best = -2;
        int bestK = 0;
        double[] scores = new double[2 * maxK + 1];
        for (int k = -maxK; k <= maxK; k++) {
            double phi = phi(audioOn, subOn, bins, k);
            scores[k + maxK] = phi;
            if (phi > best) {
                best = phi;
                bestK = k;
            }
        }
        if (best < MIN_PHI) return Long.MAX_VALUE;
        // 峰值得够突出：跟 1.5 秒开外的次高比一比，太平就不敢信
        double second = -2;
        int guard = (int) (1500 / BIN_MS);
        for (int k = -maxK; k <= maxK; k++) {
            if (Math.abs(k - bestK) <= guard) continue;
            if (scores[k + maxK] > second) second = scores[k + maxK];
        }
        if (best < second + 0.05) return Long.MAX_VALUE;
        return bestK * BIN_MS;
    }

    /** 两个二值序列的 phi 系数：subOn[i] 对 audioOn[i + k] */
    private static double phi(boolean[] audio, boolean[] sub, int n, int k) {
        long n11 = 0, n10 = 0, n01 = 0, n00 = 0;
        for (int i = 0; i < n; i++) {
            int j = i + k;
            if (j < 0 || j >= n) continue;
            boolean a = audio[j];
            boolean b = sub[i];
            if (a && b) n11++;
            else if (a) n10++;
            else if (b) n01++;
            else n00++;
        }
        double den = Math.sqrt((double) (n11 + n10) * (n01 + n00) * (n11 + n01) * (n10 + n00));
        if (den == 0) return 0;
        return (n11 * n00 - n10 * n01) / den;
    }

    private void apply(String title, long base, long delta) {
        busy = false;
        // 不管算没算出来，这部片子都算"校准过了"，下次别再折腾一遍
        if (!TextUtils.isEmpty(title)) SubtitleSetting.markAutoSynced(title);
        if (delta == Long.MAX_VALUE) {
            DebugLog.d(TAG, "没算出可信差值，保持原样 key=" + title);
            return;
        }
        if (Math.abs(delta) < MIN_APPLY_MS) {
            DebugLog.d(TAG, "差值 " + delta + "ms 太小，本来就是对得上的 key=" + title);
            return;
        }
        long target = SubtitleSetting.clampOffset(base + delta);
        SubtitleSetting.putOffsetMs(title, target);
        Player current = player;
        if (current != null && current.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) current.setTextOffsetMs(target);
        Notify.show(ResUtil.getString(R.string.subtitle_auto_sync_done, String.format(Locale.getDefault(), "%+.1f", delta / 1000f)));
        DebugLog.d(TAG, "自动校准字幕 " + delta + "ms（原偏移 " + base + " → " + target + "）key=" + title);
    }

    private long position() {
        try {
            Player current = player;
            return current == null ? 0 : current.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }
}

package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.DebugLog;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纠正 HEVC 编码等级虚标（播放设置里的「纠正虚标编码等级」开关，默认关）。
 *
 * <p>部分竖屏短剧把 codecs 写成 {@code hvc1.1.6.L186}（HEVC Level 6.2），实际只有 1080x1920@30，
 * 正常 Level 4.0（L120）就够。MediaCodec 能力探测拿这个虚标 level 去比对设备 profileLevels，
 * 判「不支持」→ EXO 不选硬解 renderer。硬解模式又不给 ffmpeg 退路，于是这类短剧直接播不了。
 *
 * <p>这里在 extractor 把轨道 Format 交给播放器那一刻，把明显虚高的 level 钳回 4.0。
 * 只改 codecs 字符串，不碰码流和 CSD，也不影响音轨。条件刻意收紧，避免误伤正常的 4K/HDR/高帧率片源：
 * 仅 HEVC、level ≥ 5.0（L150+）、分辨率长边 ≤ 1920 且短边 ≤ 1080（含竖屏 1080x1920）、帧率 ≤ 31。
 */
public final class HvcLevelFix {

    private static final String TAG = "HvcLevel";
    /** hvc1.1.6.L186 / hev1.1.6.L186.B0 这类：前缀 + 两段 profile + L<levelIdc> + 可选后缀 */
    private static final Pattern CODECS = Pattern.compile("^(hvc1|hev1)\\.(\\d+)\\.([0-9a-fA-F]+)\\.L(\\d+)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final int SUSPECT_MIN_IDC = 150; // Level 5.0 起算
    private static final int SAFE_IDC = 120;        // 钳回 Level 4.0
    private static final int MAX_EDGE = 1920;       // 长边
    private static final int MIN_EDGE = 1080;       // 短边
    private static final float MAX_FPS = 31f;       // 留点余量给 29.97

    private HvcLevelFix() {
    }

    /** 包装 extractor 工厂；开关关闭时 format() 原样放行，行为完全不变。 */
    public static ExtractorsFactory wrap(ExtractorsFactory delegate) {
        return new FixFactory(delegate);
    }

    private static Format fix(Format format) {
        if (format == null || !PlayerSetting.isHvcLevelFix()) return format;
        String codecs = format.codecs;
        if (codecs == null) return format;
        Matcher m = CODECS.matcher(codecs.trim());
        if (!m.matches()) return format;
        int idc;
        try {
            idc = Integer.parseInt(m.group(4));
        } catch (NumberFormatException ignored) {
            return format;
        }
        if (idc < SUSPECT_MIN_IDC) return format;
        int w = format.width, h = format.height;
        if (w <= 0 || h <= 0) return format;
        if (Math.max(w, h) > MAX_EDGE || Math.min(w, h) > MIN_EDGE) return format;
        float fps = format.frameRate;
        if (fps > MAX_FPS) return format;
        String fixed = m.group(1) + "." + m.group(2) + "." + m.group(3) + ".L" + SAFE_IDC + m.group(5);
        DebugLog.d(TAG, "纠正虚标等级 " + codecs + " -> " + fixed + " (" + w + "x" + h + " @" + fps + ")");
        return format.buildUpon().setCodecs(fixed).build();
    }

    /** 工厂代理：所有配置方法透传给 delegate，返回自身保住包装链。 */
    private static final class FixFactory implements ExtractorsFactory {
        private final ExtractorsFactory delegate;

        FixFactory(ExtractorsFactory delegate) {
            this.delegate = delegate;
        }

        @NonNull
        @Override
        public Extractor[] createExtractors() {
            return wrap(delegate.createExtractors());
        }

        @NonNull
        @Override
        public Extractor[] createExtractors(@NonNull Uri uri, @NonNull Map<String, List<String>> headers) {
            return wrap(delegate.createExtractors(uri, headers));
        }

        private Extractor[] wrap(Extractor[] src) {
            Extractor[] out = new Extractor[src.length];
            for (int i = 0; i < src.length; i++) out[i] = new FixExtractor(src[i]);
            return out;
        }

        @NonNull
        @Override
        public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
            delegate.experimentalSetTextTrackTranscodingEnabled(enabled);
            return this;
        }

        @NonNull
        @Override
        public ExtractorsFactory setSubtitleParserFactory(androidx.media3.extractor.text.SubtitleParser.Factory factory) {
            delegate.setSubtitleParserFactory(factory);
            return this;
        }

        @NonNull
        @Override
        public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int i) {
            delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(i);
            return this;
        }
    }

    /** 逐个 extractor 代理，只在 init() 时把 ExtractorOutput 换成包装版。 */
    private static final class FixExtractor implements Extractor {
        private final Extractor delegate;

        FixExtractor(Extractor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @Override
        public List<SniffFailure> getSniffFailureDetails() {
            return delegate.getSniffFailureDetails();
        }

        @Override
        public void init(ExtractorOutput output) {
            delegate.init(new FixOutput(output));
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public Extractor getUnderlyingImplementation() {
            return delegate.getUnderlyingImplementation();
        }
    }

    private static final class FixOutput implements ExtractorOutput {
        private final ExtractorOutput delegate;

        FixOutput(ExtractorOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public TrackOutput track(int id, int type) {
            return new FixTrack(delegate.track(id, type));
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }
    }

    /** 轨道输出代理，只在 format() 时拦截并改写 codecs。 */
    private static final class FixTrack implements TrackOutput {
        private final TrackOutput delegate;

        FixTrack(TrackOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void format(Format format) {
            delegate.format(fix(format));
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput) throws IOException {
            return delegate.sampleData(input, length, allowEndOfInput);
        }

        @Override
        public void sampleData(ParsableByteArray data, int length) {
            delegate.sampleData(data, length);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part) throws IOException {
            return delegate.sampleData(input, length, allowEndOfInput, part);
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int part) {
            delegate.sampleData(data, length, part);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
        }
    }
}

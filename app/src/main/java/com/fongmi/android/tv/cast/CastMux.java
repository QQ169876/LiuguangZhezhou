package com.fongmi.android.tv.cast;

import java.util.Arrays;

/**
 * 把编码器出来的裸 H.264 / AAC 打包成 MPEG-TS。
 *
 * 为什么非得有这一步：Android 自带的 MediaMuxer 只认 mp4 / webm，
 * 而 UDP 这一路上**只能发 TS** —— mp4 的索引表在文件尾，流式发出去接收端永远等不到。
 * TS 天生就是为流式设计的：每个 188 字节的小包自带同步字节，中途接入也能立刻开播。
 *
 * 注意这不是「重新编码」：它只是给已经压好的帧套一层壳，CPU 开销可以忽略，
 * 也不引入 ffmpeg、不加 .so。
 *
 * 这一整块都是纯字节操作，因此在普通 JVM 上就能验：
 * 生成出来的每一包是不是 188 对齐、PAT/PMT 有没有带自身的 CRC、
 * 视频 PID 里的载荷拼回去是不是还是原来那一帧。
 */
public final class CastMux {

    public interface Sink {

        /** 交出去的必然是整数个 TS 包；为了少发 UDP 包，一般攒够 {@link TsKit#UNIT} 才给一次 */
        void onTs(byte[] data, int len);
    }

    private static final int SIZE = TsKit.PACKET;
    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x0100;
    private static final int PID_AUDIO = 0x0101;

    /** 视频 PID 的 continuity counter 上限位 */
    private static final int CC_MASK = 0x0F;
    /** 隔一会儿就重发一次节目表：中途才点进来的接收端靠它起步 */
    private static final long PAT_MS = 400L;

    private final Sink sink;
    private final byte[] buffer = new byte[TsKit.UNIT];
    private final byte[] pes = new byte[64 * 1024];

    private int filled;
    private int ccPat;
    private int ccPmt;
    private int ccVideo;
    private int ccAudio;
    private long lastPatMs; // 0 而不是 Long.MIN_VALUE：否则第一次相减会溢出成负数，节目表一次都发不出去

    public CastMux(Sink sink) {
        this.sink = sink;
    }

    /** 每个新 PES 之前顺手补一次节目表；返回值是「这次是不是真的发了」 */
    public boolean header() {
        long now = System.currentTimeMillis();
        if (now - lastPatMs < PAT_MS) return false;
        lastPatMs = now;
        pat();
        pmt();
        return true;
    }

    /**
     * 塞一帧视频（Annex-B，带起始码），ptsUs 是这一帧的呈现时刻（微秒）。
     * PCR 跟着关键帧走，并且保证不超过 100ms 就有一次（标准要求的下限）。
     */
    public synchronized void video(byte[] frame, long ptsUs, boolean keyframe) {
        if (frame == null || frame.length == 0) return;
        header();
        int len = buildPes(0xE0, frame, ptsUs);
        long now = System.currentTimeMillis();
        write(PID_VIDEO, pes, len, true, true, now, ptsUs);
    }

    /** 塞一帧音频（必须是 ADTS 包好的 AAC，本身就带时间戳信息） */
    public synchronized void audio(byte[] adts, long ptsUs) {
        if (adts == null || adts.length == 0) return;
        int len = buildPes(0xC0, adts, ptsUs);
        write(PID_AUDIO, pes, len, true, false, 0L, ptsUs);
    }

    /** 收尾：把攒着不满一包的部分发出去 */
    public synchronized void flush() {
        if (filled <= 0) return;
        sink.onTs(buffer, filled * SIZE);
        filled = 0;
    }

    /* ---------------- 节目表 ---------------- */

    private void pat() {
        byte[] section = new byte[16];
        int at = 0;
        section[at++] = 0x00;                     // table_id
        section[at++] = (byte) 0xB0;              // 语法指示 + 长度高位
        section[at++] = 0x0D;                     // section_length = 13
        section[at++] = 0x00;
        section[at++] = 0x01;                     // transport_stream_id
        section[at++] = (byte) 0xC1;              // version 0 + current_next 1
        section[at++] = 0x00;                     // section_number
        section[at++] = 0x00;                     // last_section_number
        section[at++] = 0x00;
        section[at++] = 0x01;                     // program_number
        section[at++] = (byte) (0xE0 | ((PID_PMT >> 8) & 0x1F));
        section[at++] = (byte) (PID_PMT & 0xFF);
        crc(section, at);
        byte[] payload = psi(section, at + 4);
        write(PID_PAT, payload, payload.length, true, false, 0L, 0L);
    }

    private void pmt() {
        byte[] section = new byte[32];
        int at = 0;
        section[at++] = 0x02;                     // table_id
        section[at++] = (byte) 0xB0;
        section[at] = 0x00;                       // section_length 先占位，填完再回填真值
        at++;
        section[at++] = 0x00;
        section[at++] = 0x01;                     // program_number
        section[at++] = (byte) 0xC1;
        section[at++] = 0x00;
        section[at++] = 0x00;
        section[at++] = (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        section[at++] = (byte) (PID_VIDEO & 0xFF); // PCR 挂在视频这条 PID 上
        section[at++] = (byte) 0xF0;
        section[at++] = 0x00;                     // program_info_length = 0
        section[at++] = 0x1B;                     // H.264
        section[at++] = (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        section[at++] = (byte) (PID_VIDEO & 0xFF);
        section[at++] = (byte) 0xF0;
        section[at++] = 0x00;
        section[at++] = 0x0F;                     // AAC（ADTS）
        section[at++] = (byte) (0xE0 | ((PID_AUDIO >> 8) & 0x1F));
        section[at++] = (byte) (PID_AUDIO & 0xFF);
        section[at++] = (byte) 0xF0;
        section[at++] = 0x00;
        int body = at + 4;                        // 到 CRC 结束为止的总长
        section[2] = (byte) (body - 3);           // section_length 从长度字段之后数起
        crc(section, at);
        byte[] payload = psi(section, at + 4);
        write(PID_PMT, payload, payload.length, true, false, 0L, 0L);
    }

    /** PSI 载荷：先一个 pointer_field（写死 0，因为本节总是从这一包的开头起），后面填充 0xFF */
    private byte[] psi(byte[] section, int len) {
        byte[] payload = new byte[184];
        payload[0] = 0x00;
        int copy = Math.min(len, payload.length - 1);
        System.arraycopy(section, 0, payload, 1, copy);
        if (1 + copy < payload.length) Arrays.fill(payload, 1 + copy, payload.length, (byte) 0xFF);
        return payload;
    }

    /** MPEG-2 分段的 CRC-32：多项式 0x04C11DB7、初值全 1、不反射、也不取反 */
    private static void crc(byte[] data, int at) {
        int value = 0xFFFFFFFF;
        for (int i = 0; i < at; i++) {
            value ^= (data[i] & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80000000) != 0 ? (value << 1) ^ 0x04C11DB7 : value << 1;
            }
        }
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    /* ---------------- PES ---------------- */

    /** 把一帧包成 PES（PTS 和 DTS 都给，因为没有 B 帧，两者是一样的） */
    private int buildPes(int streamId, byte[] frame, long ptsUs) {
        long ticks = ptsUs * 9L / 100L;           // 微秒 → 90kHz
        long value = ticks & 0x1FFFFFFFFL;
        int at = 0;
        pes[at++] = 0x00;
        pes[at++] = 0x00;
        pes[at++] = 0x01;
        pes[at++] = (byte) streamId;
        long length = (long) frame.length + 3 + 10;
        pes[at++] = (byte) (length > 0xFFFF ? 0 : (length >> 8)); // 视频超过 64K 就写 0，标准允许不限长
        pes[at++] = (byte) (length > 0xFFFF ? 0 : length);
        pes[at++] = (byte) 0x84;                  // 10 + 00 + 优先级0 + 数据对齐1 + 00
        pes[at++] = (byte) 0xC0;                  // PTS 和 DTS 都在
        pes[at++] = 10;                           // 后面还有 10 个字节的标志
        stamp(at, 0x3, value);
        at += 5;
        stamp(at, 0x1, value);
        at += 5;
        if (at + frame.length > pes.length) return 0; // 一帧比 64K 还大：宁可丢这一帧也别写坏内存
        System.arraycopy(frame, 0, pes, at, frame.length);
        return at + frame.length;
    }

    private void stamp(int at, int flag, long value) {
        pes[at] = (byte) (((flag & 0x0F) << 4) | ((value >>> 29) & 0x0E) | 0x01);
        pes[at + 1] = (byte) (value >>> 22);
        pes[at + 2] = (byte) (((value >>> 14) & 0xFE) | 0x01);
        pes[at + 3] = (byte) (value >>> 7);
        pes[at + 4] = (byte) (((value << 1) & 0xFE) | 0x01);
    }

    /* ---------------- 打包成 188 ---------------- */

    /**
     * @param withPcr 首包是否同时插入 PCR（借用带首帧的那包，省一点额外字节）
     */
    private void write(int pid, byte[] payload, int len, boolean pusi, boolean withPcr, long nowMs, long ptsUs) {
        int pos = 0;
        boolean first = true;
        while (pos < len) {
            byte[] out = new byte[SIZE];
            boolean pcrHere = first && withPcr;
            int room = pcrHere ? 176 : 184;
            int take = Math.min(len - pos, room);
            boolean adapt = pcrHere || take < 184;
            out[0] = 0x47;
            out[1] = (byte) ((pusi && first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            out[2] = (byte) (pid & 0xFF);
            out[3] = (byte) ((adapt ? 0x30 : 0x10) | (cc(pid) & CC_MASK));
            int at = 4;
            if (adapt) {
                int body = 183 - take;            // 自适应区总长 1(长度字节) + body
                out[at++] = (byte) body;
                int need = body;
                if (pcrHere) {
                    out[at++] = (byte) 0x10;      // PCR 标志
                    TsKit.encode(out, at, ptsUs * 9L / 100L * 300L);
                    at += 6;
                    need -= 7;
                } else if (need > 0) {
                    out[at++] = 0x00;             // 只是凑数：给个空的标志字节
                    need--;
                }
                while (need > 0) {
                    out[at++] = (byte) 0xFF;
                    need--;
                }
            }
            System.arraycopy(payload, pos, out, SIZE - take, take);
            pos += take;
            first = false;
            unit(out);
        }
    }

    private int cc(int pid) {
        if (pid == PID_PAT) return ccPat++;
        if (pid == PID_PMT) return ccPmt++;
        if (pid == PID_VIDEO) return ccVideo++;
        return ccAudio++;
    }

    private void unit(byte[] packet) {
        System.arraycopy(packet, 0, buffer, filled * SIZE, SIZE);
        if (++filled * SIZE >= TsKit.UNIT) {
            sink.onTs(buffer, filled * SIZE);
            filled = 0;
        }
    }
}

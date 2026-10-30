package com.fongmi.android.tv.cast;

/**
 * 给 AAC 裸帧套一层 ADTS 头。
 *
 * 为什么要这一步：编码器吐出来的是裸 AAC 帧，**没有采样率、声道这些信息**，
 * 单独一帧送到 TS 里，接收端不知道该怎么解；ADTS 就是在每帧前面加 7 个字节说明这些参数，
 * 代价是每帧多 7 字节（约 1% 不到），换来的是接收端随时可接入。
 *
 * 纯字节操作，所以能在普通 JVM 上直接验：同步字、 profile、采样率索引、帧长这几项对不对。
 */
public final class CastAdts {

    private CastAdts() {
    }

    /** 采样率 → ADTS 里的索引；查不到就按 48kHz（编码器默认给的值） */
    public static int index(int sampleRate) {
        switch (sampleRate) {
            case 96000:
                return 0;
            case 88200:
                return 1;
            case 64000:
                return 2;
            case 48000:
                return 3;
            case 44100:
                return 4;
            case 32000:
                return 5;
            case 24000:
                return 6;
            case 22050:
                return 7;
            case 16000:
                return 8;
            case 12000:
                return 9;
            case 11025:
                return 10;
            case 8000:
                return 11;
            default:
                return 3;
        }
    }

    /**
     * @param raw AAC 裸帧
     * @return 带 ADTS 头的完整帧
     */
    public static byte[] frame(byte[] raw, int sampleRate, int channels) {
        if (raw == null || raw.length == 0) return raw;
        int ch = channels <= 0 ? 2 : Math.min(channels, 7);
        int length = raw.length + 7;
        byte[] out = new byte[length];
        out[0] = (byte) 0xFF;                                              // 同步字高 8 位
        out[1] = (byte) 0xF1;                                              // 同步字低 4 位 + MPEG-4 + layer 0 + 无 CRC
        out[2] = (byte) ((0x01 << 6) | (index(sampleRate) << 2) | ((ch >> 2) & 0x01)); // LC Profile + 采样率 + 声道高位
        out[3] = (byte) (((ch & 0x03) << 6) | ((length >> 11) & 0x03));     // 声道低位 + 帧长高三位
        out[4] = (byte) ((length >> 3) & 0xFF);
        out[5] = (byte) (((length & 0x07) << 5) | 0x1F);                    // 帧长低三位 + 缓冲满度高五位
        out[6] = (byte) 0xFC;                                               // 缓冲满度低六位 + 单帧
        System.arraycopy(raw, 0, out, 7, raw.length);
        return out;
    }
}

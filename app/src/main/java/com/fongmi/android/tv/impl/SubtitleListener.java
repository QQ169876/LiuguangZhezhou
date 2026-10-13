package com.fongmi.android.tv.impl;

public interface SubtitleListener {

    void setSubtitleToken(String token);

    /** 字幕设置弹窗里两项一起保存时用（只重搜一次）；只改 token 的老调用走上面那个 */
    void setSubtitleKeys(String token, String subdlKey);
}

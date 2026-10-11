package com.fongmi.android.tv.impl;

import android.view.View;

public interface UpdateListener {

    /** 取消 = 这一版先不装（跳过它），下次出新版本照样提醒 */
    void onCancel(View view);

    void onConfirm(View view);

    /** 不再提示 = 彻底关掉自动检查更新 */
    void onNever(View view);
}

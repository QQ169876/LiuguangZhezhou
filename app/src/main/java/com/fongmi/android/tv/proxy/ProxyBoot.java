package com.fongmi.android.tv.proxy;

import android.content.Context;
import android.net.VpnService;

import com.fongmi.android.tv.App;

public class ProxyBoot {

    public static void boot() {
        App.post(() -> {
            try {
                Context context = App.get();
                if (!ProxySetting.isEnabled()) return;
                if (ProxySetting.getNodes().isEmpty()) return;
                if (VpnService.prepare(context) != null) return;
                ProxyService.start(context);
            } catch (Exception ignored) {
            }
        }, 3000);
    }
}

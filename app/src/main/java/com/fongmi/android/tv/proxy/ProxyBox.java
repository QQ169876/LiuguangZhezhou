package com.fongmi.android.tv.proxy;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.R;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

public class ProxyBox implements CommandServerHandler {

    private static final String TAG = "ProxyBox";
    private static final ProxyBox instance = new ProxyBox();

    private CommandServer server;
    private ProxyService service;
    private boolean running;

    public static ProxyBox get() {
        return instance;
    }

    public boolean isRunning() {
        return running && server != null;
    }

    public synchronized void start(ProxyService service, ProxyPlatform platform) {
        this.service = service;
        try {
            setup(service);
            if (server == null) server = Libbox.newCommandServer(this, platform);
            server.start();
            reload();
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
            fail(e.getMessage());
        }
    }

    public synchronized void reload() {
        try {
            String config = ProxyConfig.build(ProxySetting.getNodes(), ProxySetting.getTag());
            if (config == null) {
                fail(service == null ? "empty" : service.getString(R.string.proxy_empty));
                return;
            }
            server.startOrReloadService(config, new OverrideOptions());
            running = true;
            ProxyControl.get().connect();
            if (service != null) service.notifyUser(service.getString(R.string.proxy_title), service.getString(R.string.proxy_running, ProxySetting.getTag()));
        } catch (Exception e) {
            Log.w(TAG, "reload failed", e);
            fail(e.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        ProxyControl.get().disconnect();
        if (service != null) service.closeFd();
        try {
            if (server != null) server.closeService();
        } catch (Exception e) {
            Log.w(TAG, "close service", e);
        }
        try {
            if (server != null) server.close();
        } catch (Exception e) {
            Log.w(TAG, "close server", e);
        }
        server = null;
        if (service != null) {
            service.stopSelf();
            service = null;
        }
    }

    private void fail(String message) {
        running = false;
        if (service != null) service.notifyUser(service.getString(R.string.proxy_title), message == null ? "" : message);
    }

    private static boolean setupDone;

    private static synchronized void setup(Context context) throws Exception {
        if (setupDone) return;
        SetupOptions options = new SetupOptions();
        options.setBasePath(context.getFilesDir().getAbsolutePath());
        options.setWorkingPath(context.getFilesDir().getAbsolutePath());
        options.setTempPath(context.getCacheDir().getAbsolutePath());
        options.setFixAndroidStack(true);
        options.setLogMaxLines(300);
        Libbox.setup(options);
        setupDone = true;
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        return new SystemProxyStatus();
    }

    @Override
    public void serviceReload() {
        reload();
    }

    @Override
    public void serviceStop() {
        stop();
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void writeDebugMessage(String message) {
        Log.d("sing-box", String.valueOf(message));
    }
}

package com.fongmi.android.tv.player.extractor;
import java.util.Collections;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.UrlUtil;
import com.forcetech.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class Force implements Source.Extractor, ServiceConnection {

    private static final Pattern PATTERN = Pattern.compile("(?i)(p[2-9]p|mitv)");
    private static final long BIND_TIMEOUT = 5000L;
    // 服务连接回调在主线程写、fetch 在工作线程读：原来的裸 HashSet 既没有可见性保证，
    // 回调可能永远等不到，while 循环就会 10ms 一次空转到底
    private final Set<String> set = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean bound;

    @Override
    public boolean match(Uri uri) {
        return PATTERN.matcher(UrlUtil.scheme(uri)).find();
    }

    private void init(String scheme) {
        bound = true;
        App.get().bindService(Util.intent(App.get(), scheme), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public String fetch(String url) throws Exception {
        String scheme = Util.scheme(url);
        if (!set.contains(scheme)) init(scheme);
        long until = SystemClock.elapsedRealtime() + BIND_TIMEOUT;
        while (!set.contains(scheme)) {
            if (SystemClock.elapsedRealtime() >= until) throw new IllegalStateException("Force service bind timeout: " + scheme);
            SystemClock.sleep(10);
        }
        Uri uri = UrlUtil.uri(url);
        int port = Util.port(scheme);
        String id = uri.getLastPathSegment();
        String cmd = "http://127.0.0.1:" + port + "/cmd.xml?cmd=switch_chan&server=" + uri.getHost() + ":" + uri.getPort() + "&id=" + id;
        OkHttp.string(cmd, Collections.singletonMap(HttpHeaders.USER_AGENT, "MTV"));
        return "http://127.0.0.1:" + port + "/" + id;
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
        try {
            if (bound) App.get().unbindService(this); // 按「发起过绑定」解，别因为超时时 set 还是空的就漏解
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            bound = false;
            set.clear();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        set.add(Util.trans(name));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        set.remove(Util.trans(name));
    }
}

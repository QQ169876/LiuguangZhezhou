package com.fongmi.android.tv.server;

import android.app.Activity;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.dialog.UploadDialog;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;

/**
 * 局域网推送文件 / APK 时，接收这一头的进度。
 *
 * 难点：NanoHTTPD 会把整个请求体先读进临时文件，读完之后才轮到业务代码，
 * 所以按原来的写法，传到一半的时候「已经收到多少」是量不出来的。
 * 这里换个办法：给服务套一层临时文件工厂，请求体写临时文件的同时顺手数字节。
 * 一个连接从创建 TempFileManager 到把请求体读完都在同一条线程上，
 * 所以用 ThreadLocal 就能把这一路网络和那个进度框对上号。
 */
public class UploadProgress {

    private static final long MIN_SIZE = 512 * 1024;

    private static final ThreadLocal<Manager> LOCAL = new ThreadLocal<>();

    private static UploadDialog dialog;

    /** 套在原本的工厂外面：临时目录的创建规则照旧，只是多记一笔账 */
    public static NanoHTTPD.TempFileManagerFactory factory(NanoHTTPD.TempFileManagerFactory origin) {
        return () -> {
            Manager manager = new Manager(origin.create());
            LOCAL.set(manager);
            return manager;
        };
    }

    /**
     * 上传开始：记下总量，弹出进度框。
     * 没有前台页面（App 在后台）就不弹，免得跟安装器的界面打架。
     */
    public static void begin(String name, long total) {
        if (total < MIN_SIZE) return; // 小文件一眨眼就收完了，别弹个框闪一下
        Manager manager = LOCAL.get();
        if (manager != null) manager.track = new Track(total);
        App.post(() -> show(name));
    }

    /** 上传收尾：收完了，或者对面传一半断了，都要把进度框收掉 */
    public static void end() {
        Manager manager = LOCAL.get();
        if (manager != null) manager.track = null;
        LOCAL.remove();
        App.post(() -> hide());
    }

    private static void show(String name) {
        Activity activity = App.activity();
        if (!(activity instanceof FragmentActivity)) return;
        if (dialog != null) return;
        dialog = UploadDialog.create();
        dialog.show((FragmentActivity) activity);
        if (name != null && !name.isEmpty()) dialog.setName(name);
    }

    private static void hide() {
        UploadDialog self = dialog;
        dialog = null;
        if (self == null) return;
        try {
            self.dismissAllowingStateLoss();
        } catch (Exception ignored) {
        }
    }

    private static void add(Manager manager, int bytes) {
        Track track = manager.track;
        if (track == null || track.total <= 0) return;
        long done = track.done.addAndGet(bytes);
        long now = System.currentTimeMillis();
        if (now - track.stamp < 150) return; // 每十几毫秒刷一次界面没必要，节流
        track.stamp = now;
        int percent = (int) Math.min(99, done * 100 / track.total);
        UploadDialog self = dialog;
        if (self != null) self.setProgress(percent, done, track.total);
    }

    private static class Track {

        private final long total;
        private final AtomicLong done = new AtomicLong();
        private volatile long stamp;

        private Track(long total) {
            this.total = total;
        }
    }

    private static class Manager implements NanoHTTPD.TempFileManager {

        private final NanoHTTPD.TempFileManager origin;

        private volatile Track track;

        private Manager(NanoHTTPD.TempFileManager origin) {
            this.origin = origin;
        }

        @Override
        public void clear() {
            origin.clear();
        }

        @Override
        public NanoHTTPD.TempFile createTempFile(String fileNameHint) throws Exception {
            return new Temp(origin.createTempFile(fileNameHint), this);
        }
    }

    private static class Temp implements NanoHTTPD.TempFile {

        private final NanoHTTPD.TempFile file;
        private final Manager manager;

        private Temp(NanoHTTPD.TempFile file, Manager manager) {
            this.file = file;
            this.manager = manager;
        }

        @Override
        public void delete() throws Exception {
            file.delete();
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public OutputStream open() throws Exception {
            return new Stream(file.open(), manager);
        }
    }

    private static class Stream extends OutputStream {

        private final OutputStream out;
        private final Manager manager;

        private Stream(OutputStream out, Manager manager) {
            this.out = out;
            this.manager = manager;
        }

        @Override
        public void write(int oneByte) throws IOException {
            out.write(oneByte);
            add(manager, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            add(manager, length);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}

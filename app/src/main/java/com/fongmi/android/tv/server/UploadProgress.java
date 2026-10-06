package com.fongmi.android.tv.server;

import android.app.Activity;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.dialog.UploadDialog;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import fi.iki.elonen.NanoHTTPD;

/**
 * 局域网推送文件 / APK 时，接收这一头的进度。
 *
 * 难点有两个，都踩过：
 * 1. NanoHTTPD 是「要写请求体了才建临时文件」，比 serve() 里调 begin() 晚得多 ——
 *    早先在 begin() 里直接挂账，那时候 Manager 还是空的，于是整个传输过程账一直是空的；
 * 2. 数字节不能靠数写操作 —— 翻过 NanoHTTPD 的代码才知道，它把 multipart 分片落盘时
 *    是自己 new FileOutputStream(临时文件)，压根不走 TempFile.open() 给它的那个输出流，
 *    包装临时文件在这儿一个字节都数不到。
 *
 * 所以现在改用量：这些临时文件一律经我们的 Manager 创建，名字都记着，
 * 每 200 毫秒看一眼它们长到多大 —— 请求体是连续流进来的，文件体积就是已收到的字节数。
 */
public class UploadProgress {

    private static final long MIN_SIZE = 512 * 1024;
    private static final long TICK = 200;

    private static final ThreadLocal<Manager> LOCAL = new ThreadLocal<>();

    /** 本次上传的总字节数。临时文件工厂还没建起来先用 ThreadLocal 存着，建好再挂过去 */
    private static final ThreadLocal<Long> PENDING = new ThreadLocal<>();

    private static UploadDialog dialog;

    /** 套在原本的工厂外面：临时目录的创建规则照旧，只是多留意一下这些文件长多大 */
    public static NanoHTTPD.TempFileManagerFactory factory(NanoHTTPD.TempFileManagerFactory origin) {
        return () -> {
            Manager manager = new Manager(origin.create());
            Long total = PENDING.get();
            if (total != null && total >= MIN_SIZE) {
                Track track = new Track(total);
                track.task = () -> tick(manager);
                manager.track = track;
                App.post(track.task, TICK); // 先等一小会儿，免得上来就是 0% 闪一下
            }
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
        PENDING.set(total);
        Manager manager = LOCAL.get();
        if (manager != null) track(manager, total);
        App.post(() -> show(name));
    }

    /** 上传收尾：收完了，或者对面传一半断了，都要把进度框收掉 */
    public static void end() {
        Manager manager = LOCAL.get();
        if (manager != null && manager.track != null) App.removeCallbacks(manager.track.task);
        if (manager != null) manager.track = null;
        LOCAL.remove();
        PENDING.remove();
        App.post(() -> hide());
    }

    private static void show(String name) {
        Activity activity = App.activity();
        if (!(activity instanceof FragmentActivity)) return;
        if (dialog != null) return;
        dialog = UploadDialog.create(name);
        dialog.show((FragmentActivity) activity);
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

    /**
     * 看看临时文件长到多大了。
     *
     * NanoHTTPD 会先把整个请求体灌进一个「桶」里，拆出来的分片再各自另存一份 ——
     * 所以取这批文件里最大的那个（就是那个一直在长的桶），而不是求和，否则会数成两倍。
     */
    /** 把这次的总量挂到 Manager 上，同时起轮询。重复调用只认第一次 */
    private static void track(Manager manager, long total) {
        if (manager.track != null) return;
        Track track = new Track(total);
        track.task = () -> tick(manager);
        manager.track = track;
        App.post(track.task, TICK); // 稍等一小会儿再看，免得上来就是 0% 闪一下
    }

    private static void tick(Manager manager) {
        Track track = manager.track;
        if (track == null) return;
        long done = 0;
        for (String name : manager.names) {
            File file = new File(name);
            if (file.exists()) done = Math.max(done, file.length());
        }
        UploadDialog self = dialog;
        if (self != null) self.setProgress((int) Math.min(99, done * 100 / track.total), done, track.total);
        App.post(track.task, TICK);
    }

    private static class Track {

        private final long total;
        private Runnable task;

        private Track(long total) {
            this.total = total;
        }
    }

    private static class Manager implements NanoHTTPD.TempFileManager {

        private final NanoHTTPD.TempFileManager origin;
        private final List<String> names = new CopyOnWriteArrayList<>();

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
            NanoHTTPD.TempFile file = origin.createTempFile(fileNameHint);
            names.add(file.getName());
            return file;
        }
    }
}

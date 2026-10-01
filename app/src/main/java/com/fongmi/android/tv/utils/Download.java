package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Future;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Download {

    private static final int PARTIAL = 206;

    private final File file;
    private final String url;
    private Callback callback;
    private OkHttpClient client;
    private Future<?> future;
    private long expect;
    private long maxBytes;
    private long minBytes;
    private boolean verify;
    private String tag;

    public static Download create(String url, File file) {
        return new Download(url, file);
    }

    public Download(String url, File file) {
        this.maxBytes = Long.MAX_VALUE;
        this.tag = url;
        this.url = url;
        this.file = file;
    }

    public Download tag(String tag) {
        this.tag = tag;
        return this;
    }

    public Download maxBytes(long maxBytes) {
        this.maxBytes = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        return this;
    }

    /** 走指定客户端（比如 SOCKS5 线路）下载 */
    public Download client(OkHttpClient client) {
        this.client = client;
        return this;
    }

    /** 预期总大小：给了才知道本地那份半成品有没有用 */
    public Download expect(long expect) {
        this.expect = expect;
        return this;
    }

    /**
     * 校验模式：下完再看一眼内容是不是真东西（APK 必须是 zip 头、不能小于指定体积）。
     * 加速代理时不时会在拿到 200 的同时吐一个报错网页，
     * 不校验的话那个网页会被当成下载成功交给安装器，表现就是「安装包是空的」。
     */
    public Download verify(long minBytes) {
        this.verify = true;
        this.minBytes = minBytes > 0 ? minBytes : 0;
        return this;
    }

    public File get() {
        doInBackground();
        return file;
    }

    public void start(Callback callback) {
        this.callback = callback;
        future = Task.submit(this::doInBackground);
    }

    public Download cancel() {
        if (future != null) future.cancel(true);
        OkHttp.cancel(tag);
        future = null;
        return this;
    }

    /**
     * 已经下了多少字节。续传是有风险的：上次留下的半成品可能是代理吐的报错页、
     * 也可能是别的大小差不多的东西，接着往后面写就装不上了。
     * 所以校验模式下先认一下开头是不是 zip 头，不是就把整个坏文件删掉从头下。
     */
    private long getResume() {
        if (expect <= 0 || file == null || !file.exists()) return 0;
        long done = file.length();
        if (done <= 0 || done >= expect) return 0;
        if (verify && !hasZipHead(file)) {
            Path.clear(file);
            return 0;
        }
        return done;
    }

    private void doInBackground() {
        long offset = getResume();
        boolean bad = false;
        try (Response res = call(offset).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code()); // 404/403 之类的报错页面别当成正文写进去
            boolean partial = res.code() == PARTIAL;
            if (!partial) offset = 0; // 服务端不接受断点，老实从头下
            double remain = getLength(res);
            double total = offset + remain;
            if (total <= 0) total = expect;
            download(res.body().byteStream(), total, offset);
            if (expect > 0 && file.length() < expect) throw new IOException("Download incomplete");
            if (verify) bad = !isPackage(file);
            if (bad) throw new IOException("Bad package");
            if (callback != null) App.post(() -> callback.success(file));
        } catch (Exception e) {
            if (bad || expect <= 0) Path.clear(file); // 坏包不能留着续传；续传模式下的正常半成品才保留
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (callback != null) App.post(() -> callback.error(message));
            else if (!bad && expect > 0) throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 下完后看一眼：APK 本质是 zip，开头必须是 PK\x03\x04，长度也要够。
     * 只看大小不够 —— 代理的报错页有时候正好能凑够长度。
     */
    private boolean isPackage(File file) {
        if (file == null || !file.exists()) return false;
        if (file.length() < minBytes) return false;
        // 只卡「少了」：个别线路会做透明压缩，实际字节数可能比 Content-Length 多，
        // 这种包是好的，不能因为多了几个字节就判死
        if (expect > 0 && file.length() < expect) return false;
        return hasZipHead(file);
    }

    private boolean hasZipHead(File file) {
        try (FileInputStream is = new FileInputStream(file)) {
            return is.read() == 'P' && is.read() == 'K' && is.read() == 3 && is.read() == 4;
        } catch (Exception e) {
            return false;
        }
    }

    private void download(InputStream is, double total, long offset) throws IOException {
        if (total > maxBytes) throw new IOException("Download size limit exceeded");
        boolean append = offset > 0;
        if (!append) Path.create(file);
        try (BufferedInputStream input = new BufferedInputStream(is); FileOutputStream os = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[16384];
            int readBytes;
            long written = offset;
            while ((readBytes = input.read(buffer)) != -1) {
                if (Thread.interrupted()) return;
                written += readBytes;
                os.write(buffer, 0, readBytes);
                if (written > maxBytes) throw new IOException("Download size limit exceeded");
                if (total <= 0) continue;
                int progress = (int) (written / total * 100.0);
                if (callback != null) App.post(() -> callback.progress(progress));
            }
        }
    }

    private okhttp3.Call call(long offset) {
        Request.Builder builder = new Request.Builder().url(url).tag(tag).get();
        if (offset > 0) builder.header(HttpHeaders.RANGE, "bytes=" + offset + "-");
        OkHttpClient use = client != null ? client : OkHttp.client();
        return use.newCall(builder.build());
    }

    private double getLength(Response res) {
        try {
            String header = res.header(HttpHeaders.CONTENT_LENGTH);
            return header != null ? Double.parseDouble(header) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public interface Callback {

        void progress(int progress);

        void error(String msg);

        void success(File file);
    }
}

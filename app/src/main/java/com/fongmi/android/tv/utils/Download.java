package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
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
     * 已经下了多少字节（只有小于预期总大小时才算有效的半成品）
     */
    private long getResume() {
        if (expect <= 0 || file == null || !file.exists()) return 0;
        long done = file.length();
        return done > 0 && done < expect ? done : 0;
    }

    private void doInBackground() {
        long offset = getResume();
        try (Response res = call(offset).execute()) {
            boolean partial = res.code() == PARTIAL;
            if (!partial) offset = 0; // 服务端不接受断点，老实从头下
            double remain = getLength(res);
            double total = offset + remain;
            if (total <= 0) total = expect;
            download(res.body().byteStream(), total, offset);
            if (expect > 0 && file.length() < expect) throw new IOException("Download incomplete");
            if (callback != null) App.post(() -> callback.success(file));
        } catch (Exception e) {
            if (expect <= 0) Path.clear(file); // 续传模式留着半成品，下次能接着下
            if (callback != null) App.post(() -> callback.error(e.getMessage()));
            else throw new RuntimeException(e.getMessage(), e);
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

package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.cast.ScreenStream;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * 屏幕广播的主动拉流端点：{@code GET /screen}。
 *
 * 接收端主动发起 HTTP 连接（跟能通的 probe 一样），本端把编码好的 MPEG-TS 顺着这条
 * 已建立的连接源源不断吐回去 —— 不依赖 AP 的组播 / 客户端隔离设置。
 */
public class Screen implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/screen");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        ScreenStream stream = ScreenStream.get();
        if (stream == null) return Nano.error("screen offline");
        LinkedBlockingQueue<byte[]> queue = stream.subscribe();
        InputStream input = new InputStream() {

            private byte[] chunk;
            private int offset;
            private boolean closed;

            @Override
            public int read() throws IOException {
                while (true) {
                    if (chunk != null && offset < chunk.length) return chunk[offset++] & 0xFF;
                    if (!next()) return -1;
                }
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                if (closed) return -1;
                while (true) {
                    if (chunk != null && offset < chunk.length) {
                        int n = Math.min(len, chunk.length - offset);
                        System.arraycopy(chunk, offset, buffer, off, n);
                        offset += n;
                        return n;
                    }
                    if (!next()) return -1;
                }
            }

            private boolean next() throws IOException {
                chunk = null;
                offset = 0;
                try {
                    chunk = stream.take(queue, 15_000L);
                } catch (Throwable e) {
                    throw new IOException(e);
                }
                if (chunk == null) {
                    close();
                    return false;
                }
                return true;
            }

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                stream.unsubscribe(queue);
            }
        };
        Response response = Nano.stream(Response.Status.OK, "video/mp2t", input);
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "close");
        return response;
    }
}

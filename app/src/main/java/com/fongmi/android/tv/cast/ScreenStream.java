package com.fongmi.android.tv.cast;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 屏幕广播的「HTTP 拉流」分发器（不依赖 AP 的组播能力）。
 *
 * 为什么需要它：组播 / 无状态单播在很多家用 AP（开了 AP 隔离、IGMP Snooping、
 * 或者 mesh 跨节点）上根本到不了，而「接收端主动发起的 HTTP 连接」跟现在能通的
 * probe 一样，是接收端先出门、数据再顺着原路回来 —— 这条路 AP 一般不拦。
 *
 * 设计要点：
 * - 编码器把 TS 字节 {@link #publish(byte[], int)} 进来，这里复制给每个正在拉的客户端；
 * - 每个客户端一条独立的阻塞队列（{@link #subscribe()}），互不拖累，慢客户端只丢自己的；
 * - 为了让「中途才连上」的接收端也能立刻出画，订阅那一刻先发一个节目表请求，
 *   由编码线程在下一帧前补一份 PAT/PMT（见 {@link CastMux#header()}）。
 */
public final class ScreenStream {

    /** 单个客户端最多缓存多少块；多了说明它根本拉不动，直接丢旧块，别把内存撑爆 */
    private static final int PER_CLIENT = 200;

    private static volatile ScreenStream current;

    private final java.util.Set<LinkedBlockingQueue<byte[]>> clients =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final Runnable onSubscribe;

    /** 屏幕广播在播的那一路；HTTP 端点靠它取流。没在播就是 null */
    public static ScreenStream get() {
        return current;
    }

    public static void set(ScreenStream stream) {
        ScreenStream old = current;
        current = stream;
        if (old != null && old != stream) old.close();
    }

    /** @param onSubscribe 有新客户端时回调：编码线程据此补一次 PAT/PMT */
    public ScreenStream(Runnable onSubscribe) {
        this.onSubscribe = onSubscribe == null ? () -> {
        } : onSubscribe;
    }

    /** 新开一个拉流口，返回这个客户端专属的队列 */
    public LinkedBlockingQueue<byte[]> subscribe() {
        LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(PER_CLIENT);
        clients.add(queue);
        try {
            onSubscribe.run();
        } catch (Throwable ignored) {
        }
        return queue;
    }

    public void unsubscribe(LinkedBlockingQueue<byte[]> queue) {
        if (queue != null) clients.remove(queue);
    }

    /** 编码好的一段 TS：复制给所有客户端；谁队列满了就只丢谁那一份 */
    public void publish(byte[] data, int len) {
        if (data == null || len <= 0 || clients.isEmpty()) return;
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);
        for (LinkedBlockingQueue<byte[]> queue : clients) {
            queue.offer(copy);
        }
    }

    /** 阻塞等下一块 TS；客户端断开（被移除）时返回 null，让 HTTP 线程收线 */
    public byte[] take(LinkedBlockingQueue<byte[]> queue, long timeoutMs) {
        if (queue == null) return null;
        try {
            byte[] data = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (data == null && !clients.contains(queue)) return null;
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void close() {
        clients.clear();
    }
}

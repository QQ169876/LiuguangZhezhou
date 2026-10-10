package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 刷剧兴趣画像：题材词的正/负反馈计数 + 时间衰减，本机持久化，随刷随学。
 * 正反馈 = 一部剧自然看完（完播是红果评分里最大的信号，占 35%）；
 * 负反馈 = 没看完就手动点「下一部」，一次抵两分（弃看是强负信号）。
 * 权重按半衰期 14 天衰减——照「近 7 天行为权重约为 30 天前的 3.2 倍」反推 2^(23/H)=3.2 得 H≈13.7。
 * 画像只影响刷剧选哪个题材族去搜，不碰播放本身；文件坏了、丢了，全当没有画像。
 */
public class BingeProfile {

    /** 兴趣半衰期 14 天 */
    private static final long HALF_LIFE_MS = 14L * 24 * 3600 * 1000;
    /** 弃看一次的惩罚权重 */
    private static final double NEG_WEIGHT = 2.0;
    /** 画像里最多记多少个题材词，超了清掉重来（词典一共也就一百多个） */
    private static final int LIMIT = 300;

    private static final Map<String, Entry> MAP = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private static class Entry {
        int pos;
        int neg;
        long last;
    }

    /** 看完一部：它命中的题材词各记一分 */
    public static synchronized void hit(Iterable<String> tags) {
        if (tags == null) return;
        load();
        for (String tag : tags) {
            Entry entry = MAP.computeIfAbsent(tag, k -> new Entry());
            entry.pos++;
            entry.last = System.currentTimeMillis();
        }
        save();
    }

    /** 没看完就换下一部：它命中的题材词各记一次负反馈 */
    public static synchronized void miss(Iterable<String> tags) {
        if (tags == null) return;
        load();
        for (String tag : tags) {
            Entry entry = MAP.computeIfAbsent(tag, k -> new Entry());
            entry.neg++;
            entry.last = System.currentTimeMillis();
        }
        save();
    }

    /** 题材词当前的兴趣权重：没记过是 0，负反馈攒多了也压到 0 */
    public static double weight(String tag) {
        load();
        Entry entry = MAP.get(tag);
        if (entry == null) return 0;
        return scoreOf(entry.pos, entry.neg, entry.last, System.currentTimeMillis());
    }

    /** 纯函数，方便单测：基数 × 半衰期衰减 */
    public static double scoreOf(int pos, int neg, long lastMs, long nowMs) {
        double base = pos - NEG_WEIGHT * neg;
        if (base <= 0) return 0;
        double periods = Math.max(0, nowMs - lastMs) / (double) HALF_LIFE_MS;
        return base * Math.pow(0.5, periods);
    }

    /** 画像累计完播数：喂给探索比例的指数衰减，刷得越久越不折腾 */
    public static int totalPos() {
        load();
        int total = 0;
        for (Entry entry : MAP.values()) total += entry.pos;
        return total;
    }

    /** 一族的兴趣权重：族里所有题材词的权重之和 */
    public static double familyWeight(int family) {
        if (family < 0 || family >= BingeTags.FAMILIES.length) return 0;
        double weight = 0;
        for (String word : BingeTags.FAMILIES[family]) weight += weight(word);
        return weight;
    }

    /** 画像里最热、且最近没刷过的族；画像还是一张白纸返回 -1 */
    public static int hottestFamily(Set<Integer> excluded) {
        int best = -1;
        double bestWeight = 0;
        for (int i = 0; i < BingeTags.FAMILIES.length; i++) {
            if (excluded != null && excluded.contains(i)) continue;
            double weight = familyWeight(i);
            if (weight > bestWeight) {
                bestWeight = weight;
                best = i;
            }
        }
        return best;
    }

    /**
     * 动态漫是不是他的菜。画像里漫剧权重够高（看完过漫剧，且是最爱之一或占了不小份额）就照推；
     * 一部漫剧都没看完过、或者只是偶尔看一部 → 算「没有基础数据」，按马先生的意思压到最后。
     * 权重带 14 天半衰期：很久不看漫剧，这个偏好会自己淡回去，又变回"没数据"。
     */
    public static boolean comicPreferred() {
        load();
        double comicWeight = familyWeight(BingeTags.comicFamily());
        if (comicWeight <= 0) return false; // 一部漫剧都没看完过：没有任何基础数据
        double total = 0;
        double max = 0;
        for (int i = 0; i < BingeTags.FAMILIES.length; i++) {
            double weight = familyWeight(i);
            total += weight;
            if (weight > max) max = weight;
        }
        return BingeTags.comicPreferredOf(comicWeight, max, total);
    }

    /**
     * 探索用：有正权重的族里挑最冷、且最近没刷过的（冷的才值得试探）；
     * 画像还没有任何正权重族，就随机挑一族开开眼界。
     */
    public static int coldestFamily(Set<Integer> excluded, Random random) {
        int coldest = -1;
        double coldestWeight = Double.MAX_VALUE;
        for (int i = 0; i < BingeTags.FAMILIES.length; i++) {
            if (excluded != null && excluded.contains(i)) continue;
            double weight = familyWeight(i);
            if (weight > 0 && weight < coldestWeight) {
                coldestWeight = weight;
                coldest = i;
            }
        }
        if (coldest >= 0) return coldest;
        int count = BingeTags.FAMILIES.length;
        int start = (random == null ? new Random() : random).nextInt(count);
        for (int i = 0; i < count; i++) {
            int family = (start + i) % count;
            if (excluded == null || !excluded.contains(family)) return family;
        }
        return start;
    }

    private static void load() {
        if (loaded) return;
        synchronized (BingeProfile.class) {
            if (loaded) return;
            try {
                File file = file();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            JSONObject item = json.optJSONObject(key);
                            if (item == null) continue;
                            Entry entry = new Entry();
                            entry.pos = item.optInt("p", 0);
                            entry.neg = item.optInt("n", 0);
                            entry.last = item.optLong("t", 0);
                            MAP.put(key, entry);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    private static synchronized void save() {
        try {
            if (MAP.size() > LIMIT) MAP.clear(); // 画像膨胀过头就清掉重来，刷几天又攒回来了
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Entry> item : MAP.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("p", item.getValue().pos);
                obj.put("n", item.getValue().neg);
                obj.put("t", item.getValue().last);
                json.put(item.getKey(), obj);
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), "binge_profile.json");
    }
}

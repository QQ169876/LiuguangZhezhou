package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DebugLog;
import com.github.catvod.utils.Asset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 「随便听听」的曲库。
 *
 * 两条红线（马先生定的，谁动这个文件都得先看明白）：
 *
 * 1. 曲库里只有歌本身的信息（歌名/歌手/平台/取址用的 ID），**没有任何账号、地址、密码**，
 *    界面上也不会出现「来自某某账号」这类字样。
 * 2. 在这个模式里收藏的歌，只写到本机 random_love.json，**永远不参与同步**。
 *    同步只认用户在设置里自己填的那个服务器和密钥（MusicSetting），跟这份曲库没关系，
 *    免得收藏一不小心写回别人的账号里。
 *
 * 曲库有两个来源，谁有数据用谁：
 * - 缓存（files/music/random_cache.json）：每次点「随便听听」时，从本机已同步下来的歌单重抄一份。
 *   用户在自己洛雪里增删了歌，这边跟着变，不用改包。
 * - 内置（assets/music/random.json）：没配同步、或同步下来的歌单是空的时候兜底，保证免登录也能听。
 *
 * 注意：缓存是从「用户自己服务器上同步下来的那份歌单」抄的，抄完就只留在本机，
 * 不往外发；同步程序（LxSync）只认 MusicStore 的 list.json，压根不读这两个文件。
 */
public class MusicRandom {

    private static final String ASSET = "music/random.json";
    private static final String LOVE = "random_love.json";
    private static final String CACHE = "random_cache.json";
    private static final int SPARE_MAX = 100;   // 应急歌曲上限，别把「随便听听」变成半个歌单
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static List<Music> builtIn;
    private static List<Music> favorite;
    private static List<Music> cache;

    private static File file(String name) {
        File dir = new File(App.get().getFilesDir(), "music");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    /** 包里自带的那份曲库，读一次就缓存住 */
    private static synchronized List<Music> builtIn() {
        if (builtIn != null) return builtIn;
        try {
            List<Music> list = GSON.fromJson(Asset.read(ASSET), new TypeToken<List<Music>>() {
            }.getType());
            builtIn = list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            builtIn = new ArrayList<>();
        }
        return builtIn;
    }

    /** 上次刷新下来的曲库 */
    private static synchronized List<Music> cache() {
        if (cache != null) return cache;
        try {
            File file = file(CACHE);
            if (!file.exists()) return new ArrayList<>();
            List<Music> list = GSON.fromJson(new FileReader(file), new TypeToken<List<Music>>() {
            }.getType());
            cache = list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            cache = new ArrayList<>();
        }
        return cache;
    }

    /** 本机收藏（不同步，区别于 MusicStore 里那份会随着同步走的收藏） */
    private static synchronized List<Music> favorite() {
        if (favorite != null) return favorite;
        try {
            File file = file(LOVE);
            if (!file.exists()) {
                favorite = new ArrayList<>();
                return favorite;
            }
            List<Music> list = GSON.fromJson(new FileReader(file), new TypeToken<List<Music>>() {
            }.getType());
            favorite = list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            favorite = new ArrayList<>();
        }
        return favorite;
    }

    private static synchronized void save(String name, List<Music> list) {
        try {
            File file = file(name);
            FileWriter writer = new FileWriter(file, false);
            GSON.toJson(list, writer);
            writer.flush();
            writer.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 刷新曲库：把本机同步下来的「我喜欢的」那份歌单抄过来。
     *
     * 注意口径（马先生 2026-10-05 定的）：要的是洛雪里**从腾讯分享/导入生成**的那个歌单
     * （同名自建歌单，411 首那份，会随着腾讯那边更新而变），
     * **不是洛雪自己的收藏列表 loveList**（只有几首那份）——那个是另一回事，别混进来。
     *
     * 找不到「我喜欢的」这份（比如还没同步过来、或者改了名），才退回收藏列表兜底；
     * 同步数据为空时不动缓存，免得把上次刷出来那份冲掉。
     */
    public static synchronized int refresh() {
        List<Music> pool = new ArrayList<>();
        try {
            MusicList data = MusicStore.get();
            for (MusicList.UserList item : data.getUserList()) {
                if (item.getName().contains("我喜欢")) addAll(pool, item.getList());
            }
            if (pool.isEmpty()) addAll(pool, data.getLoveList());
        } catch (Exception e) {
            DebugLog.d("MusicRandom", "刷新曲库失败 " + e);
            return 0;
        }
        if (pool.isEmpty()) {
            DebugLog.d("MusicRandom", "本机歌单是空的，沿用上次那份 " + cache().size() + " 首");
            return 0;
        }
        cache = pool;
        save(CACHE, cache);
        DebugLog.d("MusicRandom", "曲库已刷新 " + cache.size() + " 首");
        return cache.size();
    }

    /** 曲库：有缓存用缓存，没缓存用包里自带那份 */
    private static synchronized List<Music> pool() {
        List<Music> list = cache();
        return list.isEmpty() ? builtIn() : list;
    }

    /** 随便听听的曲库：本机收藏排在前面，后面接曲库，去重 */
    public static synchronized List<Music> songs() {
        List<Music> result = new ArrayList<>();
        for (Music item : favorite()) if (in(result, item) == null) result.add(item);
        for (Music item : pool()) if (in(result, item) == null) result.add(item);
        return result;
    }

    /** 刷新 + 取曲库，点「随便听听」走这个 */
    public static synchronized List<Music> fresh() {
        refresh();
        List<Music> list = songs();
        if (!MusicSource.scriptPaused()) return list;
        for (Music item : list) if (MusicDirect.support(item)) return list;
        // 脚本那条路已经确认不通（比如 IP 被限），而这份歌单又全是要走脚本的源：
        // 从本机同步歌单里捞几首能自取的挂到前面，别让「随便听听」变成「随便跳曲」
        List<Music> spare = spare();
        if (spare.isEmpty()) return list;
        spare.addAll(list);
        DebugLog.d("MusicRandom", "脚本音源不可用，先播能从内置接口取的 " + spare.size() + " 首");
        return spare;
    }

    /** 本机同步歌单里、不走第三方也能放的歌（最多 SPARE_MAX 首） */
    public static synchronized List<Music> spare() {
        List<Music> out = new ArrayList<>();
        try {
            MusicList data = MusicStore.get();
            addPlayable(out, data.getLoveList());
            addPlayable(out, data.getDefaultList());
            for (MusicList.UserList item : data.getUserList()) {
                addPlayable(out, item.getList());
                if (out.size() >= SPARE_MAX) break;
            }
        } catch (Exception e) {
            return out;
        }
        return out.size() > SPARE_MAX ? new ArrayList<>(out.subList(0, SPARE_MAX)) : out;
    }

    private static void addPlayable(List<Music> target, List<Music> source) {
        if (source == null || target.size() >= SPARE_MAX) return;
        for (Music item : source) {
            if (target.size() >= SPARE_MAX) return;
            if (item == null || item.getId().isEmpty()) continue;
            if (!MusicDirect.support(item)) continue;
            if (in(target, item) == null) target.add(item);
        }
    }

    public static synchronized boolean loved(Music music) {
        return music != null && in(favorite(), music) != null;
    }

    /** 收藏 / 取消收藏，只落本机。返回操作后的状态 */
    public static synchronized boolean toggleLove(Music music) {
        if (music == null) return false;
        Music exist = in(favorite(), music);
        if (exist != null) {
            favorite.remove(exist);
            save(LOVE, favorite);
            return false;
        }
        favorite.add(0, music);
        save(LOVE, favorite);
        return true;
    }

    private static void addAll(List<Music> target, List<Music> source) {
        if (source == null) return;
        for (Music item : source) {
            if (item == null || item.getId().isEmpty()) continue;
            if (in(target, item) == null) target.add(item);
        }
    }

    private static Music in(List<Music> list, Music music) {
        for (Music item : list) if (item.isSame(music)) return item;
        return null;
    }
}

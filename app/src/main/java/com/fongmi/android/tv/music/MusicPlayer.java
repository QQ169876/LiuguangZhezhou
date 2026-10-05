package com.fongmi.android.tv.music;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音乐播放器：ExoPlayer 单实例，带一个播放列表和播放模式。
 * 视频播放用的是同一套内核，这里只做音乐那点控制逻辑。
 */
public class MusicPlayer {

    public interface Listener {
        void onChanged(Music music, boolean playing);

        void onProgress(long position, long duration);
    }

    private static volatile MusicPlayer instance;

    private final List<Listener> listeners;
    private final Handler handler;

    private ExoPlayer player;
    private List<Music> playlist;
    private List<Integer> order;   // 实际播放顺序，随机时不再等于列表顺序
    private int cursor;            // order 里的位置（不是列表里的位置）
    private boolean shuffle;
    private boolean random;        // 是不是「随便听听」模式：此模式下收藏只落本机
    private static final int FAIL_TO_SPARE = 3;   // 一首都放不出来到这个数，就把应急歌曲接进来
    private int skip;              // 连续有几首取不到地址，连跳几次就收手

    /** 连跳上限：一首不行自动换下一首，跳这么多还不行就提示用户 */
    private static final int MAX_SKIP = 6;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) notifyProgress();
            handler.postDelayed(this, 500);
        }
    };

    public static MusicPlayer get() {
        if (instance == null) {
            synchronized (MusicPlayer.class) {
                if (instance == null) instance = new MusicPlayer();
            }
        }
        return instance;
    }

    private MusicPlayer() {
        this.listeners = new CopyOnWriteArrayList<>();
        this.playlist = new ArrayList<>();
        this.order = new ArrayList<>();
        this.handler = new Handler(Looper.getMainLooper());
        this.shuffle = MusicSetting.isShuffle();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private ExoPlayer player() {
        if (player == null) {
            // 必须换 OkHttp 的 DataSource：默认 DefaultHttpDataSource 不跟跨协议 302，
            // 酷我 anti.s 是 http 跳 https，会被当成 "Response code: 302" 错误直接起播失败。
            // 视频那边（ExoMediaSourceFactory）也是同一套 OkHttpDataSource。
            player = new ExoPlayer.Builder(App.get())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new OkHttpDataSource.Factory(OkHttp.player())))
                    .build();
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) next();
                    notifyChanged();
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    Music music = current();
                    DebugLog.d("MusicPlayer", (playing ? "开始播放 " : "暂停 ") + (music == null ? "" : music.getName()));
                    notifyChanged();
                    if (playing) handler.removeCallbacks(tick);
                    handler.post(tick);
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    DebugLog.d("MusicPlayer", "播放出错 " + error.getMessage());
                    onFail("播放出错");
                }
            });
        }
        return player;
    }

    /** 按顺序建索引：0,1,2... */
    private List<Integer> sequence(int size) {
        List<Integer> indices = new ArrayList<>(Math.max(size, 0));
        for (int i = 0; i < size; i++) indices.add(i);
        return indices;
    }

    /** 当前播的是列表里的第几首，没有就 -1 */
    private int currentIndex() {
        if (order.isEmpty() || cursor < 0 || cursor >= order.size()) return -1;
        int index = order.get(cursor);
        return index >= 0 && index < playlist.size() ? index : -1;
    }

    public void setPlaylist(List<Music> list, int position) {
        random = false;
        applyPlaylist(list, position);
    }

    /** 单曲直接播（点了歌单里某一首） */
    public void play(List<Music> list, int position) {
        setPlaylist(list, position);
    }

    /** 「随便听听」：整张列表随机排一遍再播，不要求登录 */
    public void playRandom(List<Music> list) {
        random = true;
        applyPlaylist(list, pickStart(list), true);
    }

    /**
     * 从哪首开始放。脚本音源那头正被挂着（连着失败过）的时候，就从能自取的
     * 那批歌（网易）里挑一首起头，别让用户一进来先听半天跳曲。
     */
    private int pickStart(List<Music> list) {
        int size = list == null ? 0 : list.size();
        if (size == 0) return 0;
        if (!MusicSource.scriptPaused()) return (int) (Math.random() * size);
        List<Integer> playable = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (MusicDirect.support(list.get(i))) playable.add(i);
        }
        if (playable.isEmpty()) return (int) (Math.random() * size);
        return playable.get((int) (Math.random() * playable.size()));
    }

    private void applyPlaylist(List<Music> list, int position) {
        applyPlaylist(list, position, shuffle);
    }

    private void applyPlaylist(List<Music> list, int position, boolean shuffled) {
        playlist = list == null ? new ArrayList<>() : new ArrayList<>(list);
        if (playlist.isEmpty()) {
            order = new ArrayList<>();
            cursor = 0;
            return;
        }
        int base = Math.max(0, Math.min(position, playlist.size() - 1));
        List<Integer> indices = sequence(playlist.size());
        if (shuffled) {
            indices.remove(Integer.valueOf(base));
            java.util.Collections.shuffle(indices);
            indices.add(0, base);   // 手点的这首先播，后面的随机走
        }
        order = indices;
        cursor = shuffled ? 0 : base;
        skip = 0;
        start();
    }

    /** 随机开关（记进设置，下次进来还是这个）。切完当前这首不会跳走 */
    public void setShuffle(boolean value) {
        this.shuffle = value;
        MusicSetting.putShuffle(value);
        rebuild();
    }

    public boolean isShuffle() {
        return shuffle;
    }

    /** 当前是不是「随便听听」在播：决定收藏写哪里 */
    public boolean isRandomMode() {
        return random;
    }

    private void rebuild() {
        int size = playlist.size();
        if (size == 0) {
            order = new ArrayList<>();
            cursor = 0;
            return;
        }
        int base = currentIndex();
        List<Integer> indices = sequence(size);
        if (shuffle) {
            int head = base < 0 ? indices.get((int) (Math.random() * size)) : base;
            indices.remove(Integer.valueOf(head));
            java.util.Collections.shuffle(indices);
            indices.add(0, head);
        }
        order = indices;
        cursor = shuffle ? 0 : Math.max(0, base);
    }

    private void start() {
        App.post(() -> {
            try {
                Music music = current();
                if (music == null) return;
                String url = MusicSource.url(music);
                if (!url.startsWith("http")) {
                    // 取不到地址：别停在那儿装死，自动跳下一首（随便听听里源杂，可播性靠这个兜住）
                    onFail("取不到地址");
                    return;
                }
                skip = 0;
                player().stop();
                player().clearMediaItems();
                player().setMediaItem(MediaItem.fromUri(url));
                player().prepare();
                player().play();
                notifyChanged();
            } catch (Throwable e) {
                DebugLog.d("MusicPlayer", "起播失败 " + e);
                onFail("起播失败");
            }
        });
    }

    /**
     * 下一首跳哪儿。连着失败两首以上时，优先挑一首能自己取地址的（wy/kw 是内置算法，
     * 不依赖第三方服务器）——池子里要是绝大部分都得走脚本、而脚本那头被限了，
     * 光按随机顺序跳基本跳不出一首能响的，这条兜底就是为这个准备的。
     */
    /**
     * 脚本那条路确认不通、而池子里又没有能自取的歌时，把本机同步歌单里
     * 那批不依赖第三方的歌接到当前播放列表后面。列表内容（曲库口径）不动，
     * 只是这一轮先保证有歌能响。
     */
    private void ensureSpare() {
        for (Music item : playlist) if (MusicDirect.support(item)) return;
        List<Music> spare = MusicRandom.spare();
        if (spare.isEmpty()) return;
        int from = playlist.size();
        playlist.addAll(spare);
        List<Integer> added = new ArrayList<>();
        for (int i = from; i < playlist.size(); i++) added.add(i);
        java.util.Collections.shuffle(added);
        order.addAll(Math.min(cursor + 1, order.size()), added);
        DebugLog.d("MusicPlayer", "接入应急歌曲 " + spare.size() + " 首");
    }

    private int nextIndex() {
        int size = order.size();
        if (size <= 1) return 0;
        if (skip >= 2) {
            for (int i = 1; i <= size; i++) {
                int pos = (cursor + i) % size;
                Music item = at(pos);
                if (MusicDirect.support(item)) return pos;
            }
        }
        return (cursor + 1) % size;
    }

    private Music at(int pos) {
        if (pos < 0 || pos >= order.size()) return null;
        int index = order.get(pos);
        return index >= 0 && index < playlist.size() ? playlist.get(index) : null;
    }

    /** 这一首不行：连着跳几首都还不行才告诉用户，免得播一首报一次 */
    private void onFail(String reason) {
        Music music = current();
        DebugLog.d("MusicPlayer", "播放失败 " + reason + " " + (music == null ? "" : music.getName()));
        skip++;
        if (random && skip >= FAIL_TO_SPARE) ensureSpare();   // 脚本不通时把能放的那批歌接进来
        notifyChanged();   // 让播放页那行状态跟着变，别停在「正在取地址」
        if (skip <= MAX_SKIP && order.size() > 1) {
            cursor = nextIndex();
            start();
            return;
        }
        skip = 0;
        Notify.show(R.string.music_no_url);
        notifyChanged();
    }

    public void toggle() {
        App.post(() -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.play();
        });
    }

    public void next() {
        if (order.isEmpty()) return;
        skip = 0;
        cursor = (cursor + 1) % order.size();
        start();
    }

    /** 重试当前这首：取不到地址时点播放页那行状态就走这儿 */
    public void retry() {
        if (playlist.isEmpty()) return;
        skip = 0;
        start();
    }

    public void prev() {
        if (order.isEmpty()) return;
        skip = 0;
        cursor = (cursor - 1 + order.size()) % order.size();
        start();
    }

    public void seek(long position) {
        App.post(() -> {
            if (player != null) player.seekTo(position);
        });
    }

    public void stop() {
        App.post(() -> {
            if (player != null) player.stop();
            notifyChanged();
        });
    }

    public void release() {
        App.post(() -> {
            handler.removeCallbacks(tick);
            if (player != null) {
                player.release();
                player = null;
            }
        });
        listeners.clear();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public Music current() {
        int index = currentIndex();
        return index < 0 ? null : playlist.get(index);
    }

    public int getIndex() {
        return Math.max(currentIndex(), 0);
    }

    public long position() {
        return player == null ? 0 : player.getCurrentPosition();
    }

    public long duration() {
        return player == null ? 0 : player.getDuration();
    }

    public boolean has(Music music) {
        return music != null && current() != null && current().isSame(music);
    }

    private void notifyChanged() {
        Music music = current();
        boolean playing = isPlaying();
        App.post(() -> {
            for (Listener listener : listeners) listener.onChanged(music, playing);
        });
    }

    private void notifyProgress() {
        long position = position();
        long duration = duration();
        App.post(() -> {
            for (Listener listener : listeners) listener.onProgress(position, duration);
        });
    }
}

package com.fongmi.android.tv.music;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DebugLog;

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
    private int index;
    private boolean single;

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
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private ExoPlayer player() {
        if (player == null) {
            player = new ExoPlayer.Builder(App.get()).build();
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) next();
                    notifyChanged();
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    notifyChanged();
                    if (playing) handler.removeCallbacks(tick);
                    handler.post(tick);
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    DebugLog.d("MusicPlayer", "播放出错 " + error.getMessage());
                    notifyChanged();
                }
            });
        }
        return player;
    }

    public void setPlaylist(List<Music> list, int position) {
        this.playlist = list == null ? new ArrayList<>() : new ArrayList<>(list);
        this.index = Math.max(0, Math.min(position, this.playlist.size() - 1));
        start();
    }

    /** 单曲直接播（点了歌单里某一首） */
    public void play(List<Music> list, int position) {
        single = false;
        setPlaylist(list, position);
    }

    private void start() {
        App.post(() -> {
            try {
                Music music = current();
                if (music == null) return;
                player().stop();
                player().clearMediaItems();
                player().setMediaItem(MediaItem.fromUri(MusicSource.url(music)));
                player().prepare();
                player().play();
                notifyChanged();
            } catch (Throwable e) {
                DebugLog.d("MusicPlayer", "起播失败 " + e);
            }
        });
    }

    public void toggle() {
        App.post(() -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.play();
        });
    }

    public void next() {
        if (playlist.isEmpty()) return;
        index = (index + 1) % playlist.size();
        start();
    }

    public void prev() {
        if (playlist.isEmpty()) return;
        index = (index - 1 + playlist.size()) % playlist.size();
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
        if (playlist.isEmpty()) return null;
        if (index < 0 || index >= playlist.size()) return null;
        return playlist.get(index);
    }

    public int getIndex() {
        return index;
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

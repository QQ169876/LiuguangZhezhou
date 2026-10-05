package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMusicPlayBinding;
import com.fongmi.android.tv.music.Lrc;
import com.fongmi.android.tv.music.Music;
import com.fongmi.android.tv.music.MusicPlayer;
import com.fongmi.android.tv.music.MusicRandom;
import com.fongmi.android.tv.music.MusicSource;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.adapter.LrcAdapter;
import com.fongmi.android.tv.utils.Notify;

/**
 * 播放页：封面 + 滚动歌词 + 播放控制。TV 和手机共用。
 */
public class MusicPlayActivity extends AppCompatActivity implements MusicPlayer.Listener {

    private ActivityMusicPlayBinding binding;
    private LrcAdapter adapter;
    private Music current;
    private Lrc lrc;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMusicPlayBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        adapter = new LrcAdapter();
        binding.musicLrc.setAdapter(adapter);
        initEvent();
        refreshShuffle();
    }

    private void initEvent() {
        binding.musicPlay.setOnClickListener(v -> MusicPlayer.get().toggle());
        binding.musicNext.setOnClickListener(v -> MusicPlayer.get().next());
        binding.musicPrev.setOnClickListener(v -> MusicPlayer.get().prev());
        binding.musicLove.setOnClickListener(v -> toggleLove());
        // 状态行也能点：取不到地址时点一下重新取一次
        binding.musicStatus.setOnClickListener(v -> {
            MusicPlayer.get().retry();
            Notify.show(R.string.music_status_retry);
        });
        binding.musicShuffle.setOnClickListener(v -> {
            MusicPlayer.get().setShuffle(!MusicPlayer.get().isShuffle());
            refreshShuffle();
            Notify.show(MusicPlayer.get().isShuffle() ? R.string.music_shuffle_on : R.string.music_shuffle_off);
        });
        binding.musicSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) MusicPlayer.get().seek(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void toggleLove() {
        if (current == null) return;
        // 随便听听里收藏的歌只留在本机，绝不进用户的同步歌单
        if (MusicPlayer.get().isRandomMode()) {
            boolean loved = MusicRandom.toggleLove(current);
            Notify.show(loved ? R.string.music_random_loved_toast : R.string.music_random_unloved_toast);
        } else {
            boolean loved = MusicStore.toggleLove(current);
            Notify.show(loved ? R.string.music_loved_toast : R.string.music_unloved_toast);
        }
        refreshLove();
    }

    private void refreshLove() {
        if (current == null) return;
        boolean loved = MusicPlayer.get().isRandomMode() ? MusicRandom.loved(current) : MusicStore.loved(current);
        binding.musicLove.setImageResource(loved ? R.drawable.ic_music_love_on : R.drawable.ic_music_love);
    }

    /** 歌词下面那行：这首歌的地址是从哪条路取来的，取不到时把原因也带上 */
    private void refreshStatus() {
        if (MusicPlayer.get().current() == null) {
            binding.musicStatus.setText("");
            return;
        }
        String note = MusicSource.getNote();
        String text;
        switch (MusicSource.getRoute()) {
            case MusicSource.JX -> text = getString(R.string.music_status_jx);
            case MusicSource.DIRECT -> text = getString(R.string.music_status_direct);
            case MusicSource.SCRIPT -> text = getString(R.string.music_status_script);
            default -> {
                text = getString(R.string.music_status_fail);
                if (note != null && !note.isEmpty()) text = text + " · " + note;
            }
        }
        binding.musicStatus.setText(text);
    }

    private void refreshShuffle() {
        boolean on = MusicPlayer.get().isShuffle();
        binding.musicShuffle.setImageResource(R.drawable.ic_music_shuffle);
        binding.musicShuffle.setColorFilter(on ? getResources().getColor(R.color.blue_secondary) : getResources().getColor(R.color.white));
        binding.musicShuffle.setAlpha(on ? 1f : 0.5f);
    }

    private void bind(Music music) {
        current = music;
        if (music == null) return;
        binding.musicName.setText(music.getName());
        binding.musicSinger.setText(music.getSinger());
        if (music.getPicUrl().startsWith("http")) {
            Glide.with(this).load(music.getPicUrl()).placeholder(R.drawable.ic_music_note).into(binding.musicCover);
        }
        binding.musicMode.setVisibility(MusicPlayer.get().isRandomMode() ? View.VISIBLE : View.GONE);
        binding.musicStatus.setText(MusicPlayer.get().current() == null ? "" : getString(R.string.music_status_fetching));
        refreshLove();
        adapter.setLrc(Lrc.parse(""));
        lrc = Lrc.parse("");
        new Thread(() -> {
            Lrc result = Lrc.parse(MusicSource.lyric(music));
            App.post(() -> {
                lrc = result;
                adapter.setLrc(result);
            });
        }, "lx-lrc").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicPlayer.get().addListener(this);
        Music music = MusicPlayer.get().current();
        if (music != null && !music.isSame(current)) bind(music);
        else if (music == null) finish();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        MusicPlayer.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onChanged(Music music, boolean playing) {
        if (music != null && !music.isSame(current)) bind(music);
        binding.musicPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        refreshStatus();
    }

    @Override
    public void onProgress(long position, long duration) {
        if (duration <= 0) return;
        binding.musicSeek.setMax((int) duration);
        binding.musicSeek.setProgress((int) position);
        binding.musicNow.setText(format(position));
        binding.musicTotal.setText(format(duration));
        int index = lrc == null ? -1 : lrc.index(position);
        adapter.setCurrent(index);
        if (index >= 0) binding.musicLrc.scrollToPosition(index);
    }

    private String format(long ms) {
        long total = Math.max(ms, 0) / 1000;
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}

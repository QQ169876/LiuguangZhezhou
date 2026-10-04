package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.music.LxSync;
import com.fongmi.android.tv.music.Music;
import com.fongmi.android.tv.music.MusicApi;
import com.fongmi.android.tv.music.MusicPlayer;
import com.fongmi.android.tv.music.MusicSetting;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.adapter.MusicGroupAdapter;
import com.fongmi.android.tv.ui.adapter.MusicSongAdapter;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 音乐主页：左边歌单，右边歌曲。TV 和手机共用这一套，靠焦点和触摸都能操作。
 */
public class MusicActivity extends AppCompatActivity implements MusicPlayer.Listener {

    private ActivityMusicBinding binding;
    private MusicGroupAdapter groupAdapter;
    private MusicSongAdapter songAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMusicBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        initView();
        initEvent();
        new Thread(() -> MusicApi.get().init(), "lx-api").start();
    }

    private void initView() {
        groupAdapter = new MusicGroupAdapter(this::onGroupClick);
        songAdapter = new MusicSongAdapter(this::onSongClick);
        binding.musicList.setAdapter(groupAdapter);
        binding.musicSong.setAdapter(songAdapter);
        reload();
    }

    private void initEvent() {
        binding.musicSync.setOnClickListener(v -> doSync());
        binding.musicSetting.setOnClickListener(v -> showSetting());
        binding.musicBar.setOnClickListener(v -> MusicPlayActivity.start(this));
        binding.musicBarPlay.setOnClickListener(v -> MusicPlayer.get().toggle());
    }

    private void reload() {
        List<MusicStore.Group> groups = MusicStore.groups();
        groupAdapter.addAll(groups);
        if (groups.isEmpty()) {
            binding.musicEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.musicEmpty.setVisibility(View.GONE);
        onGroupClick(groups.get(0));
    }

    private void onGroupClick(MusicStore.Group item) {
        songAdapter.addAll(item.getList());
        binding.musicEmpty.setVisibility(item.getList().isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onSongClick(Music item) {
        MusicPlayer.get().play(songAdapterIndexOf(item) >= 0 ? currentList() : java.util.Collections.singletonList(item), Math.max(songAdapterIndexOf(item), 0));
        MusicPlayActivity.start(this);
    }

    private int songAdapterIndexOf(Music item) {
        return songAdapter.indexOf(item);
    }

    private List<Music> currentList() {
        MusicStore.Group group = currentGroup();
        return group == null ? new java.util.ArrayList<>() : group.getList();
    }

    private MusicStore.Group currentGroup() {
        List<MusicStore.Group> groups = MusicStore.groups();
        return groups.isEmpty() ? null : groups.get(0);
    }

    private void doSync() {
        if (!MusicSetting.isValid()) {
            showSetting();
            return;
        }
        binding.musicLoading.setVisibility(View.VISIBLE);
        Notify.show(R.string.music_syncing);
        LxSync.sync((ok, message) -> App.post(() -> {
            binding.musicLoading.setVisibility(View.GONE);
            Notify.show(ok ? message : getString(R.string.music_sync_fail) + "：" + message);
            if (ok) reload();
        }));
    }

    /** 音乐同步设置：地址、密码默认留空，填了才生效 */
    private void showSetting() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        EditText host = edit(MusicSetting.getUrl(), getString(R.string.music_host_hint), false);
        EditText pass = edit(MusicSetting.getPass(), getString(R.string.music_pass), true);
        EditText jx = edit(MusicSetting.getJx(), getString(R.string.music_jx), false);
        EditText script = edit(MusicSetting.getScript(), getString(R.string.music_script), false);

        TextView label = new TextView(this);
        label.setText(R.string.music_quality);
        label.setTextColor(0xFFFFFFFF);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, MusicSetting.QUALITYS);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, java.util.Arrays.asList(MusicSetting.QUALITYS).indexOf(MusicSetting.getQuality())));

        CheckBox lyric = new CheckBox(this);
        lyric.setText(R.string.music_lyric);
        lyric.setTextColor(0xFFFFFFFF);
        lyric.setChecked(MusicSetting.isLyric());

        layout.addView(host);
        layout.addView(pass);
        layout.addView(label);
        layout.addView(spinner);
        layout.addView(lyric);
        layout.addView(jx);
        layout.addView(script);

        new MaterialAlertDialogBuilder(this).setTitle(R.string.music_title).setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    MusicSetting.putUrl(host.getText().toString());
                    MusicSetting.putPass(pass.getText().toString());
                    MusicSetting.putJx(jx.getText().toString());
                    MusicSetting.putScript(script.getText().toString());
                    MusicSetting.putQuality((String) spinner.getSelectedItem());
                    MusicSetting.putLyric(lyric.isChecked());
                    new Thread(() -> MusicApi.get().init(), "lx-api").start();
                }).show();
    }

    private EditText edit(String value, String hint, boolean password) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setSingleLine(true);
        view.setGravity(Gravity.START);
        view.setTextColor(0xFFFFFFFF);
        view.setHintTextColor(0x80FFFFFF);
        if (password) view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicPlayer.get().addListener(this);
        onChanged(MusicPlayer.get().current(), MusicPlayer.get().isPlaying());
    }

    @Override
    protected void onPause() {
        MusicPlayer.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onChanged(Music music, boolean playing) {
        if (music == null) {
            binding.musicBar.setVisibility(View.GONE);
            return;
        }
        binding.musicBar.setVisibility(View.VISIBLE);
        binding.musicBarTitle.setText(music.getName().concat(" - ").concat(music.getSinger()));
        binding.musicBarPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        songAdapter.setPlaying(music);
    }

    @Override
    public void onProgress(long position, long duration) {
    }
}

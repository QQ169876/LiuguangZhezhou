package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ItemMusicSongBinding;
import com.fongmi.android.tv.music.Music;

import java.util.ArrayList;
import java.util.List;

/** 右边那一列：当前歌单里的歌 */
public class MusicSongAdapter extends RecyclerView.Adapter<MusicSongAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Music> items;
    private Music playing;

    public interface OnClickListener {
        void onItemClick(Music item);
    }

    public MusicSongAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void addAll(List<Music> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setPlaying(Music music) {
        playing = music;
        notifyDataSetChanged();
    }

    public int indexOf(Music music) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSame(music)) return i;
        return -1;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemMusicSongBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Music item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.singer.setText(item.getSinger());
        holder.binding.interval.setText(item.getInterval());
        boolean current = playing != null && item.isSame(playing);
        holder.binding.name.setTextColor(holder.binding.name.getResources().getColor(current ? com.fongmi.android.tv.R.color.blue_secondary : com.fongmi.android.tv.R.color.white, null));
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemMusicSongBinding binding;

        ViewHolder(@NonNull ItemMusicSongBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

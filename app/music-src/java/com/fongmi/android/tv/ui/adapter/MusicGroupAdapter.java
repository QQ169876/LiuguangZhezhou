package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ItemMusicGroupBinding;
import com.fongmi.android.tv.music.MusicStore;

import java.util.ArrayList;
import java.util.List;

/** 左边那一列：我的列表 / 我喜欢 / 各个自建歌单 */
public class MusicGroupAdapter extends RecyclerView.Adapter<MusicGroupAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<MusicStore.Group> items;
    private int selected;

    public interface OnClickListener {
        void onItemClick(MusicStore.Group item);
    }

    public MusicGroupAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void addAll(List<MusicStore.Group> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public int getSelected() {
        return selected;
    }

    public void setSelected(int position) {
        int old = selected;
        selected = position;
        notifyItemChanged(old);
        notifyItemChanged(selected);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemMusicGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MusicStore.Group item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.size.setText(String.valueOf(item.getSize()));
        holder.binding.getRoot().setSelected(position == selected);
        holder.binding.getRoot().setAlpha(position == selected ? 1f : 0.75f);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemMusicGroupBinding binding;

        ViewHolder(@NonNull ItemMusicGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

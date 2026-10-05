package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ItemMusicLrcBinding;
import com.fongmi.android.tv.music.Lrc;

/** 播放页的歌词，当前行高亮 */
public class LrcAdapter extends RecyclerView.Adapter<LrcAdapter.ViewHolder> {

    private Lrc lrc;
    private int current = -1;

    public LrcAdapter() {
        this.lrc = Lrc.parse("");
    }

    public void setLrc(Lrc lrc) {
        this.lrc = lrc == null ? Lrc.parse("") : lrc;
        this.current = -1;
        notifyDataSetChanged();
    }

    public void setCurrent(int index) {
        if (index == current) return;
        int old = current;
        current = index;
        if (old >= 0) notifyItemChanged(old);
        if (current >= 0) notifyItemChanged(current);
    }

    public int position(int index) {
        return Math.max(index, 0);
    }

    public boolean isEmpty() {
        return lrc.isEmpty();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemMusicLrcBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(lrc.text(position));
        holder.binding.text.setTextColor(holder.binding.text.getResources().getColor(position == current ? R.color.white : R.color.white_50, null));
        holder.binding.text.setTextSize(position == current ? 17f : 15f);
    }

    @Override
    public int getItemCount() {
        return lrc.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemMusicLrcBinding binding;

        ViewHolder(@NonNull ItemMusicLrcBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

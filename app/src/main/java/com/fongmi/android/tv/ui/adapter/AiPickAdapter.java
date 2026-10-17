package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.databinding.AdapterAiPickBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能搜索的候选列表：可能是 TMDB 认出来的作品（带海报/评分），也可能是模型给的题材词（放大镜图标）。
 * 不管哪种，点下去都是拿名字回 App 里全站搜。
 */
public class AiPickAdapter extends RecyclerView.Adapter<AiPickAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<TmdbWork> items;

    public AiPickAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(TmdbWork item);
    }

    public void setItems(List<TmdbWork> works) {
        items.clear();
        if (works != null) items.addAll(works);
        notifyDataSetChanged();
    }

    /** 模型提名的片子被 TMDB 认领回来了：换上准名字、海报和评分 */
    public void update(int position, TmdbWork work) {
        if (position < 0 || position >= items.size()) return;
        items.set(position, work);
        notifyItemChanged(position);
    }

    public void append(List<TmdbWork> works) {
        if (works == null || works.isEmpty()) return;
        int at = items.size();
        items.addAll(works);
        notifyItemRangeInserted(at, works.size());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterAiPickBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TmdbWork item = items.get(position);
        holder.binding.title.setText(item.getTitle());
        holder.binding.sub.setText(sub(item));
        holder.binding.sub.setVisibility(sub(item).isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        String poster = item.getPoster();
        if (poster.isEmpty()) {
            holder.binding.poster.setImageResource(R.drawable.ic_ai_pick);
        } else {
            Glide.with(holder.binding.poster).load(poster).placeholder(R.drawable.ic_ai_pick).error(R.drawable.ic_ai_pick).into(holder.binding.poster);
        }
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    /** 副标题：年份 · 评分 · 电影/剧集 · 演的角色，有啥拼啥；题材词行显示模型给的标签 */
    private String sub(TmdbWork item) {
        List<String> parts = new ArrayList<>();
        if (!item.getYear().isEmpty()) parts.add(item.getYear());
        if (!item.getRating().isEmpty()) parts.add(ResUtil.getString(R.string.ai_pick_rating, item.getRating()));
        if (item.getType() == TmdbWork.TYPE_MOVIE) parts.add(ResUtil.getString(R.string.ai_pick_movie));
        if (item.getType() == TmdbWork.TYPE_TV) parts.add(ResUtil.getString(R.string.ai_pick_tv));
        if (!TextUtils.isEmpty(item.getRole())) parts.add(item.getRole());
        return TextUtils.join(" · ", parts);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterAiPickBinding binding;

        public ViewHolder(@NonNull AdapterAiPickBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

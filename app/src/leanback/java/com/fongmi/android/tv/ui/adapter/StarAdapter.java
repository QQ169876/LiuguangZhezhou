package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.databinding.AdapterStarBinding;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 收藏页「演员」分类的卡片。演员没有头像，也不做头像占位，就是一张写着名字的牌子，
 * 宽度跟影视收藏那套对齐，遥控器上下左右走得顺。
 */
public class StarAdapter extends BaseDiffAdapter<Star, StarAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width;
    private boolean delete;

    public StarAdapter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public interface OnClickListener {

        void onItemClick(Star item);

        void onItemDelete(Star item);

        boolean onLongClick();
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        notifyItemRangeChanged(0, getItemCount());
    }

    private void setClickListener(View root, Star item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterStarBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Star item = getItem(position);
        setClickListener(holder.itemView, item);
        holder.binding.name.setText(item.getName());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterStarBinding binding;

        public ViewHolder(@NonNull AdapterStarBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
        }

        private void setFocusListener() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
                    v.setTranslationZ(10f);
                    v.setSelected(true);
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setTranslationZ(0f);
                    v.setSelected(false);
                }
            });
        }
    }
}

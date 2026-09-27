package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.databinding.AdapterLanBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 局域网设备列表（手机 / TV 双端共用）。
 */
public class LanAdapter extends RecyclerView.Adapter<LanAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Device> items;

    public LanAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public interface OnClickListener {
        void onItemClick(Device item);
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public void add(Device item) {
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public List<Device> getItems() {
        return items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLanBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.host.setText(item.getIp().replace("http://", ""));
        holder.binding.type.setImageResource(item.isMobile() ? R.drawable.ic_lan_mobile : R.drawable.ic_lan_tv);
        holder.binding.root.setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLanBinding binding;

        ViewHolder(@NonNull AdapterLanBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

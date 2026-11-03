package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiModel;
import com.fongmi.android.tv.databinding.AdapterAiModelBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型列表：自建的在前（顺序就是优先级，可以手动上下挪），内置的在后。
 * 内置的只给启停——改不了也删不掉；自建的能改能删能挪。
 */
public class AiModelAdapter extends RecyclerView.Adapter<AiModelAdapter.ViewHolder> {

    public interface OnActionListener {

        void onToggle(AiModel item);

        void onEdit(AiModel item);

        void onMove(AiModel item, boolean up);

        void onDelete(AiModel item);
    }

    private final OnActionListener listener;
    private final List<AiModel> items;

    public AiModelAdapter(OnActionListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void addAll(List<AiModel> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    /** 排在越前面越优先：给列表上第一条挂个「优先」的角标，一眼能看出问的是谁 */
    private boolean first(AiModel item) {
        if (item.isBuiltin()) return false;
        for (AiModel one : items) {
            if (one.isBuiltin()) continue;
            return one.getId().equals(item.getId());
        }
        return false;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterAiModelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AiModel item = items.get(position);
        boolean builtin = item.isBuiltin();
        holder.binding.name.setText(builtin ? item.getName().concat("（ ").concat(holder.binding.getRoot().getContext().getString(R.string.ai_model_builtin)).concat(" ）") : item.getName().isEmpty() ? item.getModel() : item.getName());
        holder.binding.sub.setText(first(item) ? holder.binding.getRoot().getContext().getString(R.string.ai_model_first).concat(" · ").concat(sub(item)) : sub(item));
        holder.binding.toggle.setText(item.isEnabled() ? R.string.ai_model_on : R.string.ai_model_off);
        holder.binding.edit.setVisibility(builtin ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.del.setVisibility(builtin ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.up.setVisibility(builtin ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.down.setVisibility(builtin ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.toggle.setOnClickListener(v -> listener.onToggle(item));
        holder.binding.edit.setOnClickListener(v -> listener.onEdit(item));
        holder.binding.del.setOnClickListener(v -> listener.onDelete(item));
        holder.binding.up.setOnClickListener(v -> listener.onMove(item, true));
        holder.binding.down.setOnClickListener(v -> listener.onMove(item, false));
    }

    private String sub(AiModel item) {
        String model = item.getModel().trim();
        String endpoint = item.getEndpoint().trim();
        if (model.isEmpty()) return endpoint;
        return endpoint.isEmpty() ? model : model.concat(" · ").concat(endpoint);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterAiModelBinding binding;

        public ViewHolder(@NonNull AdapterAiModelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

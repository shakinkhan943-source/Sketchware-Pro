package pro.sketchware.activities.editor.manage.library.downloader;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.databinding.ItemDependencyDownloadBinding;

import com.google.android.material.color.MaterialColors;


public class DependencyDownloadAdapter extends RecyclerView.Adapter<DependencyDownloadAdapter.ViewHolder> {

    private final List<DependencyDownloadItem> dependencies = new ArrayList<>();

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDependencyDownloadBinding binding = ItemDependencyDownloadBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DependencyDownloadItem item = dependencies.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return dependencies.size();
    }

    public void setDependencies(@NonNull List<DependencyDownloadItem> newDependencies) {
        dependencies.clear();
        dependencies.addAll(newDependencies);
        notifyDataSetChanged();
    }

    public void updateDependency(@NonNull DependencyDownloadItem updatedItem) {
        for (int i = 0; i < dependencies.size(); i++) {
            if (dependencies.get(i).equals(updatedItem)) {
                dependencies.set(i, updatedItem);
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void addDependency(@NonNull DependencyDownloadItem item) {
        dependencies.add(item);
        notifyItemInserted(dependencies.size() - 1);
    }

    public List<DependencyDownloadItem> getDependencies() {
        return new ArrayList<>(dependencies);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemDependencyDownloadBinding binding;

        public ViewHolder(@NonNull ItemDependencyDownloadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(@NonNull DependencyDownloadItem item) {
            binding.dependencyName.setText(item.getDisplayName());
            binding.statusText.setText(item.getStatusMessage());

            switch (item.getState()) {
                case PENDING:
                    setStatusIcon(R.drawable.ic_mtrl_info, MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnSurfaceVariant),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh));
                    binding.progressIndicator.setIndeterminate(true);
                    binding.progressIndicator.setVisibility(View.VISIBLE);
                    break;

                case RESOLVING:
                case UNZIPPING:
                case DEXING:
                    setStatusIcon(R.drawable.ic_mtrl_file, MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnSurface),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh));
                    binding.progressIndicator.setIndeterminate(true);
                    binding.progressIndicator.setVisibility(View.VISIBLE);
                    break;

                case DOWNLOADING:
                    setStatusIcon(R.drawable.ic_mtrl_download, MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnSurface),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh));
                    binding.progressIndicator.setIndeterminate(false);
                    binding.progressIndicator.setProgress(item.getProgress());
                    binding.progressIndicator.setVisibility(View.VISIBLE);
                    break;

                case COMPLETED:
                    setStatusIcon(R.drawable.ic_mtrl_done, MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnSurface),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh));
                    binding.progressIndicator.setVisibility(View.GONE);
                    break;

                case ERROR:
                    setStatusIcon(R.drawable.ic_mtrl_bt_error, MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnError),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorError));
                    binding.progressIndicator.setVisibility(View.GONE);
                    break;

                default:
                    binding.progressIndicator.setVisibility(View.GONE);
                    break;
            }
        }

        private void setStatusIcon(int iconRes, int iconColor, int containerColor) {
            binding.statusIcon.setImageResource(iconRes);
            binding.statusIcon.setImageTintList(ColorStateList.valueOf(iconColor));
            binding.statusIconContainer.setBackgroundTintList(ColorStateList.valueOf(containerColor));
        }
    }
}

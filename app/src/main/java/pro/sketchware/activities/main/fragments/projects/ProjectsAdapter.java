package pro.sketchware.activities.main.fragments.projects;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import pro.sketchware.activities.export.ExportProjectActivity;
import pro.sketchware.widgets.LoadingDialog;
import pro.sketchware.activities.projects.MyProjectSettingActivity;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import pro.sketchware.core.async.BackgroundTasks;
import pro.sketchware.util.io.SharedPrefsHelper;
import pro.sketchware.core.project.ProjectListManager;
import pro.sketchware.core.async.TaskHost;
import pro.sketchware.util.UIHelper;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.MapValueHelper;
import pro.sketchware.util.Helper;
import pro.sketchware.util.LogUtil;
import pro.sketchware.R;
import pro.sketchware.activities.main.fragments.projects.ProjectsFragment;
import pro.sketchware.databinding.BottomSheetProjectOptionsBinding;
import pro.sketchware.databinding.MyprojectsItemBinding;

public class ProjectsAdapter extends RecyclerView.Adapter<ProjectsAdapter.ProjectViewHolder> {
    private final ProjectsFragment projectsFragment;
    private final Activity activity;
    private final SharedPrefsHelper preference;
    private List<HashMap<String, Object>> shownProjects = new ArrayList<>();
    private List<HashMap<String, Object>> allProjects;

    public ProjectsAdapter(ProjectsFragment projectsFragment, List<HashMap<String, Object>> allProjects) {
        this.projectsFragment = projectsFragment;
        activity = projectsFragment.requireActivity();
        this.allProjects = allProjects;
        preference = new SharedPrefsHelper(activity, "project");

    }

    public void setAllProjects(List<HashMap<String, Object>> projects) {
        allProjects = projects;
    }

    public void filterData(String query) {
        List<HashMap<String, Object>> newProjects = query.isEmpty() ? allProjects : new ArrayList<>();
        if (!query.isEmpty()) {
            for (HashMap<String, Object> project : allProjects) {
                if (matchesQuery(project, query)) {
                    newProjects.add(project);
                }
            }
        }

        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return shownProjects.size();
            }

            @Override
            public int getNewListSize() {
                return newProjects.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                String oldScId = MapValueHelper.getString(shownProjects.get(oldItemPosition), "sc_id");
                String newScId = MapValueHelper.getString(newProjects.get(newItemPosition), "sc_id");
                return oldScId.equalsIgnoreCase(newScId);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                HashMap<String, Object> oldMap = shownProjects.get(oldItemPosition);
                HashMap<String, Object> newMap = newProjects.get(newItemPosition);
                for (String key : Arrays.asList("my_app_name", "my_ws_name", "sc_ver_name", "sc_ver_code", "my_sc_pkg_name")) {
                    if (!MapValueHelper.getString(oldMap, key).equals(MapValueHelper.getString(newMap, key))) {
                        return false;
                    }
                }
                boolean oldCustomIcon = MapValueHelper.get(oldMap, "custom_icon");
                boolean newCustomIcon = MapValueHelper.get(newMap, "custom_icon");
                return oldCustomIcon == newCustomIcon;
            }
        }, true);
        shownProjects = newProjects;
        result.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemCount() {
        return shownProjects.size();
    }

    private boolean matchesQuery(HashMap<String, Object> projectMap, String searchQuery) {
        searchQuery = searchQuery.toLowerCase();
        for (String key : Arrays.asList("sc_id", "my_ws_name", "my_app_name", "my_sc_pkg_name")) {
            if (MapValueHelper.getString(projectMap, key).toLowerCase().contains(searchQuery)) {
                return true;
            }
        }
        return false;
    }

    @DrawableRes
    public static <T> int getShapedBackgroundForList(List<T> list, int position) {
        if (list.size() == 1) {
            return R.drawable.project_item_shape_alone;
        } else if (position == 0) {
            return R.drawable.project_item_shape_top;
        } else if (position == list.size() - 1) {
            return R.drawable.project_item_shape_bottom;
        } else {
            return R.drawable.project_item_shape_middle;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ProjectViewHolder holder, int position) {
        HashMap<String, Object> projectMap = shownProjects.get(position);
        String scId = MapValueHelper.getString(projectMap, "sc_id");

        holder.itemView.setBackgroundResource(getShapedBackgroundForList(shownProjects, position));

        // Keep per-row click handling cheap: listeners are attached once and read the
        // bound data, so binding stays a plain text/icon update.
        holder.bindData(projectMap, scId, position);

        if (isPinned(projectMap)) {
            holder.binding.imgPin.setVisibility(View.VISIBLE);
        } else {
            holder.binding.imgPin.setVisibility(View.INVISIBLE);
        }

        bindProjectIcon(holder, projectMap, scId);

        String version = " - " + MapValueHelper.getString(projectMap, "sc_ver_name") + " (" + MapValueHelper.getString(projectMap, "sc_ver_code") + ")";
        holder.binding.appName.setText(MapValueHelper.getString(projectMap, "my_ws_name") + version);
        holder.binding.projectName.setText(MapValueHelper.getString(projectMap, "my_app_name"));
        holder.binding.packageName.setText(MapValueHelper.getString(projectMap, "my_sc_pkg_name"));
        holder.itemView.setTag("custom");
    }

    /**
     * Loads the project launcher icon. Custom icons are decoded off the main thread and
     * cached by Glide (the previous FileProvider + setImageURI path decoded on the UI
     * thread, which caused jank while scrolling through many projects).
     */
    private void bindProjectIcon(ProjectViewHolder holder, HashMap<String, Object> projectMap, String scId) {
        if (MapValueHelper.get(projectMap, "custom_icon")) {
            File iconFile = new File(SketchwarePaths.getIconsPath() + File.separator + scId + File.separator + "icon.png");
            if (iconFile.exists()) {
                Glide.with(holder.binding.imgIcon.getContext())
                        .load(iconFile)
                        .placeholder(R.drawable.default_icon)
                        .error(R.drawable.default_icon)
                        .centerCrop()
                        .into(holder.binding.imgIcon);
                return;
            }
        }
        Glide.with(holder.binding.imgIcon.getContext()).clear(holder.binding.imgIcon);
        holder.binding.imgIcon.setImageResource(R.drawable.default_icon);
    }

    @NonNull
    @Override
    public ProjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        MyprojectsItemBinding binding = MyprojectsItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ProjectViewHolder(binding, this);
    }

    private void deleteProject(HashMap<String, Object> projectMap, int position) {
        LoadingDialog progressDialog = new LoadingDialog(activity);
        progressDialog.show();

        String scId = MapValueHelper.getString(projectMap, "sc_id");
        BackgroundTasks.runIo(TaskHost.of(activity), "ProjectsAdapter", () -> ProjectListManager.deleteProject(activity, scId), () -> {
            progressDialog.dismiss();
            shownProjects.remove(position);
            notifyDataSetChanged();
            allProjects.remove(projectMap);
            projectsFragment.updateEmptyState();
        }, error -> progressDialog.dismiss());
    }

    void toProjectSettingOrRequestPermission(HashMap<String, Object> project, int index) {
        Intent intent = new Intent(activity, MyProjectSettingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("sc_id", MapValueHelper.getString(project, "sc_id"));
        intent.putExtra("is_update", true);
        intent.putExtra("index", index);
        projectsFragment.openProjectSettings.launch(intent);
    }

    private void showProjectSettingDialog(HashMap<String, Object> project) {
        new ProjectSettingsDialog(activity, MapValueHelper.getString(project, "sc_id")).show();
    }

    private void backupProject(HashMap<String, Object> project) {
        String scId = MapValueHelper.getString(project, "sc_id");
        String appName = MapValueHelper.getString(project, "my_ws_name");
        new BackupRestoreManager(activity).backup(scId, appName);
    }

    private void toExportProjectActivity(HashMap<String, Object> project) {
        Intent intent = new Intent(activity, ExportProjectActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("sc_id", MapValueHelper.getString(project, "sc_id"));
        activity.startActivity(intent);
    }

    private void changePinState(HashMap<String, Object> projectMap) {
        if (isPinned(projectMap)) {
            preference.put("pinnedProject", "-1", true);
        } else {
            preference.put("pinnedProject", MapValueHelper.getString(projectMap, "sc_id"), true);
        }
        projectsFragment.refreshProjectsList();
    }

    private boolean isPinned(HashMap<String, Object> projectMap) {
        return Objects.equals(MapValueHelper.getString(projectMap, "sc_id"), preference.getString("pinnedProject", "-1"));
    }

    void showProjectOptionsBottomSheet(HashMap<String, Object> projectMap, int position) {
        BottomSheetDialog projectOptionsBSD = new BottomSheetDialog(activity);
        BottomSheetProjectOptionsBinding binding = BottomSheetProjectOptionsBinding.inflate(LayoutInflater.from(activity));
        projectOptionsBSD.setContentView(binding.getRoot());

        binding.title.setText(MapValueHelper.getString(projectMap, "my_ws_name"));
        binding.tvProjectId.setText(MapValueHelper.getString(projectMap, "sc_id"));

        binding.projectSettings.setOnClickListener(v -> {
            toProjectSettingOrRequestPermission(projectMap, position);
            projectOptionsBSD.dismiss();
        });

        binding.projectBackup.setOnClickListener(v -> {
            backupProject(projectMap);
            projectOptionsBSD.dismiss();
        });

        binding.pinProject.setOnClickListener(v -> {
            changePinState(projectMap);
            projectOptionsBSD.dismiss();
        });

        binding.exportSign.setOnClickListener(v -> {
            toExportProjectActivity(projectMap);
            projectOptionsBSD.dismiss();
        });

        binding.projectConfig.setOnClickListener(v -> {
            showProjectSettingDialog(projectMap);
            projectOptionsBSD.dismiss();
        });

        binding.projectDelete.setOnClickListener(v -> {
            projectOptionsBSD.dismiss();
            MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity);
            dialog.setIcon(R.drawable.icon_delete);
            dialog.setTitle(Helper.getResString(R.string.delete_project_dialog_title));
            dialog.setMessage(Helper.getResString(R.string.delete_project_dialog_message).replace("%1$s", MapValueHelper.getString(projectMap, "my_app_name")));
            dialog.setPositiveButton(Helper.getResString(R.string.common_word_delete), (v1, which) -> {
                deleteProject(projectMap, position);
                v1.dismiss();
            });
            dialog.setNegativeButton(Helper.getResString(R.string.common_word_cancel), null);
            dialog.show();
        });

        if (isPinned(projectMap)) {
            binding.pinIcon.setImageResource(R.drawable.ic_mtrl_unpin);
            binding.pinText.setText(R.string.project_unpin);
        } else {
            binding.pinIcon.setImageResource(R.drawable.ic_mtrl_pin);
            binding.pinText.setText(R.string.project_pin);
        }

        projectOptionsBSD.show();
    }

    public static class ProjectViewHolder extends RecyclerView.ViewHolder {
        final MyprojectsItemBinding binding;
        private final ProjectsAdapter adapter;
        private HashMap<String, Object> projectMap;
        private String scId;
        private int position;

        ProjectViewHolder(MyprojectsItemBinding binding, ProjectsAdapter adapter) {
            super(binding.getRoot());
            this.binding = binding;
            this.adapter = adapter;

            // Click handling is attached once; the bound row data is read from the
            // fields set in {@link #bindData} so binds never re-allocate listeners.
            binding.getRoot().setOnClickListener(v -> {
                if (scId != null && !UIHelper.isClickThrottled()) {
                    adapter.projectsFragment.toDesignActivity(scId);
                }
            });
            binding.expand.setOnClickListener(v -> {
                UIHelper.disableTemporarily(v);
                int currentPosition = getAbsoluteAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION && projectMap != null) {
                    adapter.showProjectOptionsBottomSheet(projectMap, currentPosition);
                }
            });
            binding.imgIcon.setOnClickListener(v -> {
                if (projectMap != null) {
                    adapter.toProjectSettingOrRequestPermission(projectMap, position);
                }
            });
            binding.getRoot().setOnLongClickListener(v -> {
                int currentPosition = getAbsoluteAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION && projectMap != null) {
                    adapter.showProjectOptionsBottomSheet(projectMap, currentPosition);
                }
                return true;
            });
        }

        void bindData(HashMap<String, Object> projectMap, String scId, int position) {
            this.projectMap = projectMap;
            this.scId = scId;
            this.position = position;
        }
    }
}

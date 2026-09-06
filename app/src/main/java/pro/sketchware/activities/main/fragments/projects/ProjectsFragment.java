package pro.sketchware.activities.main.fragments.projects;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;

import pro.sketchware.activities.design.DesignActivity;
import pro.sketchware.activities.editor.manage.library.ProjectComparator;
import pro.sketchware.activities.projects.MyProjectSettingActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.transition.MaterialFadeThrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import pro.sketchware.activities.base.PermissionFragment;
import pro.sketchware.util.io.SharedPrefsHelper;
import pro.sketchware.core.project.ProjectListManager;
import pro.sketchware.core.project.ProjectTracker;
import pro.sketchware.R;
import pro.sketchware.activities.main.activities.MainActivity;
import pro.sketchware.databinding.MyprojectsBinding;
import pro.sketchware.databinding.SortProjectDialogBinding;
import pro.sketchware.util.MapValueHelper;
import pro.sketchware.util.UI;

public class ProjectsFragment extends PermissionFragment {
    private ExecutorService executorService;
    private final List<HashMap<String, Object>> projectsList = new ArrayList<>();
    private MyprojectsBinding binding;
    private ProjectsAdapter projectsAdapter;
    public final ActivityResultLauncher<Intent> openProjectSettings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            if (data != null) {
                String sc_id = data.getStringExtra("sc_id");
                if (data.getBooleanExtra("is_new", false)) {
                    toDesignActivity(sc_id);
                    addProject(sc_id);
                } else {
                    updateProject(sc_id);
                }
            }
        }
    });
    private SharedPrefsHelper preference;
    private SearchView projectsSearchView;
    private MenuProvider menuProvider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialFadeThrough());
        setReturnTransition(new MaterialFadeThrough());
        setExitTransition(new MaterialFadeThrough());
        setReenterTransition(new MaterialFadeThrough());
    }

    @Override
    public void onPermissionGranted(int requestCode) {
    }

    public void toDesignActivity(String sc_id) {
        Intent intent = new Intent(requireContext(), DesignActivity.class);
        ProjectTracker.setScId(sc_id);
        intent.putExtra("sc_id", sc_id);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        requireActivity().startActivity(intent);
    }

    @Override
    public void openAppSettings(int requestCode) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }

    @Override
    public void onPermissionDenied() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showStorageAccessDeniedSnackbar();
        }
    }

    @Override
    public void onSettingsDenied() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showStorageAccessDeniedSnackbar();
        }
    }

    public void toProjectSettingsActivity() {
        Intent intent = new Intent(getActivity(), MyProjectSettingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openProjectSettings.launch(intent);
    }

    public void restoreProject() {
        new BackupRestoreManager(getActivity(), this).restore();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        binding = MyprojectsBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executorService.shutdownNow();
        binding = null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        executorService = Executors.newSingleThreadExecutor();
        preference = new SharedPrefsHelper(requireContext(), "project");

        binding.swipeRefresh.setOnRefreshListener(this::refreshProjectsList);
        binding.swipeRefresh.setOnChildScrollUpCallback((parent, child) -> binding.myprojects.canScrollVertically(-1));

        projectsAdapter = new ProjectsAdapter(this, projectsList);
        binding.myprojects.setAdapter(projectsAdapter);
        binding.myprojects.setHasFixedSize(true);
        binding.myprojects.setItemViewCacheSize(5);

        binding.btnEmptyCreate.setOnClickListener(v -> toProjectSettingsActivity());
        binding.specialAction.projectOne.setOnClickListener(v -> toProjectSettingsActivity());
        binding.specialAction.restoreProject.setOnClickListener(v -> restoreProject());

        binding.myprojects.post(this::refreshProjectsList);
        UI.addSystemWindowInsetToPadding(binding.specialActionContainer, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.loadingContainer, true, false, true, true);
        UI.addSystemWindowInsetToPadding(binding.titleContainer, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.myprojects, true, false, true, true);

        binding.iconSort.setOnClickListener(v -> showProjectSortingDialog());

        menuProvider = new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.projects_fragment_menu, menu);
                projectsSearchView = (SearchView) menu.findItem(R.id.searchProjects).getActionView();
                if (projectsSearchView != null) {
                    projectsSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                        @Override
                        public boolean onQueryTextChange(String s) {
                            projectsAdapter.filterData(s);
                            return false;
                        }

                        @Override
                        public boolean onQueryTextSubmit(String s) {
                            return false;
                        }
                    });
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                return false;
            }
        };

        requireActivity().addMenuProvider(menuProvider);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() == null) return;
        if (hidden) {
            requireActivity().removeMenuProvider(menuProvider);
        } else {
            requireActivity().addMenuProvider(menuProvider);
        }
    }

    public void refreshProjectsList() {
        if (!isAdded()) return;

        if (!hasStoragePermission()) {
            if (binding.swipeRefresh.isRefreshing()) binding.swipeRefresh.setRefreshing(false);
            ((MainActivity) requireActivity()).showStorageAccessDeniedSnackbar();
            return;
        }

        executorService.execute(() -> {
            List<HashMap<String, Object>> loadedProjects = ProjectListManager.listProjects();
            backfillLegacyProjectVersions(loadedProjects);
            loadedProjects.sort(new ProjectComparator(preference.getIntDefault("sortBy"), preference.getString("pinnedProject", "-1")));

            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new ProjectDiffCallback(projectsList, loadedProjects));

            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || activity.isFinishing() || activity.isDestroyed()) return;
                if (binding == null) return;
                if (binding.swipeRefresh.isRefreshing()) binding.swipeRefresh.setRefreshing(false);
                if (binding.loadingContainer.getVisibility() == View.VISIBLE) {
                    binding.loadingContainer.setVisibility(View.GONE);
                    binding.myprojects.setVisibility(View.VISIBLE);
                }
                projectsList.clear();
                projectsList.addAll(loadedProjects);
                diffResult.dispatchUpdatesTo(projectsAdapter);
                updateEmptyState();
                if (projectsSearchView != null)
                    projectsAdapter.filterData(projectsSearchView.getQuery().toString());
            });
        });
    }

    private static void backfillLegacyProjectVersions(List<HashMap<String, Object>> projects) {
        for (HashMap<String, Object> project : projects) {
            String scId = MapValueHelper.getString(project, "sc_id");
            boolean changed = false;
            if (MapValueHelper.getString(project, "sc_ver_code").isEmpty()) {
                project.put("sc_ver_code", "1");
                project.put("sc_ver_name", "1.0");
                changed = true;
            }
            if (MapValueHelper.getInt(project, "sketchware_ver") <= 0) {
                project.put("sketchware_ver", 61);
                changed = true;
            }
            if (changed) ProjectListManager.updateProject(scId, project);
        }
    }

    public void updateEmptyState() {
        if (binding == null) return;
        binding.emptyContainer.setVisibility(projectsList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addProject(String sc_id) {
        executorService.execute(() -> {
            HashMap<String, Object> newProject = ProjectListManager.getProjectById(sc_id);
            if (newProject != null) {
                var activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    if (!isAdded() || activity.isFinishing() || activity.isDestroyed()) return;
                    if (binding == null) return;
                    projectsList.add(0, newProject);
                    projectsAdapter.notifyDataSetChanged();
                    binding.myprojects.scrollToPosition(0);
                });
            }
        });
    }

    private void updateProject(String sc_id) {
        executorService.execute(() -> {
            HashMap<String, Object> updatedProject = ProjectListManager.getProjectById(sc_id);
            if (updatedProject != null) {
                int index = IntStream.range(0, projectsList.size()).filter(i -> sc_id.equals(projectsList.get(i).get("sc_id"))).findFirst().orElse(-1);
                if (index != -1) {
                    projectsList.set(index, updatedProject);
                    var activity = getActivity();
                    if (activity == null) return;
                    activity.runOnUiThread(() -> {
                        if (!isAdded() || activity.isFinishing() || activity.isDestroyed()) return;
                        projectsAdapter.notifyDataSetChanged();
                    });
                }
            }
        });
    }

    private void showProjectSortingDialog() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(requireActivity());
        dialog.setTitle(R.string.projects_sort_title);

        SortProjectDialogBinding dialogBinding = SortProjectDialogBinding.inflate(LayoutInflater.from(requireActivity()));
        RadioButton sortByName = dialogBinding.sortByName;
        RadioButton sortByID = dialogBinding.sortByID;
        RadioButton sortOrderAsc = dialogBinding.sortOrderAsc;
        RadioButton sortOrderDesc = dialogBinding.sortOrderDesc;

        int storedValue = preference.getInt("sortBy", ProjectComparator.DEFAULT);
        if ((storedValue & ProjectComparator.SORT_BY_NAME) == ProjectComparator.SORT_BY_NAME) sortByName.setChecked(true);
        else if ((storedValue & ProjectComparator.SORT_BY_ID) == ProjectComparator.SORT_BY_ID) sortByID.setChecked(true);
        if ((storedValue & ProjectComparator.SORT_ORDER_ASCENDING) == ProjectComparator.SORT_ORDER_ASCENDING) sortOrderAsc.setChecked(true);
        else if ((storedValue & ProjectComparator.SORT_ORDER_DESCENDING) == ProjectComparator.SORT_ORDER_DESCENDING) sortOrderDesc.setChecked(true);

        dialog.setView(dialogBinding.getRoot());
        dialog.setPositiveButton(R.string.common_word_save, (v, which) -> {
            int sortValue = 0;
            if (sortByName.isChecked()) sortValue |= ProjectComparator.SORT_BY_NAME;
            if (sortByID.isChecked()) sortValue |= ProjectComparator.SORT_BY_ID;
            if (sortOrderAsc.isChecked()) sortValue |= ProjectComparator.SORT_ORDER_ASCENDING;
            if (sortOrderDesc.isChecked()) sortValue |= ProjectComparator.SORT_ORDER_DESCENDING;
            preference.put("sortBy", sortValue, true);
            v.dismiss();
            refreshProjectsList();
        });
        dialog.setNegativeButton(R.string.common_word_cancel, null);
        dialog.show();
    }

    private static class ProjectDiffCallback extends DiffUtil.Callback {
        private final List<HashMap<String, Object>> oldList;
        private final List<HashMap<String, Object>> newList;

        ProjectDiffCallback(List<HashMap<String, Object>> oldList, List<HashMap<String, Object>> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return Objects.equals(oldList.get(oldItemPosition).get("sc_id"), newList.get(newItemPosition).get("sc_id"));
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).equals(newList.get(newItemPosition));
        }
    }
}

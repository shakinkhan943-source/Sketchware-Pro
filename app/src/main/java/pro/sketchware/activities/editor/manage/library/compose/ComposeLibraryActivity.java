package pro.sketchware.activities.editor.manage.library.compose;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;

import pro.sketchware.activities.base.BaseAppCompatActivity;
import pro.sketchware.activities.editor.manage.library.downloader.LibraryDownloaderDialogFragment;
import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.core.build.BuildSettings;
import pro.sketchware.databinding.ItemComposeDependencyBinding;
import pro.sketchware.databinding.ManageLibraryComposeBinding;
import pro.sketchware.util.Helper;
import pro.sketchware.util.library.LocalLibrariesUtil;
import pro.sketchware.R;

/**
 * Settings screen for the Jetpack Compose component. It exposes a simple
 * enable toggle and, once enabled, a list of downloadable Compose dependencies.
 * Tapping a dependency opens the existing Sketchware dependency downloader
 * which downloads it and makes it available to the project (enable -> download
 * -> use).
 */
public class ComposeLibraryActivity extends BaseAppCompatActivity {

    private ManageLibraryComposeBinding binding;
    private ProjectLibraryBean composeLibraryBean;
    private BuildSettings buildSettings;
    private String scId;
    private boolean notAssociatedWithProject;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ManageLibraryComposeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        enableEdgeToEdgeNoContrast();
        initialize();
    }

    private void initialize() {
        binding.toolbar.setNavigationOnClickListener(Helper.getBackPressedClickListener(this));

        scId = getIntent().getStringExtra("sc_id");
        composeLibraryBean = getIntent().getParcelableExtra("compose");
        if (composeLibraryBean == null) {
            composeLibraryBean = new ProjectLibraryBean(ProjectLibraryBean.PROJECT_LIB_TYPE_COMPOSE);
        }

        if (scId != null) {
            buildSettings = new BuildSettings(scId);
            notAssociatedWithProject = scId.equals("system");
        }

        binding.composeSwitch.setChecked(composeLibraryBean.isEnabled());
        updateDependenciesSection(binding.composeSwitch.isChecked());

        binding.layoutSwitchCompose.setOnClickListener(v -> binding.composeSwitch.setChecked(!binding.composeSwitch.isChecked()));
        binding.composeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateDependenciesSection(isChecked));

        populateDependencyList();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                composeLibraryBean.useYn = binding.composeSwitch.isChecked()
                        ? ProjectLibraryBean.LIB_USE_Y
                        : ProjectLibraryBean.LIB_USE_N;

                Intent resultIntent = new Intent();
                resultIntent.putExtra("compose", composeLibraryBean);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void updateDependenciesSection(boolean enabled) {
        binding.composeDependenciesSection.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void populateDependencyList() {
        LinearLayout container = binding.composeDependenciesContainer;
        container.removeAllViews();

        for (ComposeDependency dependency : ComposeDependencies.getDefaults()) {
            ItemComposeDependencyBinding itemBinding = ItemComposeDependencyBinding.inflate(
                    LayoutInflater.from(this), container, false);

            itemBinding.dependencyName.setText(dependency.name);
            itemBinding.dependencyCoordinate.setText(dependency.coordinate);
            boolean downloaded = isDependencyDownloaded(dependency);
            itemBinding.dependencyStatus.setText(Helper.getResString(downloaded
                    ? R.string.compose_dependency_status_downloaded
                    : R.string.compose_dependency_status_download));

            itemBinding.cardDependency.setOnClickListener(v -> {
                if (isDependencyDownloaded(dependency)) {
                    return;
                }
                showDependencyDownloader(dependency);
            });

            container.addView(itemBinding.getRoot());
        }
    }

    private boolean isDependencyDownloaded(ComposeDependency dependency) {
        return !dependency.getLibraryName().isEmpty()
                && LocalLibrariesUtil.getLocalLibraryDirectory(dependency.getLibraryName()).exists();
    }

    private void showDependencyDownloader(ComposeDependency dependency) {
        if (scId == null
                || getSupportFragmentManager().findFragmentByTag("compose_dependency_downloader") != null) {
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putBoolean("notAssociatedWithProject", notAssociatedWithProject);
        bundle.putSerializable("buildSettings", buildSettings);
        if (!notAssociatedWithProject) {
            bundle.putString("localLibFile", LocalLibrariesUtil.getLocalLibFile(scId).getAbsolutePath());
        }
        bundle.putString("prefillDependency", dependency.coordinate);

        LibraryDownloaderDialogFragment fragment = new LibraryDownloaderDialogFragment();
        fragment.setArguments(bundle);
        fragment.setOnLibraryDownloadedTask(this::populateDependencyList);
        fragment.show(getSupportFragmentManager(), "compose_dependency_downloader");
    }
}

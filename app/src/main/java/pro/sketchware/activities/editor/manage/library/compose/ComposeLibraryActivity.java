package pro.sketchware.activities.editor.manage.library.compose;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import pro.sketchware.R;
import pro.sketchware.activities.base.BaseAppCompatActivity;
import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.databinding.ItemComposeDependencyBinding;
import pro.sketchware.databinding.ManageLibraryComposeBinding;
import pro.sketchware.util.Helper;
import pro.sketchware.util.library.ComposeBuiltInLibraries;
import pro.sketchware.util.library.ComposeBuiltInLibraryManager;

/**
 * Settings screen for the built-in Jetpack Compose bundle.
 * Required features are always enabled; optional feature groups can be selected
 * without invoking the normal dependency downloader.
 */
public class ComposeLibraryActivity extends BaseAppCompatActivity {

    private ManageLibraryComposeBinding binding;
    private ProjectLibraryBean composeLibraryBean;
    private final Set<String> selectedOptionalFeatures = new HashSet<>();

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

        composeLibraryBean = getIntent().getParcelableExtra("compose");
        if (composeLibraryBean == null) {
            composeLibraryBean = new ProjectLibraryBean(ProjectLibraryBean.PROJECT_LIB_TYPE_COMPOSE);
        }

        selectedOptionalFeatures.addAll(
                new ComposeBuiltInLibraryManager(composeLibraryBean).getOptionalFeatureIds());

        binding.composeSwitch.setChecked(composeLibraryBean.isEnabled());
        updateDependenciesSection(binding.composeSwitch.isChecked());
        binding.layoutSwitchCompose.setOnClickListener(v ->
                binding.composeSwitch.setChecked(!binding.composeSwitch.isChecked()));
        binding.composeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateDependenciesSection(isChecked));

        populateDependencyList();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                composeLibraryBean.useYn = binding.composeSwitch.isChecked()
                        ? ProjectLibraryBean.LIB_USE_Y
                        : ProjectLibraryBean.LIB_USE_N;
                new ComposeBuiltInLibraryManager(composeLibraryBean)
                        .setOptionalFeatureIds(new ArrayList<>(selectedOptionalFeatures));

                Intent resultIntent = new Intent();
                resultIntent.putExtra("compose", composeLibraryBean);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void updateDependenciesSection(boolean enabled) {
        binding.composeDependenciesSection.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void populateDependencyList() {
        LinearLayout container = binding.composeDependenciesContainer;
        container.removeAllViews();

        for (ComposeBuiltInLibraries.ComposeFeature feature : ComposeBuiltInLibraries.getFeatures()) {
            ItemComposeDependencyBinding itemBinding = ItemComposeDependencyBinding.inflate(
                    LayoutInflater.from(this), container, false);

            itemBinding.dependencyName.setText(feature.name);
            itemBinding.dependencyCoordinate.setText(feature.description);
            itemBinding.dependencyTag.setText(feature.tag);

            boolean enabled = feature.required || selectedOptionalFeatures.contains(feature.id);
            itemBinding.dependencySwitch.setChecked(enabled);
            itemBinding.dependencySwitch.setEnabled(!feature.required);
            itemBinding.dependencySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                if (feature.required) return;
                if (checked) selectedOptionalFeatures.add(feature.id);
                else selectedOptionalFeatures.remove(feature.id);
            });

            itemBinding.cardDependency.setOnClickListener(v -> {
                if (!feature.required) {
                    itemBinding.dependencySwitch.setChecked(!itemBinding.dependencySwitch.isChecked());
                }
            });
            container.addView(itemBinding.getRoot());
        }
    }
}

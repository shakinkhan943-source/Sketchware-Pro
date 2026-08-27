package pro.sketchware.activities.editor.manage.library.compose;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import dev.pranav.filepicker.FilePickerCallback;
import dev.pranav.filepicker.FilePickerDialogFragment;
import dev.pranav.filepicker.FilePickerOptions;

import pro.sketchware.R;
import pro.sketchware.activities.base.BaseAppCompatActivity;
import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.databinding.ItemComposeDependencyBinding;
import pro.sketchware.databinding.ManageLibraryComposeBinding;
import pro.sketchware.util.Helper;
import pro.sketchware.util.library.ComposeBuiltInLibraries;
import pro.sketchware.util.library.ComposeBuiltInLibraryManager;
import pro.sketchware.util.library.ComposeDependencyManager;

/**
 * Project Compose library selector and, when opened from App Settings, the
 * global Compose dependency package selector.
 */
public class ComposeLibraryActivity extends BaseAppCompatActivity {

    private ManageLibraryComposeBinding binding;
    private ProjectLibraryBean composeLibraryBean;
    private final Set<String> selectedOptionalFeatures = new HashSet<>();
    private boolean packageSettingsMode;
    private File selectedZip;
    private File selectedJson;

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
        packageSettingsMode = composeLibraryBean == null;
        if (packageSettingsMode) {
            setupPackageSettings();
            return;
        }

        selectedOptionalFeatures.addAll(
                new ComposeBuiltInLibraryManager(composeLibraryBean).getOptionalFeatureIds());

        if (!ComposeBuiltInLibraries.isBundleAvailable()) {
            binding.composeSwitch.setChecked(false);
            binding.composeSwitch.setEnabled(false);
            binding.layoutSwitchCompose.setEnabled(false);
            updateDependenciesSection(false);
            Toast.makeText(this,
                    "No Jetpack Compose dependency package is configured. Open App Settings → Jetpack Compose Dependencies.",
                    Toast.LENGTH_LONG).show();
            return;
        }

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

    private void setupPackageSettings() {
        binding.toolbar.setTitle("Jetpack Compose Dependencies");
        binding.composeDependenciesSection.setVisibility(View.VISIBLE);
        binding.layoutSwitchCompose.setVisibility(View.GONE);
        binding.composeDependenciesContainer.removeAllViews();

        TextView description = new TextView(this);
        description.setText("Select the Compose dependency ZIP and accompanying JSON manifest. The JSON is parsed automatically and package paths are detected from it.");
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        description.setPadding(padding, padding, padding, padding);
        binding.composeDependenciesContainer.addView(description);

        TextView status = new TextView(this);
        status.setPadding(padding, 0, padding, padding);
        binding.composeDependenciesContainer.addView(status);

        MaterialButton zipButton = new MaterialButton(this);
        zipButton.setText("Select Compose dependency ZIP");
        binding.composeDependenciesContainer.addView(zipButton);
        zipButton.setOnClickListener(v -> pickZip(status));

        MaterialButton jsonButton = new MaterialButton(this);
        jsonButton.setText("Select accompanying JSON");
        binding.composeDependenciesContainer.addView(jsonButton);
        jsonButton.setOnClickListener(v -> pickJson(status));

        MaterialButton applyButton = new MaterialButton(this);
        applyButton.setText("Use selected package");
        applyButton.setEnabled(false);
        binding.composeDependenciesContainer.addView(applyButton);
        applyButton.setOnClickListener(v -> applyPackage(status, applyButton));

        MaterialButton clearButton = new MaterialButton(this);
        clearButton.setText("Remove configured package");
        binding.composeDependenciesContainer.addView(clearButton);
        clearButton.setOnClickListener(v -> {
            ComposeDependencyManager.clear();
            selectedZip = null;
            selectedJson = null;
            updatePackageStatus(status, applyButton);
            Toast.makeText(this, "Compose dependency package removed", Toast.LENGTH_SHORT).show();
        });

        updatePackageStatus(status, applyButton);
    }

    private void pickZip(TextView status) {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"zip"});
        options.setTitle("Select Compose dependency ZIP");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                selectedZip = file;
                status.setText("ZIP selected: " + file.getName());
                MaterialButton applyButton = findApplyButton();
                if (applyButton != null) applyButton.setEnabled(selectedZip != null && selectedJson != null);
            }
        }).show(getSupportFragmentManager(), "compose_zip_picker");
    }

    private void pickJson(TextView status) {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"json"});
        options.setTitle("Select accompanying JSON");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                selectedJson = file;
                status.setText("JSON selected: " + file.getName());
                MaterialButton applyButton = findApplyButton();
                if (applyButton != null) applyButton.setEnabled(selectedZip != null && selectedJson != null);
            }
        }).show(getSupportFragmentManager(), "compose_json_picker");
    }

    private MaterialButton findApplyButton() {
        for (int i = 0; i < binding.composeDependenciesContainer.getChildCount(); i++) {
            View child = binding.composeDependenciesContainer.getChildAt(i);
            if (child instanceof MaterialButton && "Use selected package".contentEquals(((MaterialButton) child).getText())) {
                return (MaterialButton) child;
            }
        }
        return null;
    }

    private void updatePackageStatus(TextView status, MaterialButton applyButton) {
        if (ComposeDependencyManager.isConfigured()) {
            String hash = ComposeDependencyManager.getConfiguredPackageName();
            status.setText("Configured package: " + hash.substring(0, Math.min(12, hash.length())) + "…");
        } else {
            status.setText("No Compose dependency package configured.");
        }
        applyButton.setEnabled(selectedZip != null && selectedJson != null);
    }

    private void applyPackage(TextView status, MaterialButton applyButton) {
        if (selectedZip == null || selectedJson == null) return;
        applyButton.setEnabled(false);
        status.setText("Validating and preparing Compose dependencies…");
        new Thread(() -> {
            try {
                ComposeDependencyManager.configure(selectedZip, selectedJson);
                ComposeDependencyManager.ensureReady();
                runOnUiThread(() -> {
                    updatePackageStatus(status, applyButton);
                    Toast.makeText(this, "Compose dependency package is ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Package error: " + e.getMessage());
                    applyButton.setEnabled(true);
                });
            }
        }, "compose-package-prepare").start();
    }

    private void updateDependenciesSection(boolean enabled) {
        binding.composeDependenciesSection.setVisibility(enabled ? View.VISIBLE : View.GONE);
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

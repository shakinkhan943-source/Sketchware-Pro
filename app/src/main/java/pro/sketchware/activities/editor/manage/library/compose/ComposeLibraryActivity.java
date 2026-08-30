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

import com.google.gson.Gson;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
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
import pro.sketchware.util.library.JetpackLibs;
import pro.sketchware.util.library.JetpackLibsInstaller;
import pro.sketchware.util.library.LocalLibrariesUtil;
import pro.sketchware.util.library.LocalLibrary;

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
    private String scId;
    /** Artifacts of the shared Jetpack store this project activates. */
    private final Set<String> enabledStoreArtifacts = new LinkedHashSet<>();
    private LinearLayout storeList;
    private TextView storeStatus;
    private MaterialButton storeImportButton;
    private boolean useZipKotlinRuntime;
    private boolean storeSelectionChanged;

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
        scId = getIntent().getStringExtra("sc_id");
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
        if (scId != null) {
            // Start from what the project already activated, so leaving a row untouched cannot silently
            // drop it from the project's library list when this screen closes.
            enabledStoreArtifacts.addAll(activeStoreArtifacts());
            addStoreSection();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                composeLibraryBean.useYn = binding.composeSwitch.isChecked()
                        ? ProjectLibraryBean.LIB_USE_Y
                        : ProjectLibraryBean.LIB_USE_N;
                new ComposeBuiltInLibraryManager(composeLibraryBean)
                        .setOptionalFeatureIds(new ArrayList<>(selectedOptionalFeatures));
                if (storeSelectionChanged) {
                    applyStoreSelection();
                }

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

        addStoreSection();

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

    /**
     * The shared Jetpack store: import a dependency ZIP and choose which of its artifacts this project
     * activates. Installed artifacts live once under {@code .sketchware/libs/JetpackLibs}, so enabling
     * them here only records names in the project's local library list — no copies per project, and no
     * cache directory that the system may clear.
     */
    private void addStoreSection() {
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        LinearLayout container = binding.composeDependenciesContainer;

        TextView storeTitle = new TextView(this);
        storeTitle.setText("Jetpack library store");
        storeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        storeTitle.setPadding(padding, padding, padding, 0);
        container.addView(storeTitle);

        TextView storeDescription = new TextView(this);
        storeDescription.setText("Pick a ZIP that contains one folder per artifact"
                + " (classes.jar, and optionally classes.dex, res/, AndroidManifest.xml, proguard.txt,"
                + " assets/, libs/). Dependencies are read from the class files themselves, a missing"
                + " classes.dex is generated, and no JSON manifest is needed.");
        storeDescription.setPadding(padding, 0, padding, padding / 2);
        container.addView(storeDescription);

        storeStatus = new TextView(this);
        storeStatus.setPadding(padding, 0, padding, padding / 2);
        container.addView(storeStatus);

        storeImportButton = new MaterialButton(this);
        storeImportButton.setText("Import Jetpack dependency ZIP");
        container.addView(storeImportButton);
        storeImportButton.setOnClickListener(v -> pickStoreZip());

        MaterialButton cancelButton = new MaterialButton(this);
        cancelButton.setText("Cancel import");
        cancelButton.setVisibility(View.GONE);
        container.addView(cancelButton);

        if (scId != null) {
            // The runtime override only makes sense per project: it replaces what the app ships for
            // this build, so it is not a global switch.
            ItemComposeDependencyBinding overrideBinding = ItemComposeDependencyBinding.inflate(
                    LayoutInflater.from(this), container, false);
            overrideBinding.dependencyName.setText("Use this ZIP's Kotlin runtime");
            overrideBinding.dependencyCoordinate.setText("Import kotlin-stdlib / kotlinx-coroutines from"
                    + " the ZIP and let them replace the app's built-in copies. Only enable this when the"
                    + " ZIP carries a newer runtime than the app ships — two copies of one Kotlin runtime"
                    + " in an APK crash far from their cause.");
            overrideBinding.dependencyTag.setText("ADVANCED");
            overrideBinding.dependencySwitch.setChecked(useZipKotlinRuntime);
            overrideBinding.dependencySwitch.setOnCheckedChangeListener(
                    (view, checked) -> useZipKotlinRuntime = checked);
            overrideBinding.cardDependency.setOnClickListener(view ->
                    overrideBinding.dependencySwitch.toggle());
            container.addView(overrideBinding.getRoot());
        }

        storeList = new LinearLayout(this);
        storeList.setOrientation(LinearLayout.VERTICAL);
        container.addView(storeList);

        cancelButton.setOnClickListener(v -> {
            JetpackLibsInstaller.cancel();
            storeStatus.setText("Cancelling import…");
        });

        refreshStoreList();
    }

    private void pickStoreZip() {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"zip"});
        options.setTitle("Select Jetpack dependency ZIP");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                startStoreImport(file);
            }
        }).show(getSupportFragmentManager(), "jetpack_zip_picker");
    }

    private void startStoreImport(File zip) {
        storeImportButton.setEnabled(false);
        storeStatus.setText("Importing " + zip.getName() + "…");
        JetpackLibsInstaller.install(zip, useZipKotlinRuntime, new JetpackLibsInstaller.Listener() {
            @Override
            public void onStage(String message, int percent) {
                runOnUiThread(() -> storeStatus.setText(message));
            }

            @Override
            public void onFinished(JetpackLibsInstaller.Report report) {
                runOnUiThread(() -> {
                    storeImportButton.setEnabled(true);
                    StringBuilder text = new StringBuilder(report.summary());
                    for (String warning : report.warnings) {
                        text.append("\n").append(warning);
                    }
                    storeStatus.setText(text);
                    refreshStoreList();
                    if (scId != null && !report.names.isEmpty()) {
                        // A freshly imported artifact is what the project needs to build against, so
                        // activate everything the import installed instead of leaving the user to guess.
                        enabledStoreArtifacts.addAll(report.names);
                        storeSelectionChanged = true;
                    }
                });
            }

            @Override
            public void onFailed(String message) {
                runOnUiThread(() -> {
                    storeImportButton.setEnabled(true);
                    storeStatus.setText("Import failed: " + message);
                });
            }
        });
    }

    private void refreshStoreList() {
        if (storeList == null) return;
        storeList.removeAllViews();

        List<JetpackLibs.Entry> entries = JetpackLibs.installed();
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nothing installed yet.");
            int pad = (int) (getResources().getDisplayMetrics().density * 16);
            empty.setPadding(pad, pad / 2, pad, pad / 2);
            storeList.addView(empty);
            return;
        }

        Set<String> active = scId == null ? Collections.emptySet() : activeStoreArtifacts();
        for (JetpackLibs.Entry entry : entries) {
            ItemComposeDependencyBinding itemBinding = ItemComposeDependencyBinding.inflate(
                    LayoutInflater.from(this), storeList, false);
            itemBinding.dependencyName.setText(entry.id);
            itemBinding.dependencyCoordinate.setText(entry.describe());
            itemBinding.dependencyTag.setText(entry.hasDex ? "READY" : "NO DEX");
            itemBinding.dependencySwitch.setEnabled(scId != null);
            itemBinding.dependencySwitch.setChecked(scId != null && active.contains(entry.id));
            itemBinding.dependencySwitch.setOnCheckedChangeListener((view, checked) -> {
                if (scId == null) return;
                if (checked) enabledStoreArtifacts.add(entry.id);
                else enabledStoreArtifacts.remove(entry.id);
                storeSelectionChanged = true;
            });
            itemBinding.cardDependency.setOnClickListener(view -> {
                if (scId != null) itemBinding.dependencySwitch.toggle();
            });
            storeList.addView(itemBinding.getRoot());
        }
    }

    /** The store artifacts this project has activated, including the expanded sub-dependencies. */
    private Set<String> activeStoreArtifacts() {
        Set<String> installed = JetpackLibs.installedIds();
        Set<String> active = new LinkedHashSet<>();
        for (HashMap<String, Object> library : LocalLibrariesUtil.getLocalLibraries(scId)) {
            Object name = library.get("name");
            if (name instanceof String && installed.contains(name)) active.add((String) name);
        }
        return active;
    }

    /**
     * Writes the activated artifacts into the project's local library list, together with the
     * dependencies each of them needs. The library directories themselves stay untouched in the store,
     * so ten projects using Compose still read one copy of every artifact from disk.
     */
    private void applyStoreSelection() {
        Set<String> installed = JetpackLibs.installedIds();
        ArrayList<HashMap<String, Object>> used = LocalLibrariesUtil.getLocalLibraries(scId);
        used.removeIf(library -> installed.contains(String.valueOf(library.get("name"))));

        // An empty selection is a decision too: it means this project builds without the store.
        for (String id : enabledStoreArtifacts) {
            File directory = LocalLibrariesUtil.getLocalLibraryDirectory(id);
            LocalLibrary library = LocalLibrary.fromFile(directory);
            used.add(LocalLibrariesUtil.createLibraryMap(id, null));
            for (String subDependency : library.getSubDependencyNames()) {
                boolean alreadyListed = false;
                for (HashMap<String, Object> entry : used) {
                    if (subDependency.equals(entry.get("name"))) {
                        alreadyListed = true;
                        break;
                    }
                }
                if (!alreadyListed) {
                    used.add(LocalLibrariesUtil.createLibraryMap(subDependency, null));
                }
            }
        }
        LocalLibrariesUtil.rewriteLocalLibFile(scId, new Gson().toJson(used));
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

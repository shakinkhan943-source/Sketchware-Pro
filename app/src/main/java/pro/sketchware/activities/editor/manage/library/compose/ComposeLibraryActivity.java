package pro.sketchware.activities.editor.manage.library.compose;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
import pro.sketchware.core.project.ProjectDataManager;
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
 * The shared Jetpack store: pick a dependency ZIP once, and its artifacts are installed for every
 * project on the device.
 *
 * <p>Opened from a project's library list this screen also decides which installed artifacts that
 * project activates; opened from App Settings it manages the store itself (import, inspect, delete).
 * The two modes deliberately differ: activation is per project because a project's code decides which
 * libraries it can compile against, while the files themselves must never be copied per project — that
 * is what makes one extraction enough for the whole device.</p>
 */
public class ComposeLibraryActivity extends BaseAppCompatActivity {

    private ManageLibraryComposeBinding binding;
    private ProjectLibraryBean composeLibraryBean;
    private final Set<String> selectedOptionalFeatures = new HashSet<>();
    /** True when opened from App Settings, where there is no project to activate anything for. */
    private boolean packageSettingsMode;
    private String scId;
    /** Artifacts of the shared Jetpack store this project activates. */
    private final Set<String> enabledStoreArtifacts = new LinkedHashSet<>();
    private final List<JetpackLibs.Entry> storeEntries = new ArrayList<>();
    private LinearLayout storeList;
    private TextView storeStatus;
    private MaterialButton storeImportButton;
    private MaterialButton storeRescanButton;
    private MaterialButton storeCancelButton;
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
            setupStoreSettings();
            return;
        }

        selectedOptionalFeatures.addAll(
                new ComposeBuiltInLibraryManager(composeLibraryBean).getOptionalFeatureIds());

        binding.composeSwitch.setChecked(composeLibraryBean.isEnabled());
        // The old ZIP-and-JSON bundle is still honoured when it is configured, but its absence no longer
        // blocks this screen: the shared store is the way to add Compose now, and that is chosen here.
        binding.composeDependenciesSection.setVisibility(View.VISIBLE);
        if (ComposeBuiltInLibraries.isBundleAvailable()) populateLegacyFeatures();
        binding.layoutSwitchCompose.setOnClickListener(v ->
                binding.composeSwitch.setChecked(!binding.composeSwitch.isChecked()));
        binding.composeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !JetpackLibs.isInstalled()
                    && !ComposeBuiltInLibraries.isBundleAvailable()) {
                Toast.makeText(this, "Nothing is installed in the shared Jetpack store yet."
                                + " Import your Compose ZIP here first — one import covers every project.",
                        Toast.LENGTH_LONG).show();
            }
        });

        // Start from what the project already activated, so leaving a row untouched cannot silently drop
        // it from the project's library list when this screen closes.
        enabledStoreArtifacts.addAll(activeStoreArtifacts());
        addStoreSection();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                persistComposeState();
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

    /**
     * Copies the switch and optional-feature choices onto the Compose library bean so a caller that
     * saves the bean persists exactly what the user sees on screen.
     */
    private void persistComposeState() {
        composeLibraryBean.useYn = binding.composeSwitch.isChecked()
                ? ProjectLibraryBean.LIB_USE_Y
                : ProjectLibraryBean.LIB_USE_N;
        new ComposeBuiltInLibraryManager(composeLibraryBean)
                .setOptionalFeatureIds(new ArrayList<>(selectedOptionalFeatures));
    }

    /**
     * Persists the activation when this screen is left by any route, not only by the back arrow: a
     * project's library list is a file, and losing a selection because the app was swiped away mid-import
     * would look exactly like a switch that does nothing.
     */
    @Override
    public void onPause() {
        super.onPause();
        if (packageSettingsMode || scId == null) return;
        // The enable flag is written to the project the same way the store selection is: leaving the
        // screen without pressing the back arrow (home button, app switch, activity destruction) must not
        // silently re-disable Compose the next time the screen is opened.
        persistComposeState();
        ProjectDataManager.getLibraryManager(scId).setCompose(composeLibraryBean);
        // Re-applied even when nothing was toggled: a re-scan can turn an artifact's dependency list from
        // empty into the closure its classes actually need, and a project holding the old root-only list
        // would keep failing to compile against exactly the folders that are now correctly recorded.
        // Rewriting the same selection is cheap and idempotent — it never touches non-store libraries.
        applyStoreSelection();
        storeSelectionChanged = false;
    }

    /**
     * App Settings view of the store: install, inspect and remove artifacts. Nothing can be activated
     * here, because activation is a decision about one project's code; the rows therefore say what is
     * available to every project and open their details on tap.
     */
    private void setupStoreSettings() {
        binding.toolbar.setTitle("Jetpack library store");
        binding.layoutSwitchCompose.setVisibility(View.GONE);
        binding.composeDependenciesSection.setVisibility(View.VISIBLE);
        binding.composeDependenciesContainer.removeAllViews();

        addStoreSection();
        addLegacyBundleRow();
    }

    /**
     * A bundle configured through the older flow still drives the build, so removing the pickers must not
     * leave it stranded: it gets one row explaining it and one button that retires it.
     */
    private void addLegacyBundleRow() {
        if (!ComposeDependencyManager.isConfigured()) return;

        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        TextView legacy = new TextView(this);
        legacy.setText("A ZIP-and-JSON Compose bundle is still configured for all projects. It works, but"
                + " it is no longer needed: the store above replaces it, and the store can read the same"
                + " ZIP without a manifest. Removing the bundle here does not delete anything you packed.");
        legacy.setPadding(padding, padding, padding, padding / 2);
        binding.composeDependenciesContainer.addView(legacy);

        MaterialButton remove = new MaterialButton(this);
        remove.setText("Remove the old bundle");
        binding.composeDependenciesContainer.addView(remove);
        remove.setOnClickListener(v -> {
            ComposeDependencyManager.clear();
            legacy.setVisibility(View.GONE);
            remove.setVisibility(View.GONE);
            Toast.makeText(this, "Old Compose bundle removed — install the ZIP into the store instead",
                    Toast.LENGTH_LONG).show();
        });
    }

    /**
     * The shared Jetpack store section: import a dependency ZIP, see what it installed, and (in a
     * project) choose which artifacts this project activates. Installed artifacts live once under
     * {@code .sketchware/libs/JetpackLibs}, so activating them here only records names in the project's
     * local library list — no copies per project, and no cache directory the system may clear.
     */
    private void addStoreSection() {
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        LinearLayout container = binding.composeDependenciesContainer;

        TextView storeTitle = new TextView(this);
        storeTitle.setText("Shared Jetpack library store");
        storeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        storeTitle.setPadding(padding, padding, padding, 0);
        container.addView(storeTitle);

        TextView storeDescription = new TextView(this);
        storeDescription.setText("Pick a ZIP that contains one folder per artifact (classes.jar, and"
                + " optionally classes.dex, res/, AndroidManifest.xml, proguard.txt, assets/, libs/)."
                + " The folder names are the whole manifest: dependencies are read from the class files"
                + " themselves, a missing classes.dex is generated, and an AAR or a jar under another name"
                + " is unpacked and renamed. Files are shared by every project, so import once.");
        storeDescription.setPadding(padding, 0, padding, padding / 2);
        container.addView(storeDescription);

        storeStatus = new TextView(this);
        storeStatus.setPadding(padding, 0, padding, padding / 2);
        container.addView(storeStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(padding, 0, padding, padding / 2);
        container.addView(actions);

        storeImportButton = new MaterialButton(this);
        storeImportButton.setText("Import ZIP");
        actions.addView(storeImportButton);
        storeImportButton.setOnClickListener(v -> {
            if (JetpackLibsInstaller.isRunning()) {
                Toast.makeText(this, "The store is busy — wait for the current import to finish",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pickStoreZip();
        });

        storeRescanButton = new MaterialButton(this);
        storeRescanButton.setText("Re-scan");
        actions.addView(storeRescanButton);
        storeRescanButton.setOnClickListener(v -> {
            if (JetpackLibsInstaller.isRunning()) return;
            startStoreRescan();
        });

        storeCancelButton = new MaterialButton(this);
        storeCancelButton.setText("Cancel");
        storeCancelButton.setVisibility(View.GONE);
        actions.addView(storeCancelButton);
        storeCancelButton.setOnClickListener(v -> {
            JetpackLibsInstaller.cancel();
            storeStatus.setText("Cancelling…");
        });

        if (packageSettingsMode) {
            MaterialButton clear = new MaterialButton(this);
            clear.setText("Delete everything");
            actions.addView(clear);
            clear.setOnClickListener(v -> confirmDelete(allStoreIds(), "the whole store"));
        }

        if (!packageSettingsMode) {
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

        refreshStoreList();
    }

    private List<String> allStoreIds() {
        List<String> ids = new ArrayList<>();
        for (JetpackLibs.Entry entry : storeEntries) ids.add(entry.id);
        return ids;
    }

    private void pickStoreZip() {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"zip"});
        options.setTitle("Select Jetpack dependency ZIP");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                storeImportButton.setEnabled(false);
                storeRescanButton.setEnabled(false);
                storeCancelButton.setVisibility(View.VISIBLE);
                storeStatus.setText("Importing " + file.getName() + "…");
                JetpackLibsInstaller.install(file, useZipKotlinRuntime, storeListener(true));
            }
        }).show(getSupportFragmentManager(), "jetpack_zip_picker");
    }

    /** Re-derives the store's metadata in place, so an edited or half-read folder needs no re-import. */
    private void startStoreRescan() {
        storeImportButton.setEnabled(false);
        storeRescanButton.setEnabled(false);
        storeCancelButton.setVisibility(View.VISIBLE);
        storeStatus.setText("Re-reading the store…");
        JetpackLibsInstaller.rescan(storeListener(false));
    }

    private JetpackLibsInstaller.Listener storeListener(boolean isImport) {
        return new JetpackLibsInstaller.Listener() {
            @Override
            public void onStage(String message, int percent) {
                runOnUiThread(() -> storeStatus.setText(message));
            }

            @Override
            public void onFinished(JetpackLibsInstaller.Report report) {
                runOnUiThread(() -> {
                    storeImportButton.setEnabled(true);
                    storeRescanButton.setEnabled(true);
                    storeCancelButton.setVisibility(View.GONE);
                    StringBuilder text = new StringBuilder(report.summary());
                    for (String warning : report.warnings) text.append("\n").append(warning);
                    storeStatus.setText(text);
                    refreshStoreList();
                    if (isImport && !packageSettingsMode && !report.names.isEmpty()) {
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
                    storeRescanButton.setEnabled(true);
                    storeCancelButton.setVisibility(View.GONE);
                    storeStatus.setText("Failed: " + message);
                    refreshStoreList();
                });
            }
        };
    }

    private void refreshStoreList() {
        if (storeList == null) return;
        storeList.removeAllViews();
        storeEntries.clear();
        storeEntries.addAll(JetpackLibs.installed());

        if (storeEntries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(packageSettingsMode
                    ? "Nothing installed yet. Import a ZIP and its folders become libraries you can switch"
                        + " on in any project."
                    : "Nothing installed yet. Import a ZIP here, or install one from App Settings →"
                        + " Jetpack library store, then switch its artifacts on below.");
            int pad = (int) (getResources().getDisplayMetrics().density * 16);
            empty.setPadding(pad, pad / 2, pad, pad / 2);
            storeList.addView(empty);
            return;
        }

        int edges = 0;
        for (JetpackLibs.Entry entry : storeEntries) edges += entry.edges;
        TextView summary = new TextView(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        summary.setText(storeEntries.size() + " artifacts · " + edges + " dependency edges"
                + (packageSettingsMode ? " · tap a row to inspect or delete it"
                        : " · switch on what this project should build with; the choice is saved when you"
                            + " leave this screen"));
        summary.setPadding(pad / 2, pad / 2, pad, pad / 4);
        storeList.addView(summary);

        Set<String> active = packageSettingsMode
                ? Collections.<String>emptySet() : activeStoreArtifacts();
        for (JetpackLibs.Entry entry : storeEntries) {
            ItemComposeDependencyBinding itemBinding = ItemComposeDependencyBinding.inflate(
                    LayoutInflater.from(this), storeList, false);
            itemBinding.dependencyName.setText(entry.id);
            itemBinding.dependencyCoordinate.setText(entry.describe());
            itemBinding.dependencyTag.setText(entry.note != null ? "CHECK"
                    : (entry.hasDex ? "READY" : "NO DEX"));
            itemBinding.dependencySwitch.setVisibility(packageSettingsMode ? View.GONE : View.VISIBLE);
            itemBinding.dependencySwitch.setChecked(!packageSettingsMode && active.contains(entry.id));
            itemBinding.dependencySwitch.setOnCheckedChangeListener((view, checked) -> {
                if (packageSettingsMode) return;
                if (checked) enabledStoreArtifacts.add(entry.id);
                else enabledStoreArtifacts.remove(entry.id);
                storeSelectionChanged = true;
            });
            itemBinding.cardDependency.setOnClickListener(view -> {
                if (packageSettingsMode) {
                    showStoreEntry(entry);
                    return;
                }
                itemBinding.dependencySwitch.toggle();
            });
            itemBinding.cardDependency.setOnLongClickListener(view -> {
                confirmDelete(Collections.singletonList(entry.id), entry.id);
                return true;
            });
            storeList.addView(itemBinding.getRoot());
        }
    }

    /**
     * Everything the store knows about one artifact, including the generated files. Shown from App
     * Settings, where a row cannot be switched on: a user who reports "it lists no dependencies" needs to
     * see the detection numbers rather than guess them.
     */
    private void showStoreEntry(JetpackLibs.Entry entry) {
        StringBuilder text = new StringBuilder();
        text.append(entry.describe()).append("\n\n");
        text.append("resources: ").append(entry.hasResources ? "yes" : "no")
                .append("  ·  proguard rules: ").append(entry.hasProguard ? "yes" : "no")
                .append("  ·  size: ").append(entry.sizeBytes / 1024).append(" KB\n");
        File directory = JetpackLibs.directoryOf(entry.id);
        if (directory != null) {
            text.append("\n").append(directory.getAbsolutePath()).append("\n");
            appendFile(text, new File(directory, "jetpack-info.json"), "metadata");
            appendFile(text, new File(directory, "dependency-tree.json"), "generated dependencies");
        }
        ScrollView scroll = new ScrollView(this);
        TextView content = new TextView(this);
        content.setTextIsSelectable(true);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        content.setPadding(padding, padding, padding, padding);
        content.setText(text);
        scroll.addView(content);
        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.id)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNegativeButton("Delete", (dialog, which) ->
                        confirmDelete(Collections.singletonList(entry.id), entry.id))
                .show();
    }

    private void appendFile(StringBuilder text, File file, String title) {
        if (file == null || !file.isFile()) return;
        String content = pro.sketchware.util.FileUtil.readFile(file.getAbsolutePath());
        if (content == null) return;
        if (content.length() > 2000) content = content.substring(0, 2000) + "\n…";
        text.append("\n").append(title).append(":\n").append(content).append("\n");
    }

    private void confirmDelete(List<String> ids, String what) {
        if (ids.isEmpty()) {
            Toast.makeText(this, "The store is already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + what + "?")
                .setMessage(ids.size() + " artifact folder(s) are deleted from the shared store. Any"
                        + " project that switched them on must re-check its Compose library list.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    storeImportButton.setEnabled(false);
                    storeStatus.setText("Deleting…");
                    JetpackLibsInstaller.uninstall(ids, new JetpackLibsInstaller.Listener() {
                        @Override
                        public void onStage(String message, int percent) {
                            runOnUiThread(() -> storeStatus.setText(message));
                        }

                        @Override
                        public void onFinished(JetpackLibsInstaller.Report report) {
                            runOnUiThread(() -> {
                                storeImportButton.setEnabled(true);
                                if (!packageSettingsMode) {
                                    enabledStoreArtifacts.removeAll(ids);
                                    storeSelectionChanged = true;
                                }
                                StringBuilder text = new StringBuilder(report.summary());
                                for (String warning : report.warnings) text.append("\n").append(warning);
                                storeStatus.setText(text);
                                refreshStoreList();
                            });
                        }

                        @Override
                        public void onFailed(String message) {
                            runOnUiThread(() -> {
                                storeImportButton.setEnabled(true);
                                storeStatus.setText("Delete failed: " + message);
                            });
                        }
                    });
                })
                .setNegativeButton("Keep", null)
                .show();
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
     * dependencies each of them needs. The library directories themselves stay untouched in the store, so
     * ten projects using Compose still read one copy of every artifact from disk.
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

    /** The legacy ZIP-and-JSON bundle's feature list, kept for projects that still use that bundle. */
    private void populateLegacyFeatures() {
        LinearLayout container = binding.composeDependenciesContainer;

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

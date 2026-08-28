package pro.sketchware.activities.design.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.activities.base.BaseFragment;
import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.core.async.BackgroundTasks;
import pro.sketchware.core.async.TaskHost;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.core.project.ProjectDataStore;
import pro.sketchware.core.sync.JavaSyncApplier;
import pro.sketchware.core.sync.JavaSyncManager;
import pro.sketchware.core.sync.MappedSource;
import pro.sketchware.core.sync.SyncPlan;
import pro.sketchware.databinding.FrJavaEditorBinding;
import pro.sketchware.lib.code_editor.CodeEditorPreferences;
import pro.sketchware.util.EditorUtils;
import pro.sketchware.util.Helper;
import pro.sketchware.util.LogUtil;
import pro.sketchware.util.SketchwareUtil;

/**
 * The "Java" tab of the project editor.
 * <p>
 * It shows the <b>complete</b> generated source of the currently selected Activity in the regular
 * Sketchware Pro code editor (sora-editor, same widget and preferences as every other code editor
 * of the app) and lets the user edit it directly.
 * <p>
 * Saving runs the synchronization layer:
 * <ul>
 *     <li>edits inside code that came from a block update or remove that block,</li>
 *     <li>code that has no block behind it is kept as user-managed code,</li>
 *     <li>generated framework code is protected,</li>
 *     <li>conflicts (blocks and Java changed at the same time) are reported and resolved by the
 *     user instead of silently overwriting anything.</li>
 * </ul>
 *
 * @see JavaSyncManager
 */
public class JavaEditorFragment extends BaseFragment {

    private FrJavaEditorBinding binding;
    private CodeEditorPreferences editorPrefs;
    private String scId;
    private ProjectFileBean projectFile;
    /**
     * The mapping of the source currently displayed. Base of every reverse synchronization.
     */
    private MappedSource baseline;
    private String loadedJavaName;
    private boolean loading;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FrJavaEditorBinding.inflate(inflater, container, false);
        scId = savedInstanceState != null ? savedInstanceState.getString("sc_id")
                : requireActivity().getIntent().getStringExtra("sc_id");

        binding.editor.setTypefaceText(EditorUtils.getTypeface(requireContext()));
        // Language-aware: default to Java, will be updated when projectFile is set
        EditorUtils.loadJavaConfig(binding.editor);
        editorPrefs = new CodeEditorPreferences(requireContext(), "java_tab");
        editorPrefs.applyToEditor(binding.editor, false);

        binding.btnSync.setOnClickListener(v -> synchronizeNow());
        binding.btnReload.setOnClickListener(v -> confirmReload());
        setStatus(Helper.getResString(R.string.java_editor_status_loading));
        return binding.getRoot();
    }

    private void updateEditorLanguage() {
        if (binding == null || projectFile == null) return;
        try {
            if (projectFile.isKotlin()) {
                EditorUtils.loadKotlinConfig(binding.editor);
            } else {
                EditorUtils.loadJavaConfig(binding.editor);
            }
            if (editorPrefs != null) {
                editorPrefs.applyToEditor(binding.editor, false);
            }
        } catch (Exception e) {
            // Fallback to Java config if Kotlin fails
            try {
                EditorUtils.loadJavaConfig(binding.editor);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("sc_id", scId);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.editor.release();
        }
        binding = null;
        super.onDestroyView();
    }

    /**
     * Called by {@link pro.sketchware.activities.design.DesignActivity} whenever the selected
     * Activity changes, so the tab always shows the source of the current Activity.
     * Language-aware: supports both Java and Kotlin activities.
     */
    public void setProjectFile(ProjectFileBean projectFileBean) {
        projectFile = projectFileBean;
        if (binding != null && projectFile != null) {
            updateEditorLanguage();
            String displayName = projectFile.isKotlin() ? projectFile.getSourceFileName() : projectFile.getJavaName();
            if (loadedJavaName == null) {
                binding.fileName.setText(displayName);
            }
            if (!projectFile.getJavaName().equals(loadedJavaName)) {
                reloadKeepingEdits(false);
            } else {
                // Even if same file, update label for language change
                binding.fileName.setText(displayName);
            }
        }
    }

    /**
     * Refreshes the editor content from the blocks (blocks → Java direction).
     * <p>
     * Unsaved Java edits are never thrown away silently: the user is asked first.
     */
    public void refresh() {
        reloadKeepingEdits(false);
    }

    private void confirmReload() {
        reloadKeepingEdits(true);
    }

    /**
     * Regenerates the source, but asks before discarding edits the user did not synchronize yet.
     */
    private void reloadKeepingEdits(boolean showToast) {
        if (binding == null) {
            return;
        }
        if (!hasUnsavedChanges()) {
            reload(showToast);
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.java_editor_action_reload)
                .setMessage(R.string.java_editor_message_discard_changes)
                .setPositiveButton(R.string.common_word_yes, (dialog, which) -> reload(showToast))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private boolean hasUnsavedChanges() {
        return baseline != null && binding != null
                && !binding.editor.getText().toString().equals(baseline.getText());
    }

    private void reload(boolean showToast) {
        if (binding == null || projectFile == null || scId == null || loading) {
            return;
        }
        loading = true;
        final ProjectFileBean file = projectFile;
        setStatus(Helper.getResString(R.string.java_editor_status_loading));
        binding.editor.setEditable(false);
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "JavaEditorFragment",
                () -> JavaSyncManager.loadSource(requireContext().getApplicationContext(), scId, file),
                mapped -> {
                    loading = false;
                    if (binding == null) {
                        return;
                    }
                    baseline = mapped;
                    loadedJavaName = file.getJavaName();
                    binding.editor.setText(mapped.getText());
                    binding.editor.setEditable(true);
                    String displayName = file.isKotlin() ? file.getSourceFileName() : file.getJavaName();
                    binding.fileName.setText(displayName);
                    updateEditorLanguage();
                    setStatus(describeMapping(mapped));
                    if (showToast) {
                        SketchwareUtil.toast(Helper.getResString(R.string.java_editor_message_reloaded));
                    }
                    if (!mapped.unanchoredChunkIds.isEmpty()) {
                        SketchwareUtil.toast(Helper.getResString(R.string.java_editor_message_unanchored_code));
                    }
                },
                error -> {
                    loading = false;
                    LogUtil.e("JavaEditorFragment", "Failed to generate Java source", error);
                    if (binding == null) {
                        return;
                    }
                    binding.editor.setEditable(true);
                    setStatus(Helper.getResString(R.string.design_error_generate_source));
                    SketchwareUtil.toast(Helper.getResString(R.string.design_error_generate_source));
                });
    }

    private String describeMapping(MappedSource mapped) {
        int blocks = mapped.blockRegions().size();
        int manual = mapped.chunks.size();
        return String.format(Helper.getResString(R.string.java_editor_status_mapped), blocks, manual);
    }

    /**
     * Java → blocks: analyses the editor content and applies the result after asking the user
     * whenever the automatic resolution would not be safe.
     */
    private void synchronizeNow() {
        if (binding == null || baseline == null || projectFile == null || loading) {
            return;
        }
        final ProjectFileBean file = projectFile;
        final MappedSource base = baseline;
        final String edited = binding.editor.getText().toString();
        if (edited.equals(base.getText())) {
            SketchwareUtil.toast(Helper.getResString(R.string.java_editor_message_nothing_to_sync));
            return;
        }
        loading = true;
        setStatus(Helper.getResString(R.string.java_editor_status_syncing));
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "JavaEditorFragment",
                () -> JavaSyncManager.analyze(requireContext().getApplicationContext(), scId, file, base, edited),
                plan -> {
                    loading = false;
                    if (binding == null) {
                        return;
                    }
                    if (!plan.reliable) {
                        setStatus(Helper.getResString(R.string.java_editor_status_unreliable));
                        new MaterialAlertDialogBuilder(requireActivity())
                                .setTitle(R.string.java_editor_title_cannot_sync)
                                .setMessage(R.string.java_editor_message_cannot_sync)
                                .setPositiveButton(R.string.common_word_ok, null)
                                .show();
                        return;
                    }
                    if (plan.requiresConfirmation()) {
                        askAndApply(file, plan);
                    } else {
                        applyPlan(file, plan, JavaSyncApplier.ConflictResolution.KEEP_JAVA, true);
                    }
                },
                error -> {
                    loading = false;
                    LogUtil.e("JavaEditorFragment", "Failed to analyse Java edits", error);
                    if (binding == null) {
                        return;
                    }
                    setStatus(Helper.getResString(R.string.java_editor_status_sync_failed));
                    SketchwareUtil.toast(Helper.getResString(R.string.java_editor_status_sync_failed));
                });
    }

    private void askAndApply(ProjectFileBean file, SyncPlan plan) {
        StringBuilder message = new StringBuilder();
        if (!plan.conflicts.isEmpty()) {
            message.append(Helper.getResString(R.string.java_editor_message_conflicts)).append("\n");
            for (SyncPlan.Conflict conflict : plan.conflicts) {
                message.append("• ").append(conflict.describe()).append('\n');
            }
            message.append('\n');
        }
        List<String> conversions = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        for (SyncPlan.BlockChange change : plan.blockChanges) {
            if (change.type == SyncPlan.ChangeType.CONVERT_TO_SOURCE_BLOCK) {
                conversions.add("• " + change.describe());
            } else if (change.type == SyncPlan.ChangeType.DELETE_BLOCK) {
                removals.add("• " + change.describe());
            }
        }
        if (!conversions.isEmpty()) {
            message.append(Helper.getResString(R.string.java_editor_message_conversions)).append('\n');
            message.append(String.join("\n", conversions)).append("\n\n");
        }
        if (!removals.isEmpty()) {
            message.append(Helper.getResString(R.string.java_editor_message_removals)).append('\n');
            message.append(String.join("\n", removals)).append("\n\n");
        }
        if (plan.rejectedFrameworkEdits > 0) {
            message.append(String.format(
                    Helper.getResString(R.string.java_editor_message_rejected_framework),
                    plan.rejectedFrameworkEdits)).append('\n');
        }

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.java_editor_title_review_changes)
                .setMessage(message.toString().trim())
                .setPositiveButton(R.string.java_editor_action_keep_java,
                        (dialog, which) -> applyPlan(file, plan, JavaSyncApplier.ConflictResolution.KEEP_JAVA, true))
                .setNeutralButton(R.string.java_editor_action_keep_blocks,
                        (dialog, which) -> applyPlan(file, plan, JavaSyncApplier.ConflictResolution.KEEP_BLOCKS, false))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void applyPlan(ProjectFileBean file, SyncPlan plan,
                           JavaSyncApplier.ConflictResolution resolution, boolean allowConversion) {
        loading = true;
        setStatus(Helper.getResString(R.string.java_editor_status_syncing));
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "JavaEditorFragment",
                () -> {
                    JavaSyncApplier.Report report = JavaSyncManager.apply(scId, file, plan, resolution, allowConversion);
                    ProjectDataStore dataStore = ProjectDataManager.getProjectDataManager(scId);
                    synchronized (dataStore) {
                        dataStore.saveAllBackup();
                    }
                    return report;
                },
                report -> {
                    loading = false;
                    if (binding == null) {
                        return;
                    }
                    SketchwareUtil.toast(describeReport(report));
                    // Re-generate so the editor shows exactly what the blocks produce now.
                    reload(false);
                },
                error -> {
                    loading = false;
                    LogUtil.e("JavaEditorFragment", "Failed to apply Java edits", error);
                    if (binding == null) {
                        return;
                    }
                    setStatus(Helper.getResString(R.string.java_editor_status_sync_failed));
                    SketchwareUtil.toast(Helper.getResString(R.string.java_editor_status_sync_failed));
                });
    }

    private String describeReport(JavaSyncApplier.Report report) {
        return String.format(Helper.getResString(R.string.java_editor_message_sync_report),
                report.updatedBlocks + report.convertedBlocks, report.removedBlocks,
                report.userCodeChunks, report.rejectedFrameworkEdits);
    }

    private void setStatus(String status) {
        if (binding != null) {
            binding.syncStatus.setText(status);
        }
    }
}

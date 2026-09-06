package pro.sketchware.activities.design.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import pro.sketchware.R;
import pro.sketchware.activities.base.BaseFragment;
import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.core.async.BackgroundTasks;
import pro.sketchware.core.async.TaskHost;
import pro.sketchware.core.sync.JavaSyncApplier;
import pro.sketchware.core.sync.JavaSyncManager;
import pro.sketchware.core.sync.MappedSource;
import pro.sketchware.databinding.FrJavaEditorBinding;
import pro.sketchware.lib.code_editor.CodeEditorPreferences;
import pro.sketchware.util.EditorUtils;
import pro.sketchware.util.Helper;
import pro.sketchware.util.LogUtil;
import pro.sketchware.util.SketchwareUtil;

/**
 * The "Java/Kotlin" tab of the project editor.
 * <p>
 * It shows the <b>complete</b> generated source of the currently selected Activity in the regular
 * Sketchware Pro code editor (sora-editor, same widget and preferences as every other code editor
 * of the app) and lets the user edit it directly.
 * <p>
 * Synchronization is no longer a separate action: it runs automatically whenever the project is
 * saved (manual save, save-and-exit and the automatic backup save). The reconciliation is loss free:
 * <ul>
 *     <li>edits inside code that came from a block update or remove that block,</li>
 *     <li>edits inside generated framework/core sections are kept as content-anchored overrides and
 *     never overwritten by a later generation,</li>
 *     <li>code that has no block behind it is kept as user-managed code,</li>
 *     <li>conflicts (blocks and Java changed at the same time) keep both sides: the block keeps its
 *     state and the Java edit stays visible as an override.</li>
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
    private String loadedSourceName;
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

        binding.btnReload.setOnClickListener(v -> confirmReload());
        setStatus(Helper.getResString(R.string.java_editor_status_loading));

        binding.editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!loading && binding != null) {
                binding.getRoot().post(() -> {
                    if (baseline != null && hasUnsavedChanges()) {
                        setStatus(Helper.getResString(R.string.java_editor_status_unsaved));
                    }
                });
            }
        });

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
            if (loadedSourceName == null) {
                binding.fileName.setText(displayName);
            }
            if (!projectFile.getSourceFileName().equals(loadedSourceName)) {
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
            reload(showToast, showToast);
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.java_editor_action_reload)
                .setMessage(R.string.java_editor_message_discard_changes)
                .setPositiveButton(R.string.common_word_yes, (dialog, which) -> reload(showToast, showToast))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private boolean hasUnsavedChanges() {
        return baseline != null && binding != null
                && !binding.editor.getText().toString().equals(baseline.getText());
    }

    /**
     * Regenerates the source from the blocks and injects the persisted user layers.
     *
     * @param resetManualMode {@code true} only for the explicit "Reload from blocks" action: leaves
     *                        whole-file manual mode, which the user confirmed to discard.
     */
    private void reload(boolean showToast, boolean resetManualMode) {
        if (binding == null || projectFile == null || scId == null || loading) {
            return;
        }
        loading = true;
        final ProjectFileBean file = projectFile;
        setStatus(Helper.getResString(R.string.java_editor_status_loading));
        binding.editor.setEditable(false);
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "JavaEditorFragment",
                () -> {
                    if (resetManualMode) {
                        JavaSyncManager.clearWholeSourceMode(scId, file.getJavaName());
                    }
                    return JavaSyncManager.loadSource(requireContext().getApplicationContext(), scId, file);
                },
                mapped -> {
                    loading = false;
                    if (binding == null) {
                        return;
                    }
                    baseline = mapped;
                    loadedSourceName = file.getSourceFileName();
                    binding.editor.setText(mapped.getText());
                    binding.editor.setEditable(true);
                    String displayName = file.isKotlin() ? file.getSourceFileName() : file.getJavaName();
                    binding.fileName.setText(displayName);
                    updateEditorLanguage();
                    setStatus(describeMapping(mapped));
                    if (showToast) {
                        SketchwareUtil.toast(Helper.getResString(R.string.java_editor_message_reloaded));
                    }
                    if (!mapped.unanchoredChunkIds.isEmpty() || !mapped.unanchoredOverrideIds.isEmpty()) {
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
        if (mapped.wholeSource) {
            return Helper.getResString(R.string.java_editor_status_manual_file);
        }
        int blocks = mapped.blockRegions().size();
        int manual = mapped.chunks.size();
        return String.format(Helper.getResString(R.string.java_editor_status_mapped), blocks, manual);
    }

    /**
     * Synchronizes unsaved editor edits and refreshes the editor with the final canonical source.
     * <p>
     * Called by {@link pro.sketchware.activities.design.DesignActivity} before every save, so
     * Save always stores a consistent blocks ↔ code ↔ user-edit state. If the user keeps typing
     * while the sync runs, their newer text is preserved (it will be synced on the next save).
     *
     * @param onComplete invoked on the main thread after synchronization finished (never dropped,
     *                   even on failure — the save itself can still proceed)
     */
    public void synchronizeForSave(Runnable onComplete) {
        if (onComplete == null) {
            return;
        }
        if (binding == null || projectFile == null || scId == null || baseline == null) {
            onComplete.run();
            return;
        }
        if (loading) {
            // A reload/sync is already running; capture the text after it finishes is complex, so
            // let the save proceed. The in-flight sync still applies the editor's edits.
            onComplete.run();
            return;
        }
        if (!hasUnsavedChanges()) {
            onComplete.run();
            return;
        }
        final ProjectFileBean file = projectFile;
        final MappedSource base = baseline;
        final String edited = binding.editor.getText().toString();
        final Context appContext = requireContext().getApplicationContext();
        loading = true;
        setStatus(Helper.getResString(R.string.java_editor_status_syncing));
        BindingGuard guard = new BindingGuard(edited);
        // The save chain (including the automatic backup save started from onSaveInstanceState)
        // must always complete, even when the Activity is already being destroyed, so delivery is
        // not gated on host aliveness. UI updates stay null-guarded.
        BackgroundTasks.callIoAlways("JavaEditorFragment",
                () -> JavaSyncManager.synchronize(appContext, scId, file, base, edited),
                outcome -> {
                    loading = false;
                    if (binding != null) {
                        setStatus(describeReport(outcome.report));
                        applyFinalSource(outcome.finalSource, file, guard);
                    }
                    onComplete.run();
                },
                error -> {
                    loading = false;
                    LogUtil.e("JavaEditorFragment", "Failed to synchronize Java edits", error);
                    if (binding != null) {
                        setStatus(Helper.getResString(R.string.java_editor_status_sync_failed));
                    }
                    onComplete.run();
                });
    }

    /**
     * Guards the editor content while a background sync is in flight: only overwrite the editor
     * with the canonical source when the user did not type anything new.
     */
    private static final class BindingGuard {
        private final String syncSource;

        BindingGuard(String syncSource) {
            this.syncSource = syncSource;
        }

        boolean stillMatches(String current) {
            return current != null && current.equals(syncSource);
        }
    }

    private void applyFinalSource(MappedSource finalSource, ProjectFileBean file, BindingGuard guard) {
        if (binding == null || finalSource == null) {
            return;
        }
        if (!guard.stillMatches(binding.editor.getText().toString())) {
            // User typed while synchronizing: keep their newest text; baseline is still the one we
            // synced, so the next save picks it up.
            baseline = finalSource;
            return;
        }
        baseline = finalSource;
        loadedSourceName = file.getSourceFileName();
        binding.editor.setText(finalSource.getText());
        String displayName = file.isKotlin() ? file.getSourceFileName() : file.getJavaName();
        binding.fileName.setText(displayName);
        updateEditorLanguage();
        if (!finalSource.unanchoredChunkIds.isEmpty() || !finalSource.unanchoredOverrideIds.isEmpty()) {
            SketchwareUtil.toast(Helper.getResString(R.string.java_editor_message_unanchored_code));
        }
    }

    private String describeReport(JavaSyncApplier.Report report) {
        return String.format(Helper.getResString(R.string.java_editor_message_sync_report),
                report.updatedBlocks + report.convertedBlocks, report.removedBlocks,
                report.userCodeChunks + report.lineOverrides, report.preservedGeneratedEdits);
    }

    private void setStatus(String status) {
        if (binding != null) {
            binding.syncStatus.setText(status);
        }
    }
}

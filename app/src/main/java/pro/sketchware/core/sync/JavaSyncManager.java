package pro.sketchware.core.sync;

import android.content.Context;

import java.util.List;

import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.core.project.ProjectDataStore;
import pro.sketchware.util.LogUtil;

/**
 * Entry point of the Java ↔ blocks synchronization layer.
 * <pre>
 *                    ┌────────────────────┐
 *                    │   Project Activity │
 *                    └─────────┬──────────┘
 *                              │
 *                  ┌───────────▼───────────┐
 *                  │  JavaSyncManager      │
 *                  └───────────┬───────────┘
 *                            ↙   ↘
 *                 ┌───────────┐   ┌──────────────┐
 *                 │  Blocks   │   │ Java editor  │
 *                 │  /Events  │   │ Full source  │
 *                 └───────────┘   └──────────────┘
 * </pre>
 * <ul>
 *     <li>Blocks → Java: {@link #loadSource} regenerates the Activity with the normal pipeline and
 *     re-injects manually written code and frame overrides.</li>
 *     <li>Java → Blocks: {@link #synchronize} maps the user's edits back onto the blocks, keeps
 *     framework edits as overrides and writes the final state. It is used by Save (manual and
 *     automatic), so saving always produces a consistent project.</li>
 *     <li>Build/export: {@link #injectUserCode} makes sure manually written code and overrides are
 *     part of the compiled source as well.</li>
 * </ul>
 * Everything is a no-op for projects that never used the Java editor.
 */
public final class JavaSyncManager {

    private JavaSyncManager() {
    }

    /**
     * Result of one synchronized save: what was applied plus the final source of the Activity.
     */
    public static class SyncOutcome {
        public final JavaSyncApplier.Report report;
        public final SyncPlan plan;
        public final MappedSource finalSource;

        SyncOutcome(JavaSyncApplier.Report report, SyncPlan plan, MappedSource finalSource) {
            this.report = report;
            this.plan = plan;
            this.finalSource = finalSource;
        }
    }

    /**
     * Generates the full Java/Kotlin source of an Activity including the ownership mapping.
     * Must be called from a background thread.
     */
    public static MappedSource loadSource(Context context, String scId, ProjectFileBean projectFile) {
        JavaSyncMetadata metadata = JavaSyncStore.load(scId, projectFile.getJavaName());
        return JavaSourceMapper.map(context, scId, projectFile, metadata);
    }

    /**
     * Compares the edited source with the blocks and returns what would change.
     * Must be called from a background thread.
     *
     * @param baseline the mapping that was shown to the user when the editor was filled
     * @param edited   the current editor content
     */
    public static SyncPlan analyze(Context context, String scId, ProjectFileBean projectFile,
                                   MappedSource baseline, String edited) {
        MappedSource current = loadSource(context, scId, projectFile);
        return JavaSyncEngine.analyze(baseline, edited, current);
    }

    /**
     * Synchronizes the current editor content with the blocks and persists the final state.
     * <p>
     * This is the single entry point used by the Save system: it reconciles blocks ↔ generated code
     * ↔ user-edited code in a loss-free way and then refreshes the source. The whole flow runs
     * off the main thread.
     *
     * @param baseline the mapping the editor currently shows
     * @param edited   the current editor content
     * @return the applied report, the plan and the final (canonical) source
     */
    public static SyncOutcome synchronize(Context context, String scId, ProjectFileBean projectFile,
                                          MappedSource baseline, String edited) {
        String javaName = projectFile.getJavaName();
        JavaSyncMetadata existing = JavaSyncStore.load(scId, javaName);

        SyncPlan plan;
        if (existing.isWholeSourceMode()) {
            // Manual whole-file mode: the user's text is already the source of truth, just update it.
            plan = new SyncPlan();
            plan.reliable = true;
            plan.wholeSource = edited;
            plan.wholeSourceBaseHash = JavaSourceMapper.hash(edited);
        } else {
            plan = analyze(context, scId, projectFile, baseline, edited);
        }

        JavaSyncApplier.Report report;
        if (!plan.reliable || !plan.wholeSource.isEmpty()) {
            report = JavaSyncApplier.applyWholeSource(scId, javaName,
                    plan.wholeSource.isEmpty() ? edited : plan.wholeSource,
                    plan.wholeSourceBaseHash.isEmpty() ? JavaSourceMapper.hash(baseline.getText())
                            : plan.wholeSourceBaseHash);
        } else {
            report = JavaSyncApplier.apply(scId, javaName, plan,
                    JavaSyncApplier.ConflictResolution.KEEP_JAVA, true);
        }
        persistBlockBackup(scId);

        MappedSource finalSource = loadSource(context, scId, projectFile);
        if (report.wholeSourceMode) {
            return new SyncOutcome(report, plan, finalSource);
        }
        refreshRegionSnapshots(scId, javaName, finalSource);

        // The user layer (manual chunks + overrides) must be visible in the final canonical source,
        // whatever happened on the block side. This is the hard guarantee that no user edit is
        // ever dropped by the reconciliation.
        if (!userLayerPresent(finalSource, plan)) {
            report = JavaSyncApplier.applyWholeSource(scId, javaName, edited,
                    JavaSourceMapper.hash(finalSource.getText()));
            finalSource = loadSource(context, scId, projectFile);
        }

        // Verification: the canonical source must contain every user edit. If an edit escaped the
        // first plan (unexpected mapping case), run one more reconciliation pass. If even that
        // cannot merge it, switch the whole file to manual mode — never lose data.
        //
        // The whole-text check is only meaningful when the block side did not move while the Java
        // editor was open. When blocks changed, the canonical source intentionally differs from the
        // (stale) editor text in exactly those block regions, and a blind recovery would revert the
        // block-side changes with old Java text. The first plan already reconciled both sides then
        // (conflicts keep blocks + Java as override), so the whole-text pass is skipped.
        if (!report.wholeSourceMode && !sameText(finalSource.getText(), edited) && !plan.blockSideChanged) {
            SyncPlan recovery = JavaSyncEngine.analyze(finalSource, edited, null);
            if (!recovery.reliable) {
                report = JavaSyncApplier.applyWholeSource(scId, javaName, edited,
                        JavaSourceMapper.hash(finalSource.getText()));
            } else {
                JavaSyncApplier.Report second = JavaSyncApplier.apply(scId, javaName, recovery,
                        JavaSyncApplier.ConflictResolution.KEEP_JAVA, true);
                mergeReport(report, second);
                persistBlockBackup(scId);
                finalSource = loadSource(context, scId, projectFile);
                if (!sameText(finalSource.getText(), edited)) {
                    report = JavaSyncApplier.applyWholeSource(scId, javaName, edited,
                            JavaSourceMapper.hash(finalSource.getText()));
                    finalSource = loadSource(context, scId, projectFile);
                }
            }
        }
        if (!report.wholeSourceMode) {
            refreshRegionSnapshots(scId, javaName, finalSource);
        }
        return new SyncOutcome(report, plan, finalSource);
    }

    public static JavaSyncApplier.Report apply(String scId, ProjectFileBean projectFile, SyncPlan plan,
                                               JavaSyncApplier.ConflictResolution resolution,
                                               boolean allowConversion) {
        return JavaSyncApplier.apply(scId, projectFile.getJavaName(), plan, resolution, allowConversion);
    }

    /**
     * Clears whole-file manual mode. Called by the explicit "Reload from blocks" action.
     */
    public static void clearWholeSourceMode(String scId, String javaName) {
        JavaSyncApplier.clearWholeSource(scId, javaName);
    }

    /**
     * Injects the manually written code + frame overrides of an Activity into freshly generated
     * code.
     * <p>
     * Called by the build/export pipeline, so what gets compiled is what the user saw in the Java
     * editor. Returns {@code code} unchanged when the Activity has no synchronization metadata,
     * which keeps existing projects byte-identical to before.
     */
    public static String injectUserCode(String scId, String javaName, String code) {
        if (scId == null || javaName == null || code == null || code.isEmpty()) {
            return code;
        }
        try {
            if (!JavaSyncStore.hasMetadata(scId, javaName)) {
                return code;
            }
            JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
            if (metadata.getUserCode().isEmpty() && metadata.getLineOverrides().isEmpty()
                    && !metadata.isWholeSourceMode()) {
                return code;
            }
            return JavaSourceMapper.injectUserCode(code, metadata);
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncManager", "Failed to inject user Java code into " + javaName, e);
            return code;
        }
    }

    /**
     * @return {@code true} when the given Activity has manually written code/overrides attached.
     */
    public static boolean hasUserCode(String scId, String javaName) {
        if (!JavaSyncStore.hasMetadata(scId, javaName)) {
            return false;
        }
        JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
        return !metadata.getUserCode().isEmpty()
                || !metadata.getLineOverrides().isEmpty()
                || metadata.isWholeSourceMode();
    }

    /**
     * Stores the generated text of every block region of the final source, so the next
     * synchronization can detect what changed on the block side since the last save.
     */
    private static void refreshRegionSnapshots(String scId, String javaName, MappedSource finalSource) {
        try {
            JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
            metadata.regionSnapshots.clear();
            if (finalSource != null) {
                for (CodeRegion region : finalSource.blockRegions()) {
                    metadata.regionSnapshots.put(region.id(),
                            JavaSyncEngine.normalize(textOf(finalSource.generatedLines,
                                    region.generatedStartLine, region.generatedEndLine)));
                }
            }
            JavaSyncStore.save(scId, metadata);
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncManager", "Failed to refresh region snapshots of " + javaName, e);
        }
    }

    private static String textOf(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end && i < lines.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static void persistBlockBackup(String scId) {
        try {
            ProjectDataStore dataStore = ProjectDataManager.getProjectDataManager(scId);
            synchronized (dataStore) {
                dataStore.saveAllBackup();
            }
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncManager", "Failed to persist block backup after sync", e);
        }
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        // Compare semantically: formatting/blank-line differences introduced by the code formatter
        // are not lost user edits and must not trigger the whole-file fallback.
        return JavaSyncEngine.normalize(a).equals(JavaSyncEngine.normalize(b));
    }

    /**
     * @return {@code true} when every line the user wrote (manual chunks and overrides) is present
     * in the final canonical source. Used as a hard, block-side-independent guarantee that the
     * reconciliation did not drop a user edit. Orphaned (unanchorable) content is still visible in
     * the final source, so it does not fail this check.
     */
    private static boolean userLayerPresent(MappedSource finalSource, SyncPlan plan) {
        if (finalSource == null) {
            return plan.userCode.isEmpty() && plan.lineOverrides.isEmpty();
        }
        String finalText = JavaSyncEngine.normalize(finalSource.getText());
        for (UserCodeChunk chunk : plan.userCode) {
            if (chunk == null) {
                continue;
            }
            for (String line : chunk.lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                if (!finalText.contains(JavaSyncEngine.normalize(line))) {
                    return false;
                }
            }
        }
        for (LineOverride override : plan.lineOverrides) {
            if (override == null) {
                continue;
            }
            for (String line : override.lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                if (!finalText.contains(JavaSyncEngine.normalize(line))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void mergeReport(JavaSyncApplier.Report target, JavaSyncApplier.Report source) {
        target.updatedBlocks += source.updatedBlocks;
        target.convertedBlocks += source.convertedBlocks;
        target.removedBlocks += source.removedBlocks;
        target.userCodeChunks = source.userCodeChunks;
        target.lineOverrides = source.lineOverrides;
        target.preservedGeneratedEdits += source.preservedGeneratedEdits;
        target.skippedConflicts += source.skippedConflicts;
    }
}

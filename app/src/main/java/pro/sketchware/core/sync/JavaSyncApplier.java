package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.beans.BlockBean;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.core.project.ProjectDataStore;
import pro.sketchware.util.LogUtil;

/**
 * Writes a {@link SyncPlan} into the project: updates/removes blocks of the affected events, stores
 * the new user chunks and generated-line overrides in the synchronization metadata. The metadata is
 * saved together with the project data, so the final state is always consistent.
 * <p>
 * The applier is <b>loss free</b>: if a block change cannot be applied (the block disappeared, the
 * chain changed, or the user chose to keep blocks), the same Java edit is stored as a
 * {@link LineOverride} instead. Manual code is never discarded.
 */
public final class JavaSyncApplier {

    /**
     * How conflicting regions (changed in the blocks <b>and</b> in the Java editor) are resolved.
     */
    public enum ConflictResolution {
        /**
         * Keep what the user typed in the Java editor.
         */
        KEEP_JAVA,
        /**
         * Keep the block version for conflicted regions; the Java edit of that region is preserved
         * as a frame override instead of being discarded.
         */
        KEEP_BLOCKS
    }

    public static class Report {
        public int updatedBlocks;
        public int convertedBlocks;
        public int removedBlocks;
        public int userCodeChunks;
        public int lineOverrides;
        public int skippedConflicts;
        /**
         * Number of generated framework lines that the user changed/removed and that were
         * preserved as overrides.
         */
        public int preservedGeneratedEdits;
        /**
         * {@code true} when the whole file switched to user-managed mode (unreliable diff).
         */
        public boolean wholeSourceMode;

        public boolean changedAnything() {
            return updatedBlocks + convertedBlocks + removedBlocks + userCodeChunks
                    + lineOverrides + preservedGeneratedEdits > 0;
        }
    }

    private JavaSyncApplier() {
    }

    /**
     * @param allowConversion when {@code false}, regular blocks whose code was edited stay as they
     *                        are; the edit is kept as a frame override instead.
     */
    public static Report apply(String scId, String javaName, SyncPlan plan,
                               ConflictResolution resolution, boolean allowConversion) {
        Report report = new Report();
        if (plan == null) {
            return report;
        }
        if (!plan.reliable) {
            return applyWholeSource(scId, javaName, plan.wholeSource, plan.wholeSourceBaseHash);
        }
        report.preservedGeneratedEdits = plan.preservedGeneratedEdits;
        report.userCodeChunks = plan.userCode.size();

        ProjectDataStore dataStore = ProjectDataManager.getProjectDataManager(scId);
        // Every block-change fallback is already part of plan.lineOverrides (added by the engine) so
        // no user edit can escape the plan. When the block change is applied, the fallback must be
        // removed again: it replaces the *generated* text of the region, and the block now produces
        // exactly that text, so keeping the override would double-apply and fight the block.
        // Mutating plan.lineOverrides (not a copy) keeps the plan an accurate view of what the
        // applier persisted, which the save-time verification relies on.
        List<LineOverride> overrides = plan.lineOverrides;

        for (SyncPlan.BlockChange change : plan.blockChanges) {
            if (change.conflicted && resolution == ConflictResolution.KEEP_BLOCKS) {
                report.skippedConflicts++;
                continue;
            }
            if (change.type == SyncPlan.ChangeType.CONVERT_TO_SOURCE_BLOCK && !allowConversion) {
                report.skippedConflicts++;
                continue;
            }
            ArrayList<BlockBean> blocks = dataStore.getBlocks(javaName, change.ownerKey);
            if (blocks.isEmpty()) {
                // The event or block no longer exists: the fallback override keeps the Java edit.
                continue;
            }
            // getBlocks() returns the live list; copy it so a failed edit cannot corrupt the project.
            ArrayList<BlockBean> working = deepCopy(blocks);
            boolean applied;
            try {
                applied = switch (change.type) {
                    case UPDATE_SOURCE_BLOCK -> BlockChainEditor.updateSourceBlock(working, change.blockId, change.newText);
                    case CONVERT_TO_SOURCE_BLOCK -> BlockChainEditor.convertToSourceBlock(working, change.blockId, change.newText);
                    case DELETE_BLOCK -> BlockChainEditor.removeStatement(working, change.blockId);
                };
            } catch (RuntimeException e) {
                LogUtil.e("JavaSyncApplier", "Failed to apply " + change.describe(), e);
                applied = false;
            }
            if (!applied) {
                // The fallback override is already in the plan; it keeps the Java edit.
                continue;
            }
            dataStore.putBlocks(javaName, change.ownerKey, working);
            overrides.remove(change.fallbackOverride);
            switch (change.type) {
                case UPDATE_SOURCE_BLOCK -> report.updatedBlocks++;
                case CONVERT_TO_SOURCE_BLOCK -> report.convertedBlocks++;
                case DELETE_BLOCK -> report.removedBlocks++;
            }
        }

        JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
        metadata.userCode = new ArrayList<>(plan.userCode);
        metadata.lineOverrides = overrides;
        metadata.wholeSourceOverride = false;
        metadata.wholeSource = "";
        metadata.wholeSourceBaseHash = "";
        metadata.regionSnapshots.clear();
        metadata.regionSnapshots.putAll(plan.regionSnapshots);
        JavaSyncStore.save(scId, metadata);
        report.lineOverrides = metadata.getLineOverrides().size();
        return report;
    }

    /**
     * Stores the whole edited source as user-managed file content. Used when the edited source was
     * too different from the generated one to be aligned safely.
     */
    public static Report applyWholeSource(String scId, String javaName, String wholeSource,
                                          String baseHash) {
        Report report = new Report();
        report.wholeSourceMode = true;
        if (wholeSource == null || wholeSource.trim().isEmpty()) {
            return report;
        }
        JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
        metadata.wholeSourceOverride = true;
        metadata.wholeSource = wholeSource;
        metadata.wholeSourceBaseHash = baseHash == null ? "" : baseHash;
        metadata.userCode = new ArrayList<>();
        metadata.lineOverrides = new ArrayList<>();
        JavaSyncStore.save(scId, metadata);
        return report;
    }

    /**
     * Clears whole-file manual mode. Called by the explicit "Reload from blocks" action.
     */
    public static void clearWholeSource(String scId, String javaName) {
        JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
        if (metadata.isWholeSourceMode()) {
            metadata.clearWholeSourceMode();
            JavaSyncStore.save(scId, metadata);
        }
    }

    private static ArrayList<BlockBean> deepCopy(List<BlockBean> blocks) {
        ArrayList<BlockBean> copy = new ArrayList<>(blocks.size());
        for (BlockBean block : blocks) {
            copy.add(block.clone());
        }
        return copy;
    }
}

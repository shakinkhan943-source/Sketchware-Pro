package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.beans.BlockBean;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.core.project.ProjectDataStore;
import pro.sketchware.util.LogUtil;

/**
 * Writes a {@link SyncPlan} into the project: updates/removes blocks of the affected events and
 * stores the manually written Java code in the project's synchronization metadata.
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
         * Keep the block version, discard the Java edit of that region.
         */
        KEEP_BLOCKS
    }

    public static class Report {
        public int updatedBlocks;
        public int convertedBlocks;
        public int removedBlocks;
        public int userCodeChunks;
        public int skippedConflicts;
        public int rejectedFrameworkEdits;

        public boolean changedAnything() {
            return updatedBlocks + convertedBlocks + removedBlocks + userCodeChunks > 0;
        }
    }

    private JavaSyncApplier() {
    }

    /**
     * @param allowConversion when {@code false}, regular blocks whose code was edited stay as they
     *                        are (the user did not confirm turning them into source-code blocks)
     */
    public static Report apply(String scId, String javaName, SyncPlan plan,
                               ConflictResolution resolution, boolean allowConversion) {
        Report report = new Report();
        report.rejectedFrameworkEdits = plan.rejectedFrameworkEdits;
        if (!plan.reliable) {
            return report;
        }

        ProjectDataStore dataStore = ProjectDataManager.getProjectDataManager(scId);

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
                continue;
            }
            dataStore.putBlocks(javaName, change.ownerKey, working);
            switch (change.type) {
                case UPDATE_SOURCE_BLOCK -> report.updatedBlocks++;
                case CONVERT_TO_SOURCE_BLOCK -> report.convertedBlocks++;
                case DELETE_BLOCK -> report.removedBlocks++;
            }
        }

        JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
        metadata.userCode = new ArrayList<>(plan.userCode);
        metadata.regionSnapshots.clear();
        metadata.regionSnapshots.putAll(plan.regionSnapshots);
        JavaSyncStore.save(scId, metadata);
        report.userCodeChunks = metadata.getUserCode().size();

        return report;
    }

    private static ArrayList<BlockBean> deepCopy(List<BlockBean> blocks) {
        ArrayList<BlockBean> copy = new ArrayList<>(blocks.size());
        for (BlockBean block : blocks) {
            copy.add(block.clone());
        }
        return copy;
    }
}

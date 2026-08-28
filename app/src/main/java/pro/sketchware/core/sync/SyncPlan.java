package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of comparing the Java source the user edited with the block based representation.
 * <p>
 * A plan is purely descriptive — nothing is written until {@link JavaSyncApplier} applies it, which
 * makes it possible to show the user what will happen and to ask for a decision when an automatic
 * resolution would be unsafe.
 */
public class SyncPlan {

    public enum ChangeType {
        /**
         * A source-code block ({@code addSourceDirectly}) whose code was edited in the Java editor.
         */
        UPDATE_SOURCE_BLOCK,
        /**
         * A regular block whose generated code was edited. Java code cannot be turned back into an
         * arbitrary block, so the block is replaced by a source-code block holding the new code —
         * only after the user confirmed it.
         */
        CONVERT_TO_SOURCE_BLOCK,
        /**
         * The generated code of a block was deleted in the Java editor → remove the block.
         */
        DELETE_BLOCK
    }

    public enum ConflictType {
        /**
         * Both the block and the Java code changed since the editor was opened.
         */
        BOTH_CHANGED,
        /**
         * The block disappeared (deleted in the logic editor) while its code was edited in Java.
         */
        BLOCK_REMOVED,
        /**
         * The block's code appears more than once in the generated file, so an edit cannot be
         * attributed unambiguously.
         */
        AMBIGUOUS_REGION
    }

    public static class BlockChange {
        public final ChangeType type;
        public final String regionId;
        public final String ownerKey;
        public final String blockId;
        public final String opCode;
        public final String oldText;
        public final String newText;
        /**
         * {@code true} when this change is part of a conflict and therefore only applied when the
         * user chose to keep the Java side.
         */
        public boolean conflicted;

        public BlockChange(ChangeType type, CodeRegion region, String oldText, String newText) {
            this.type = type;
            this.regionId = region.id();
            this.ownerKey = region.ownerKey;
            this.blockId = region.blockId;
            this.opCode = region.opCode;
            this.oldText = oldText;
            this.newText = newText;
        }

        public String describe() {
            String where = ownerKey + " → block " + blockId;
            return switch (type) {
                case UPDATE_SOURCE_BLOCK -> "Update source code block (" + where + ")";
                case CONVERT_TO_SOURCE_BLOCK -> "Replace block '" + opCode + "' with a source code block (" + where + ")";
                case DELETE_BLOCK -> "Remove block '" + opCode + "' (" + where + ")";
            };
        }
    }

    public static class Conflict {
        public final ConflictType type;
        public final String regionId;
        public final String ownerKey;
        public final String blockId;
        public final String blockText;
        public final String javaText;

        public Conflict(ConflictType type, String regionId, String ownerKey, String blockId,
                        String blockText, String javaText) {
            this.type = type;
            this.regionId = regionId;
            this.ownerKey = ownerKey;
            this.blockId = blockId;
            this.blockText = blockText;
            this.javaText = javaText;
        }

        public String describe() {
            return switch (type) {
                case BOTH_CHANGED -> ownerKey + " → block " + blockId + " changed in blocks and in Java";
                case BLOCK_REMOVED -> ownerKey + " → block " + blockId + " no longer exists in the blocks";
                case AMBIGUOUS_REGION -> ownerKey + " → block " + blockId + " is generated more than once";
            };
        }
    }

    /**
     * {@code false} when the edited source could not be aligned with the generated one. The caller
     * must not apply the plan in that case.
     */
    public boolean reliable = true;
    public final List<BlockChange> blockChanges = new ArrayList<>();
    /**
     * Complete new list of manually written code chunks (already re-anchored).
     */
    public final List<UserCodeChunk> userCode = new ArrayList<>();
    public final List<Conflict> conflicts = new ArrayList<>();
    /**
     * Number of framework/generated lines the user removed. Those edits are rejected to protect the
     * Sketchware infrastructure, and reported.
     */
    public int rejectedFrameworkEdits;
    public final List<String> warnings = new ArrayList<>();
    /**
     * Region snapshots of the freshly generated source, stored after applying.
     */
    public java.util.Map<String, String> regionSnapshots = new java.util.HashMap<>();

    public boolean hasBlockChanges() {
        return !blockChanges.isEmpty();
    }

    public boolean requiresConfirmation() {
        if (!conflicts.isEmpty()) {
            return true;
        }
        if (rejectedFrameworkEdits > 0) {
            // The user changed code the synchronizer refuses to touch (or pasted something that
            // replaces the whole Activity): never resolve that silently.
            return true;
        }
        for (BlockChange change : blockChanges) {
            if (change.type == ChangeType.CONVERT_TO_SOURCE_BLOCK) {
                return true;
            }
        }
        return false;
    }

    public int newUserCodeChunks(List<UserCodeChunk> previous) {
        int known = previous == null ? 0 : previous.size();
        return Math.max(0, userCode.size() - known);
    }

    public boolean isEmpty() {
        return blockChanges.isEmpty() && conflicts.isEmpty() && rejectedFrameworkEdits == 0;
    }
}

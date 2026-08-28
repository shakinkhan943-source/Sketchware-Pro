package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pro.sketchware.beans.BlockBean;
import pro.sketchware.core.codegen.BlockColorMapper;
import pro.sketchware.core.codegen.BlockLoader;
import pro.sketchware.core.codegen.ExtraBlockInfo;

/**
 * Structural edits on a block chain of one event/More Block.
 * <p>
 * A chain is stored as a flat {@code ArrayList<BlockBean>} where the first element is the head of
 * the chain and blocks reference each other through {@link BlockBean#nextBlock},
 * {@link BlockBean#subStack1}, {@link BlockBean#subStack2} and {@code "@id"} parameters. All the
 * operations here keep those invariants intact — the same representation the logic editor writes.
 */
public final class BlockChainEditor {

    public static final String SOURCE_BLOCK_OPCODE = "addSourceDirectly";
    private static final String SOURCE_BLOCK_FALLBACK_SPEC = "add source directly %s.inputOnly";

    private BlockChainEditor() {
    }

    public static BlockBean findById(List<BlockBean> blocks, String id) {
        for (BlockBean block : blocks) {
            if (block.id != null && block.id.equals(id)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Removes a statement block from its chain, together with everything only it owns
     * (parameters and sub stacks). The rest of the chain stays connected.
     *
     * @return {@code true} when the block existed and was removed
     */
    public static boolean removeStatement(ArrayList<BlockBean> blocks, String blockId) {
        BlockBean target = findById(blocks, blockId);
        if (target == null) {
            return false;
        }
        int next = target.nextBlock;
        relink(blocks, blockId, next);

        Set<String> toRemove = collectOwned(blocks, target);
        boolean wasHead = !blocks.isEmpty() && blocks.get(0) == target;
        blocks.removeIf(block -> toRemove.contains(block.id));

        if (wasHead) {
            // The interpreter uses index 0 as the head of the chain.
            BlockBean newHead = next >= 0 ? findById(blocks, String.valueOf(next)) : null;
            if (newHead != null) {
                blocks.remove(newHead);
                blocks.add(0, newHead);
            }
        }
        return true;
    }

    /**
     * Replaces the code of an existing {@code addSourceDirectly} block.
     */
    public static boolean updateSourceBlock(ArrayList<BlockBean> blocks, String blockId, String code) {
        BlockBean target = findById(blocks, blockId);
        if (target == null || !SOURCE_BLOCK_OPCODE.equals(target.opCode)) {
            return false;
        }
        if (target.parameters == null) {
            target.parameters = new ArrayList<>();
        }
        if (target.parameters.isEmpty()) {
            target.parameters.add(code);
        } else {
            target.parameters.set(0, code);
        }
        return true;
    }

    /**
     * Turns a regular block into a source-code block holding {@code code}. The block keeps its id
     * and its position inside the chain, so nothing else in the project has to change. Parameters
     * and sub stacks that only belonged to the replaced block are removed.
     */
    public static boolean convertToSourceBlock(ArrayList<BlockBean> blocks, String blockId, String code) {
        BlockBean target = findById(blocks, blockId);
        if (target == null) {
            return false;
        }
        Set<String> owned = collectOwned(blocks, target);
        owned.remove(target.id);
        blocks.removeIf(block -> owned.contains(block.id));

        BlockBean replacement = new BlockBean(target.id, sourceBlockSpec(), " ", "", SOURCE_BLOCK_OPCODE);
        replacement.color = sourceBlockColor();
        replacement.parameters.add(code);
        replacement.nextBlock = target.nextBlock;
        replacement.subStack1 = -1;
        replacement.subStack2 = -1;
        replacement.disabled = target.disabled;
        target.copy(replacement);
        return true;
    }

    /**
     * Appends a new source-code block at the end of a chain.
     *
     * @return the created block
     */
    public static BlockBean appendSourceBlock(ArrayList<BlockBean> blocks, String code) {
        BlockBean block = new BlockBean(nextFreeId(blocks), sourceBlockSpec(), " ", "", SOURCE_BLOCK_OPCODE);
        block.color = sourceBlockColor();
        block.parameters.add(code);
        BlockBean last = lastStatement(blocks);
        blocks.add(block);
        if (last == null) {
            if (blocks.size() > 1) {
                blocks.remove(block);
                blocks.add(0, block);
            }
        } else {
            last.nextBlock = Integer.parseInt(block.id);
        }
        return block;
    }

    private static BlockBean lastStatement(List<BlockBean> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }
        Map<String, BlockBean> byId = new HashMap<>();
        for (BlockBean block : blocks) {
            byId.put(block.id, block);
        }
        BlockBean current = blocks.get(0);
        Set<String> visited = new HashSet<>();
        while (current.nextBlock >= 0 && visited.add(current.id)) {
            BlockBean next = byId.get(String.valueOf(current.nextBlock));
            if (next == null) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String nextFreeId(List<BlockBean> blocks) {
        int max = -1;
        for (BlockBean block : blocks) {
            try {
                max = Math.max(max, Integer.parseInt(block.id));
            } catch (NumberFormatException ignored) {
                // ids created by other tools; ignore for the maximum
            }
        }
        return String.valueOf(max + 1);
    }

    /**
     * Repoints everything that referenced {@code blockId} as its successor to {@code newNext}.
     */
    private static void relink(List<BlockBean> blocks, String blockId, int newNext) {
        int id;
        try {
            id = Integer.parseInt(blockId);
        } catch (NumberFormatException e) {
            return;
        }
        for (BlockBean block : blocks) {
            if (block.nextBlock == id) {
                block.nextBlock = newNext;
            }
            if (block.subStack1 == id) {
                block.subStack1 = newNext;
            }
            if (block.subStack2 == id) {
                block.subStack2 = newNext;
            }
        }
    }

    /**
     * Collects the ids of {@code root} and of every block that only exists because of it:
     * its parameter blocks and the blocks of its sub stacks. The {@code nextBlock} chain is
     * <b>not</b> followed, because those blocks are siblings, not children.
     */
    private static Set<String> collectOwned(List<BlockBean> blocks, BlockBean root) {
        Map<String, BlockBean> byId = new HashMap<>();
        for (BlockBean block : blocks) {
            byId.put(block.id, block);
        }
        Set<String> owned = new HashSet<>();
        collect(byId, root, owned, false);
        return owned;
    }

    private static void collect(Map<String, BlockBean> byId, BlockBean block, Set<String> owned, boolean followNext) {
        if (block == null || !owned.add(block.id)) {
            return;
        }
        if (block.parameters != null) {
            for (String parameter : block.parameters) {
                if (parameter != null && parameter.startsWith("@")) {
                    collect(byId, byId.get(parameter.substring(1)), owned, true);
                }
            }
        }
        collect(byId, byId.get(String.valueOf(block.subStack1)), owned, true);
        collect(byId, byId.get(String.valueOf(block.subStack2)), owned, true);
        if (followNext) {
            collect(byId, byId.get(String.valueOf(block.nextBlock)), owned, true);
        }
    }

    private static String sourceBlockSpec() {
        try {
            ExtraBlockInfo info = BlockLoader.getBlockInfo(SOURCE_BLOCK_OPCODE);
            if (info != null && !info.isMissing && info.getSpec() != null && !info.getSpec().isEmpty()) {
                return info.getSpec();
            }
        } catch (RuntimeException ignored) {
            // fall through to the built-in spec
        }
        return SOURCE_BLOCK_FALLBACK_SPEC;
    }

    private static int sourceBlockColor() {
        try {
            return BlockColorMapper.getBlockColor(SOURCE_BLOCK_OPCODE, " ");
        } catch (RuntimeException e) {
            return 0xff5cb722;
        }
    }
}

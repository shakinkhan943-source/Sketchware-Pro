package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The heart of the "Java → blocks" direction of the synchronization layer.
 * <p>
 * It compares the source the user edited in the Java tab with the {@link MappedSource} that was
 * shown to them and decides, per mapped region, what happened:
 * <ul>
 *     <li>region unchanged → nothing to do</li>
 *     <li>region emptied → the owning block is removed from its event</li>
 *     <li>region edited → the owning source-code block is updated; a regular block can be replaced
 *     by a source-code block after the user confirmed</li>
 *     <li>text that belongs to no region → manually written code, kept as user-managed code</li>
 *     <li>removed framework/generated lines → rejected, the Sketchware infrastructure is never
 *     destroyed by an edit</li>
 * </ul>
 * Nothing is written here; the outcome is a {@link SyncPlan}.
 */
public final class JavaSyncEngine {

    private JavaSyncEngine() {
    }

    /**
     * @param baseline the source that was loaded into the editor (with its mapping)
     * @param edited   the text the user saved
     * @param current  a freshly generated mapping, used to detect changes made on the block side
     *                 while the editor was open (conflict detection)
     */
    public static SyncPlan analyze(MappedSource baseline, String edited, MappedSource current) {
        SyncPlan plan = new SyncPlan();

        List<String> editedLines = new ArrayList<>(Arrays.asList(
                edited.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        List<String> baseLines = baseline.lines;

        LineDiff.Result diff = LineDiff.diff(baseLines, editedLines);
        plan.reliable = diff.reliable;
        if (!diff.reliable) {
            plan.warnings.add("The edited source is too different from the generated one to be "
                    + "synchronized safely.");
            return plan;
        }

        int[] alignedNew = new int[baseLines.size()];
        Arrays.fill(alignedNew, -1);
        List<List<Integer>> insertsAt = new ArrayList<>(baseLines.size() + 1);
        for (int i = 0; i <= baseLines.size(); i++) {
            insertsAt.add(new ArrayList<>());
        }
        int basePosition = 0;
        for (LineDiff.Op op : diff.ops) {
            switch (op.type) {
                case EQUAL -> {
                    alignedNew[op.oldIndex] = op.newIndex;
                    basePosition = op.oldIndex + 1;
                }
                case DELETE -> basePosition = op.oldIndex + 1;
                case INSERT -> insertsAt.get(Math.min(basePosition, baseLines.size())).add(op.newIndex);
            }
        }

        // Which baseline positions are "inside" a region: inserts there belong to that region.
        boolean[] insideRegion = new boolean[baseLines.size() + 1];
        int[] regionOfLine = new int[baseLines.size()];
        Arrays.fill(regionOfLine, -1);
        List<CodeRegion> regions = baseline.regions;
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            for (int line = region.startLine; line < region.endLine && line < regionOfLine.length; line++) {
                // user regions may sit inside a block region and win
                if (regionOfLine[line] == -1 || region.kind == CodeRegion.Kind.USER) {
                    regionOfLine[line] = r;
                }
            }
            for (int position = region.startLine + 1; position < region.endLine; position++) {
                insideRegion[position] = true;
            }
        }

        // A replaced line shows up as "delete + insert at the region border". Let regions whose
        // content was (partly) deleted claim the inserts at their borders, so replacing the code of
        // a block updates that block instead of deleting it and adding unrelated manual code.
        int[] borderClaim = new int[baseLines.size() + 2];
        Arrays.fill(borderClaim, -1);
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            boolean hasDeletion = false;
            for (int line = region.startLine; line < region.endLine && line < alignedNew.length; line++) {
                if (regionOfLine[line] == r && alignedNew[line] == -1) {
                    hasDeletion = true;
                    break;
                }
            }
            if (!hasDeletion) {
                continue;
            }
            for (int position : new int[]{region.startLine, region.endLine}) {
                if (position >= 0 && position < borderClaim.length
                        && borderClaim[position] == -1 && !insideRegion[position]) {
                    borderClaim[position] = r;
                }
            }
        }

        Map<String, UserCodeChunk> previousChunks = new HashMap<>();
        for (UserCodeChunk chunk : baseline.chunks) {
            previousChunks.put(chunk.id, chunk);
        }

        List<PendingChunk> pendingChunks = new ArrayList<>();

        // --- 1. Regions -------------------------------------------------------------------
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            List<String> newLines = collectRegionLines(region, r, regionOfLine, alignedNew, insertsAt,
                    editedLines, borderClaim);
            String oldText = baseline.textOf(region);
            String newText = String.join("\n", newLines);

            if (region.kind == CodeRegion.Kind.USER) {
                UserCodeChunk previous = previousChunks.get(region.chunkId);
                if (isBlank(newText)) {
                    continue; // user deleted their own manual code
                }
                UserCodeChunk chunk = previous != null ? previous.copy() : new UserCodeChunk(region.chunkId);
                chunk.lines = stripIndent(newLines, chunk.indent);
                pendingChunks.add(new PendingChunk(region.startLine, chunk));
                continue;
            }

            if (normalize(oldText).equals(normalize(newText))) {
                continue;
            }
            if (region.duplicate) {
                plan.conflicts.add(new SyncPlan.Conflict(SyncPlan.ConflictType.AMBIGUOUS_REGION,
                        region.id(), region.ownerKey, region.blockId, oldText, newText));
                continue;
            }

            SyncPlan.BlockChange change;
            if (isBlank(newText)) {
                change = new SyncPlan.BlockChange(SyncPlan.ChangeType.DELETE_BLOCK, region, oldText, "");
            } else if (region.isSourceCodeBlock()) {
                change = new SyncPlan.BlockChange(SyncPlan.ChangeType.UPDATE_SOURCE_BLOCK, region,
                        oldText, dedent(newText));
            } else {
                change = new SyncPlan.BlockChange(SyncPlan.ChangeType.CONVERT_TO_SOURCE_BLOCK, region,
                        oldText, dedent(newText));
            }

            // --- conflict detection against the current state of the blocks ----------------
            if (current != null) {
                CodeRegion currentRegion = current.findBlockRegion(region.ownerKey, region.blockId);
                if (currentRegion == null) {
                    plan.conflicts.add(new SyncPlan.Conflict(SyncPlan.ConflictType.BLOCK_REMOVED,
                            region.id(), region.ownerKey, region.blockId, "", newText));
                    continue;
                }
                String currentText = current.textOf(currentRegion);
                if (!normalize(currentText).equals(normalize(oldText))) {
                    change.conflicted = true;
                    plan.conflicts.add(new SyncPlan.Conflict(SyncPlan.ConflictType.BOTH_CHANGED,
                            region.id(), region.ownerKey, region.blockId, currentText, newText));
                }
            }
            plan.blockChanges.add(change);
        }

        // --- 2. Deleted framework lines --------------------------------------------------
        boolean[] frameworkDeleted = new boolean[baseLines.size()];
        for (int line = 0; line < baseLines.size(); line++) {
            if (regionOfLine[line] == -1 && alignedNew[line] == -1 && !baseLines.get(line).trim().isEmpty()) {
                frameworkDeleted[line] = true;
                plan.rejectedFrameworkEdits++;
            }
        }

        // --- 3. Everything the user typed outside of any region --------------------------
        for (int position = 0; position <= baseLines.size(); position++) {
            List<Integer> inserted = insertsAt.get(position);
            if (inserted.isEmpty() || insideRegion[position]
                    || (position < borderClaim.length && borderClaim[position] >= 0)) {
                continue;
            }
            boolean replacesFramework = (position > 0 && frameworkDeleted[position - 1])
                    || (position < frameworkDeleted.length && frameworkDeleted[position]);
            if (replacesFramework) {
                // The user rewrote generated infrastructure: keep the generated version.
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (int index : inserted) {
                lines.add(editedLines.get(index));
            }
            if (isBlank(String.join("\n", lines))) {
                continue;
            }
            UserCodeChunk chunk = new UserCodeChunk(newChunkId());
            chunk.indent = leadingWhitespace(firstNonBlank(lines));
            chunk.lines = stripIndent(lines, chunk.indent);
            pendingChunks.add(new PendingChunk(position, chunk));
        }

        // --- 4. Re-anchor every manual chunk against the generated source ----------------
        pendingChunks.sort((a, b) -> Integer.compare(a.baselinePosition, b.baselinePosition));
        for (PendingChunk pending : pendingChunks) {
            anchor(baseline, pending.baselinePosition, pending.chunk);
            plan.userCode.add(pending.chunk);
        }

        // --- 5. Snapshots used for the next conflict detection ---------------------------
        if (current != null) {
            for (CodeRegion region : current.blockRegions()) {
                plan.regionSnapshots.put(region.id(), normalize(current.textOf(region)));
            }
        }

        if (!chunksEqual(baseline.chunks, plan.userCode)) {
            plan.warnings.add("manual-code-changed");
        }
        return plan;
    }

    /**
     * Collects the edited lines that correspond to a region.
     */
    private static List<String> collectRegionLines(CodeRegion region, int regionIndex, int[] regionOfLine,
                                                   int[] alignedNew, List<List<Integer>> insertsAt,
                                                   List<String> editedLines, int[] borderClaim) {
        List<String> result = new ArrayList<>();
        if (region.startLine < borderClaim.length && borderClaim[region.startLine] == regionIndex) {
            for (int index : insertsAt.get(region.startLine)) {
                result.add(editedLines.get(index));
            }
        }
        for (int line = region.startLine; line < region.endLine && line < alignedNew.length; line++) {
            if (regionOfLine[line] != regionIndex) {
                continue; // belongs to a nested user code chunk
            }
            if (line > region.startLine) {
                for (int index : insertsAt.get(line)) {
                    result.add(editedLines.get(index));
                }
            }
            if (alignedNew[line] >= 0) {
                result.add(editedLines.get(alignedNew[line]));
            }
        }
        if (region.endLine < insertsAt.size() && region.endLine < borderClaim.length
                && borderClaim[region.endLine] == regionIndex) {
            for (int index : insertsAt.get(region.endLine)) {
                result.add(editedLines.get(index));
            }
        }
        return result;
    }

    private static void anchor(MappedSource baseline, int displayPosition, UserCodeChunk chunk) {
        int generated = baseline.generatedLineAtOrBefore(displayPosition - 1);
        // Blank lines make useless anchors (they are neither unique nor recognizable), so walk back
        // to the closest line that actually contains code.
        while (generated >= 0 && baseline.generatedLines.get(generated).trim().isEmpty()) {
            generated--;
        }
        if (generated < 0) {
            chunk.anchorText = "";
            chunk.anchorOccurrence = 0;
            chunk.contextText = "";
            chunk.anchorRegionId = "";
            return;
        }
        chunk.anchorText = baseline.generatedLines.get(generated).trim();
        chunk.anchorOccurrence = UserCodeInjector.occurrenceOf(baseline.generatedLines, generated);
        chunk.contextText = findEnclosingOpener(baseline.generatedLines, generated);
        CodeRegion owner = null;
        for (CodeRegion region : baseline.regions) {
            if (region.kind == CodeRegion.Kind.BLOCK
                    && generated >= region.generatedStartLine && generated < region.generatedEndLine) {
                owner = region;
                break;
            }
        }
        chunk.anchorRegionId = owner == null ? "" : owner.id();
    }

    /**
     * @return the trimmed text of the line that opens the block the given line lives in.
     */
    static String findEnclosingOpener(List<String> lines, int lineIndex) {
        int depth = 0;
        for (int i = lineIndex; i >= 0; i--) {
            String line = UserCodeInjector.stripLiterals(lines.get(i));
            for (int c = line.length() - 1; c >= 0; c--) {
                char ch = line.charAt(c);
                if (ch == '}') {
                    depth++;
                } else if (ch == '{') {
                    if (depth == 0) {
                        return lines.get(i).trim();
                    }
                    depth--;
                }
            }
        }
        return "";
    }

    private static boolean chunksEqual(List<UserCodeChunk> a, List<UserCodeChunk> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).text().equals(b.get(i).text())) {
                return false;
            }
        }
        return true;
    }

    private static String newChunkId() {
        return "u" + Long.toHexString(System.nanoTime()) + Integer.toHexString(COUNTER.incrementAndGet());
    }

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    static boolean isBlank(String text) {
        return normalize(text).isEmpty();
    }

    /**
     * Removes the common indentation of a code snippet, so it can be stored inside a block.
     */
    static String dedent(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            common = Math.min(common, leadingWhitespace(line).length());
        }
        if (common == Integer.MAX_VALUE || common == 0) {
            return trimBlankEdges(lines);
        }
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            result.add(line.length() >= common ? line.substring(common) : line.trim());
        }
        return trimBlankEdges(result.toArray(new String[0]));
    }

    private static String trimBlankEdges(String[] lines) {
        int start = 0;
        int end = lines.length;
        while (start < end && lines[start].trim().isEmpty()) {
            start++;
        }
        while (end > start && lines[end - 1].trim().isEmpty()) {
            end--;
        }
        return String.join("\n", Arrays.asList(lines).subList(start, end));
    }

    static List<String> stripIndent(List<String> lines, String indentHint) {
        String indent = indentHint == null || indentHint.isEmpty()
                ? leadingWhitespace(firstNonBlank(lines)) : indentHint;
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                result.add("");
            } else if (!indent.isEmpty() && line.startsWith(indent)) {
                result.add(line.substring(indent.length()));
            } else {
                result.add(line.stripLeading());
            }
        }
        return result;
    }

    static String firstNonBlank(List<String> lines) {
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return "";
    }

    static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private static class PendingChunk {
        final int baselinePosition;
        final UserCodeChunk chunk;

        PendingChunk(int baselinePosition, UserCodeChunk chunk) {
            this.baselinePosition = baselinePosition;
            this.chunk = chunk;
        }
    }
}

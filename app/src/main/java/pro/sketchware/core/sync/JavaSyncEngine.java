package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The heart of the "Java → blocks" direction of the synchronization layer.
 * <p>
 * It compares the source the user edited in the Java tab with the {@link MappedSource} that was
 * shown to them and decides, per diff region, what happened:
 * <ul>
 *     <li>region unchanged → nothing to do</li>
 *     <li>region emptied → the owning block is removed from its event</li>
 *     <li>region edited → the owning source-code block is updated (regular blocks become source-code
 *     blocks) so the block system matches the user's code</li>
 *     <li>code inside a generated framework/core section (e.g. {@code MainActivityScreen()},
 *     {@code setContent { … }}, imports) → a {@link LineOverride}, so the edit never comes back</li>
 *     <li>text with no block behind it → user-managed code chunk</li>
 *     <li>deleted framework lines → suppression overrides (deleted code never comes back)</li>
 * </ul>
 * Conflicts (blocks changed while the editor was open) keep the Java side as an override and leave
 * the block side intact, so <b>neither side is ever destroyed</b>.
 * <p>
 * Nothing is written here; the outcome is a {@link SyncPlan}. The plan is loss free: an edit the
 * engine cannot classify reliably switches the whole file to manual mode ({@link SyncPlan#wholeSource})
 * instead of dropping it.
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
            // Too different to align safely. Never drop the user's work: keep the whole file as
            // user-managed source.
            plan.wholeSource = edited;
            plan.wholeSourceBaseHash = JavaSourceMapper.hash(baseline.getText());
            plan.warnings.add("whole-source-fallback");
            return plan;
        }

        // Block-side drift at the region-set level: a block added/removed/reordered while the Java
        // editor was open does not show up in the per-region comparison of the loop below, so it
        // must be detected here. Otherwise the save-time recovery pass would reinterpret the new
        // block's generated code (or the old ordering) as user-deleted framework lines and suppress
        // it with overrides, effectively reverting the block-side change.
        if (current != null) {
            List<CodeRegion> baselineBlocks = baseline.blockRegions();
            List<CodeRegion> currentBlocks = current.blockRegions();
            if (baselineBlocks.size() != currentBlocks.size()) {
                plan.blockSideChanged = true;
            } else {
                for (int i = 0; i < baselineBlocks.size() && !plan.blockSideChanged; i++) {
                    CodeRegion before = baselineBlocks.get(i);
                    CodeRegion after = currentBlocks.get(i);
                    if (!before.id().equals(after.id())) {
                        plan.blockSideChanged = true;
                    }
                }
            }
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

        // Inserts at a region border belong to that region only when the border line itself was
        // deleted. This prevents a region from stealing a replacement of an adjacent framework line.
        int[] borderClaim = buildBorderClaims(regions, regionOfLine, alignedNew, insideRegion);
        // Gaps right at a manual (USER) region (before its first / after its last line) are owned by
        // that region, so an enclosing block region must not collect insertions there: they extend
        // the manual code, they are not a change of the block.
        int[] userGapOwner = buildUserGapOwners(regions);

        Map<String, UserCodeChunk> previousChunks = new HashMap<>();
        for (UserCodeChunk chunk : baseline.chunks) {
            previousChunks.put(chunk.id, chunk);
        }

        List<PendingChunk> pendingChunks = new ArrayList<>();
        Set<Integer> consumedInsertPositions = new HashSet<>();
        // Resolve overrides once: the same index is used for the region loop and the framework
        // run loop below.
        Map<String, List<Integer>> overrideIndex =
                UserCodeInjector.buildTrimmedIndex(baseline.generatedLines);
        // Overrides that already exist and were not touched by this edit; they stay as they are.
        Map<String, LineOverride> keptOverrides = new HashMap<>();
        for (LineOverride override : baseline.overrides) {
            if (override != null && override.id != null && !override.id.isEmpty()) {
                keptOverrides.put(override.id, override.copy());
            }
        }

        // --- 1. Regions ---------------------------------------------------------------
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            List<String> newLines = collectRegionLines(region, r, regionOfLine, alignedNew, insertsAt,
                    editedLines, borderClaim, userGapOwner);
            // Old text must use exactly the same ownership rules as the new text: a nested manual
            // chunk inside a block region belongs to its own USER region and is not part of the
            // block's code, so comparing whole display ranges would make the region look changed
            // even when nothing happened.
            String oldText = regionOwnText(baseline, region, r, regionOfLine);
            String newText = String.join("\n", newLines);

            if (region.kind == CodeRegion.Kind.USER) {
                UserCodeChunk previous = previousChunks.get(region.chunkId);
                if (isBlank(newText)) {
                    continue; // user deleted their own manual code
                }
                // Unanchored chunks are displayed commented out ("// code"); never store the
                // comment prefix, otherwise every sync would add another "// " layer and the code
                // would be corrupted as soon as its anchor is found again.
                List<String> chunkLines = baseline.unanchoredChunkIds.contains(region.chunkId)
                        ? stripCommentPrefix(newLines) : newLines;
                UserCodeChunk chunk = previous != null ? previous.copy() : new UserCodeChunk(region.chunkId);
                chunk.lines = stripIndent(chunkLines, chunk.indent);
                pendingChunks.add(new PendingChunk(region.startLine, chunk));
                // The chunk owns its border gaps; make step 3 skip them again.
                if (region.startLine < userGapOwner.length && userGapOwner[region.startLine] == r) {
                    consumedInsertPositions.add(region.startLine);
                }
                if (region.endLine < userGapOwner.length && userGapOwner[region.endLine] == r) {
                    consumedInsertPositions.add(region.endLine);
                }
                continue;
            }

            if (normalize(oldText).equals(normalize(newText))) {
                // The user did not touch this region. If the block side changed underneath, the
                // final canonical source will intentionally differ from the stale editor text;
                // record it so the save-time verification skips its blind recovery pass.
                if (current != null && !region.duplicate) {
                    CodeRegion currentRegion = current.findBlockRegion(region.ownerKey, region.blockId);
                    String baselineGenerated = textOfGenerated(baseline, region);
                    String currentGenerated = currentRegion == null ? ""
                            : textOfGenerated(current, currentRegion);
                    if (!normalize(currentGenerated).equals(normalize(baselineGenerated))) {
                        plan.blockSideChanged = true;
                    }
                }
                continue;
            }

            // Any override that replaced this region's generated lines is superseded by whatever we
            // decide for the region now (block change or new fallback override).
            for (LineOverride regionOverride : findOverridesInRange(baseline,
                    region.generatedStartLine, region.generatedEndLine)) {
                keptOverrides.remove(regionOverride.id);
            }

            BlockChange change;
            if (isBlank(newText)) {
                change = new BlockChange(ChangeType.DELETE_BLOCK, region, oldText, "", newLines);
            } else if (region.isSourceCodeBlock()) {
                change = new BlockChange(ChangeType.UPDATE_SOURCE_BLOCK, region, oldText, dedent(newText), newLines);
            } else {
                change = new BlockChange(ChangeType.CONVERT_TO_SOURCE_BLOCK, region, oldText, dedent(newText), newLines);
            }
            // Safety net: even if applying the block change fails, the Java edit is kept.
            change.fallbackOverride = overrideForRegion(baseline, region, newLines);

            // --- conflict detection against the current state of the blocks ----------------
            boolean conflicted = region.duplicate;
            if (!conflicted && current != null) {
                CodeRegion currentRegion = current.findBlockRegion(region.ownerKey, region.blockId);
                if (currentRegion == null) {
                    plan.blockSideChanged = true;
                    plan.conflicts.add(new Conflict(ConflictType.BLOCK_REMOVED,
                            region.id(), region.ownerKey, region.blockId, "", newText));
                    conflicted = true;
                } else {
                    // Compare the purely generated code of the region on both sides, so injected
                    // manual chunks (which have their own USER region) never trigger a conflict.
                    String baselineGenerated = textOfGenerated(baseline, region);
                    String currentGenerated = textOfGenerated(current, currentRegion);
                    if (!normalize(currentGenerated).equals(normalize(baselineGenerated))) {
                        plan.blockSideChanged = true;
                        plan.conflicts.add(new Conflict(ConflictType.BOTH_CHANGED,
                                region.id(), region.ownerKey, region.blockId, currentGenerated, newText));
                        conflicted = true;
                    }
                }
            } else if (region.duplicate && current != null) {
                plan.conflicts.add(new Conflict(ConflictType.AMBIGUOUS_REGION,
                        region.id(), region.ownerKey, region.blockId, "", newText));
            }
            // Every region edit carries a fallback override in the plan. The applier keeps it when
            // the block change cannot be applied (failure, conflict resolution, conversion
            // disabled) and drops it when the block side now contains the edit.
            plan.lineOverrides.add(change.fallbackOverride);
            if (conflicted) {
                // Keep the Java side as an override; leave the blocks untouched. Nothing is lost.
                change.conflicted = true;
                plan.preservedGeneratedEdits++;
            } else {
                plan.blockChanges.add(change);
            }
        }

        // --- 2. Edits inside generated framework/core sections ------------------------------
        boolean[] frameworkDeleted = new boolean[baseLines.size()];
        for (int line = 0; line < baseLines.size(); line++) {
            frameworkDeleted[line] = regionOfLine[line] == -1 && alignedNew[line] == -1;
        }
        Set<String> rewrittenOverrides = new HashSet<>();
        for (int start = 0; start < baseLines.size(); ) {
            if (!frameworkDeleted[start]) {
                start++;
                continue;
            }
            int end = start;
            while (end + 1 < baseLines.size() && frameworkDeleted[end + 1]) {
                end++;
            }

            // A deleted run can span several owners (two adjacent overrides, or an override plus
            // plain framework lines). Split it by owner so every part is handled exactly once.
            int segmentStart = start;
            while (segmentStart <= end) {
                int generated = generatedIndexForDisplayLine(baseline, segmentStart);
                LineOverride owner = findOverrideOwner(baseline, generated, overrideIndex);
                if (owner == null) {
                    owner = findOrphanOverrideOwner(baseline, segmentStart);
                }
                int segmentEnd = segmentStart;
                while (segmentEnd + 1 <= end && sameOwner(baseline, segmentStart, segmentEnd + 1, owner, overrideIndex)) {
                    segmentEnd++;
                }
                handleFrameworkSegment(baseline, segmentStart, segmentEnd, owner,
                        alignedNew, insertsAt, editedLines, borderClaim, plan,
                        rewrittenOverrides, keptOverrides, consumedInsertPositions);
                segmentStart = segmentEnd + 1;
            }
            plan.preservedGeneratedEdits += end - start + 1;
            start = end + 1;
        }
        // Overrides that stayed untouched are kept (they are the home of earlier edits).
        plan.lineOverrides.addAll(keptOverrides.values());

        // --- 3. Everything the user typed outside of any region --------------------------
        for (int position = 0; position <= baseLines.size(); position++) {
            List<Integer> inserted = insertsAt.get(position);
            if (inserted.isEmpty() || consumedInsertPositions.contains(position)
                    || insideRegion[position]
                    || (position < borderClaim.length && borderClaim[position] >= 0)) {
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

        // --- 4. Keep/assign anchors ------------------------------------------------
        // Anchors are content based, so an existing chunk keeps its previous anchor: it is resolved
        // against the newly generated source and stays at its place (or stays commented out at the
        // end when it cannot be placed any more; it is never silently moved). Only a brand-new
        // chunk has no anchor yet and needs one. Note: an empty anchorText is NOT "no anchor", it
        // is the valid anchor for a chunk placed before the first line.
        pendingChunks.sort((a, b) -> Integer.compare(a.baselinePosition, b.baselinePosition));
        for (PendingChunk pending : pendingChunks) {
            UserCodeChunk previous = previousChunks.get(pending.chunk.id);
            if (previous == null) {
                anchor(baseline, pending.baselinePosition, pending.chunk);
            } else {
                pending.chunk.anchorText = previous.anchorText;
                pending.chunk.anchorOccurrence = previous.anchorOccurrence;
                pending.chunk.contextText = previous.contextText;
                pending.chunk.anchorRegionId = previous.anchorRegionId;
            }
            plan.userCode.add(pending.chunk);
        }

        // --- 5. Snapshots used for the next conflict detection ---------------------------
        if (current != null) {
            for (CodeRegion region : current.blockRegions()) {
                plan.regionSnapshots.put(region.id(), normalize(textOfGenerated(current, region)));
            }
        }

        if (!chunksEqual(baseline.chunks, plan.userCode)) {
            plan.warnings.add("manual-code-changed");
        }
        return plan;
    }

    /**
     * Collects the lines a region owns in the baseline display (nested USER regions excluded), so
     * old/new text comparison and block snapshots use the same ownership rules.
     */
    private static String regionOwnText(MappedSource baseline, CodeRegion region, int regionIndex,
                                        int[] regionOfLine) {
        StringBuilder sb = new StringBuilder();
        for (int line = region.startLine; line < region.endLine && line < regionOfLine.length; line++) {
            if (regionOfLine[line] != regionIndex) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(baseline.lines.get(line));
        }
        return sb.toString();
    }

    /**
     * Claims border insertions for a region only when the border line itself was removed, so a
     * region can never swallow a replacement of an adjacent framework line.
     */
    private static int[] buildBorderClaims(List<CodeRegion> regions, int[] regionOfLine,
                                           int[] alignedNew, boolean[] insideRegion) {
        int[] borderClaim = new int[alignedNew.length + 2];
        Arrays.fill(borderClaim, -1);
        int[] userGapOwner = buildUserGapOwners(regions);
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            if (region.kind != CodeRegion.Kind.BLOCK) {
                continue;
            }
            int start = region.startLine;
            int end = region.endLine;
            if (start < regionOfLine.length && regionOfLine[start] == r
                    && alignedNew[start] == -1 && !insideRegion[start]
                    // A manual chunk owns this gap (it ends right before the block)? Then the gap
                    // belongs to the chunk, not to the block.
                    && userGapOwner[Math.min(start, userGapOwner.length - 1)] == -1) {
                claim(borderClaim, start, r);
            }
            if (end - 1 >= 0 && end - 1 < regionOfLine.length && regionOfLine[end - 1] == r
                    && alignedNew[end - 1] == -1 && end < insideRegion.length && !insideRegion[end]
                    // A manual chunk owns this gap (it starts right after the block)? Then the gap
                    // belongs to the chunk, not to the block.
                    && userGapOwner[Math.min(end, userGapOwner.length - 1)] == -1) {
                claim(borderClaim, end, r);
            }
        }
        return borderClaim;
    }

    private static void claim(int[] borderClaim, int position, int regionIndex) {
        if (position >= 0 && position < borderClaim.length && borderClaim[position] == -1) {
            borderClaim[position] = regionIndex;
        }
    }

    /**
     * @return an array mapping every gap position to the USER region that owns it, or {@code -1}.
     * A manual chunk owns the gap before its first line and the gap after its last line: insertions
     * there extend the chunk. Enclosing block regions must not collect insertions in those gaps.
     */
    private static int[] buildUserGapOwners(List<CodeRegion> regions) {
        int size = 0;
        for (CodeRegion region : regions) {
            size = Math.max(size, region.endLine + 1);
        }
        int[] userGapOwner = new int[Math.max(size, 1)];
        java.util.Arrays.fill(userGapOwner, -1);
        for (int r = 0; r < regions.size(); r++) {
            CodeRegion region = regions.get(r);
            if (region.kind != CodeRegion.Kind.USER) {
                continue;
            }
            if (region.startLine >= 0 && region.startLine < userGapOwner.length) {
                userGapOwner[region.startLine] = r;
            }
            if (region.endLine >= 0 && region.endLine < userGapOwner.length) {
                userGapOwner[region.endLine] = r;
            }
        }
        return userGapOwner;
    }

    /**
     * Collects the edited lines that correspond to a region.
     */
    private static List<String> collectRegionLines(CodeRegion region, int regionIndex, int[] regionOfLine,
                                                   int[] alignedNew, List<List<Integer>> insertsAt,
                                                   List<String> editedLines, int[] borderClaim,
                                                   int[] userGapOwner) {
        List<String> result = new ArrayList<>();
        // A replacement can surface its inserts at different diff positions; attribute each new
        // line to the region exactly once.
        Set<Integer> added = new HashSet<>();
        // USER regions own the gaps before their first and after their last line (both positions
        // may lie inside an enclosing block region, where step 3 would otherwise skip them and the
        // lines would be lost). BLOCK regions only take border insertions when their border line
        // was deleted (borderClaim); otherwise the insertion is new manual code before the block.
        if (region.kind == CodeRegion.Kind.USER) {
            if (region.startLine < userGapOwner.length && userGapOwner[region.startLine] == regionIndex) {
                for (int index : insertsAt.get(region.startLine)) {
                    if (added.add(index)) {
                        result.add(editedLines.get(index));
                    }
                }
            }
        } else if (region.startLine < borderClaim.length && borderClaim[region.startLine] == regionIndex) {
            for (int index : insertsAt.get(region.startLine)) {
                if (added.add(index)) {
                    result.add(editedLines.get(index));
                }
            }
        }
        for (int line = region.startLine; line < region.endLine && line < alignedNew.length; line++) {
            if (regionOfLine[line] != regionIndex) {
                continue; // belongs to a nested user code chunk
            }
            if (line > region.startLine) {
                // Gaps owned by a nested manual chunk belong to that chunk, not to this region.
                if (line < userGapOwner.length && userGapOwner[line] != -1) {
                    continue;
                }
                for (int index : insertsAt.get(line)) {
                    if (added.add(index)) {
                        result.add(editedLines.get(index));
                    }
                }
            }
            if (alignedNew[line] >= 0) {
                result.add(editedLines.get(alignedNew[line]));
            }
        }
        boolean ownsEndGap = region.kind == CodeRegion.Kind.USER
                ? region.endLine < userGapOwner.length && userGapOwner[region.endLine] == regionIndex
                : region.endLine < borderClaim.length && borderClaim[region.endLine] == regionIndex;
        if (region.endLine < insertsAt.size() && ownsEndGap) {
            for (int index : insertsAt.get(region.endLine)) {
                if (added.add(index)) {
                    result.add(editedLines.get(index));
                }
            }
        }
        return result;
    }

    /**
     * Builds an override for a run of deleted framework lines. The run is replaced by the inserted
     * lines (if any), or suppressed when the user simply deleted them.
     */
    private static LineOverride overrideForFrameworkRun(MappedSource baseline, int displayStart,
                                                        int displayEnd, List<Integer> inserts,
                                                        List<String> editedLines) {
        LineOverride override = new LineOverride(newOverrideId());
        override.consumeLines = displayEnd - displayStart + 1;
        int generated = generatedIndexForDisplayLine(baseline, displayStart);
        if (generated < 0 || generated >= baseline.generatedLines.size()) {
            // Cannot create a usable anchor (weird mapping); keep the text at the end instead.
            if (inserts != null) {
                for (int index : inserts) {
                    override.lines.add(editedLines.get(index));
                }
            }
            return override;
        }
        anchorFromGenerated(baseline, generated, override);
        if (inserts != null) {
            for (int index : inserts) {
                override.lines.add(editedLines.get(index));
            }
        }
        return override;
    }

    private static int generatedIndexForDisplayLine(MappedSource baseline, int displayLine) {
        if (displayLine < 0 || displayLine >= baseline.displayToGenerated.length) {
            return -1;
        }
        return baseline.displayToGenerated[displayLine];
    }

    /**
     * Text of a block region inside the <b>purely generated</b> source: no injected chunks, no
     * overrides. This is what the block side owns and what must be compared to detect block-side
     * changes.
     */
    private static String textOfGenerated(MappedSource source, CodeRegion region) {
        int start = region.generatedStartLine;
        int end = region.generatedEndLine;
        if (start < 0 || end <= start) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end && i < source.generatedLines.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(source.generatedLines.get(i));
        }
        return sb.toString();
    }

    private static boolean sameOwner(MappedSource baseline, int firstLine, int line, LineOverride owner,
                                     Map<String, List<Integer>> overrideIndex) {
        int generated = generatedIndexForDisplayLine(baseline, line);
        LineOverride lineOwner = findOverrideOwner(baseline, generated, overrideIndex);
        if (lineOwner == null && owner == null) {
            // Both unowned: they may still be parts of the same orphaned override.
            LineOverride firstOrphan = findOrphanOverrideOwner(baseline, firstLine);
            LineOverride lineOrphan = findOrphanOverrideOwner(baseline, line);
            if (firstOrphan != null || lineOrphan != null) {
                return firstOrphan != null && lineOrphan != null
                        && firstOrphan.id.equals(lineOrphan.id);
            }
            return true;
        }
        if (lineOwner == null || owner == null) {
            return false;
        }
        return lineOwner == owner || lineOwner.id.equals(owner.id);
    }

    /**
     * Handles one maximal segment of a deleted framework run that belongs to the same owner.
     *
     * @param owner the override owning the segment, or {@code null} for plain framework lines
     */
    private static void handleFrameworkSegment(MappedSource baseline, int start, int end,
                                               LineOverride owner, int[] alignedNew,
                                               List<List<Integer>> insertsAt, List<String> editedLines,
                                               int[] borderClaim, SyncPlan plan,
                                               Set<String> rewrittenOverrides,
                                               Map<String, LineOverride> keptOverrides,
                                               Set<Integer> consumedInsertPositions) {
        List<Integer> inserts = collectInserts(insertsAt, start, end, borderClaim);

        if (owner != null) {
            if (!rewrittenOverrides.add(owner.id)) {
                // Another segment already re-rendered the whole override; the ranges of later
                // segments were covered by that re-render, so only consume their insert gaps.
                markRangeConsumed(baseline, owner, borderClaim, consumedInsertPositions);
                return;
            }
            keptOverrides.remove(owner.id);
            if (baseline.unanchoredOverrideRanges.containsKey(owner.id)) {
                // Orphaned (unanchorable) override: re-render the whole orphan from the diff.
                plan.lineOverrides.add(updatedOrphanOverride(baseline, owner,
                        alignedNew, insertsAt, editedLines, borderClaim));
                markRangeConsumed(baseline, owner, borderClaim, consumedInsertPositions);
            } else {
                plan.lineOverrides.add(updatedOverrideForRun(baseline, owner,
                        alignedNew, insertsAt, editedLines, borderClaim));
                markRangeConsumed(baseline, owner, borderClaim, consumedInsertPositions);
            }
            return;
        }

        plan.lineOverrides.add(overrideForFrameworkRun(baseline, start, end, inserts, editedLines));
        markConsumed(consumedInsertPositions, insertsAt, start, end, borderClaim);
    }

    /**
     * Collects all insertions of a deleted framework segment. The diff may place the replacement
     * lines at several gap positions of the run, so only taking {@code end + 1} would drop lines.
     * Insertions at a gap claimed by a block region belong to that region and are excluded here.
     */
    private static List<Integer> collectInserts(List<List<Integer>> insertsAt, int start, int end,
                                                int[] borderClaim) {
        List<Integer> result = new ArrayList<>();
        for (int position = start; position <= end + 1 && position < insertsAt.size(); position++) {
            if (position < borderClaim.length && borderClaim[position] >= 0) {
                continue;
            }
            result.addAll(insertsAt.get(position));
        }
        return result;
    }

    /**
     * Marks every gap of a framework segment consumed (except gaps claimed by a block region).
     */
    private static void markConsumed(Set<Integer> consumedInsertPositions,
                                     List<List<Integer>> insertsAt, int start, int end,
                                     int[] borderClaim) {
        for (int position = start; position <= end + 1 && position < insertsAt.size(); position++) {
            if (position >= borderClaim.length || borderClaim[position] < 0) {
                consumedInsertPositions.add(position);
            }
        }
    }

    /**
     * Marks every gap inside an override's display range consumed after re-rendering it, so
     * step 3 does not re-emit the same lines as a new user chunk.
     */
    private static void markRangeConsumed(MappedSource baseline, LineOverride override,
                                          int[] borderClaim, Set<Integer> consumedInsertPositions) {
        int[] range = displayRangeOf(baseline, override);
        if (range == null) {
            range = baseline.unanchoredOverrideRanges.get(override.id);
        }
        if (range == null) {
            return;
        }
        for (int p = Math.max(0, range[0]); p <= range[1] + 1; p++) {
            if (p >= borderClaim.length || borderClaim[p] < 0) {
                consumedInsertPositions.add(p);
            }
        }
    }

    /**
     * Re-renders all replacement lines of an existing override by applying the diff to its display
     * range, so edits in the middle of an override keep the untouched lines around them.
     */
    private static LineOverride updatedOverrideForRun(MappedSource baseline, LineOverride existing,
                                                      int[] alignedNew, List<List<Integer>> insertsAt,
                                                      List<String> editedLines, int[] borderClaim) {
        LineOverride updated = existing.copy();
        Map<String, List<Integer>> index =
                UserCodeInjector.buildTrimmedIndex(baseline.generatedLines);
        int at = UserCodeInjector.resolveOverrideIndex(baseline.generatedLines, index, existing);
        if (at < 0) {
            // Anchor went away; the override is kept unchanged (it will be reported as unplaced).
            return updated;
        }
        int consume = Math.max(1, existing.consumeLines);
        int displayStart = -1;
        int displayEnd = -1;
        for (int d = 0; d < baseline.displayToGenerated.length; d++) {
            int g = baseline.displayToGenerated[d];
            if (g >= at && g < at + consume) {
                if (displayStart < 0) {
                    displayStart = d;
                }
                displayEnd = d + 1;
            }
        }
        if (displayStart < 0) {
            return updated;
        }
        // Insertions at the borders may be owned by a neighbouring block region; only the region
        // claims them then and they must not be duplicated into the override.
        boolean claimedStart = displayStart < borderClaim.length && borderClaim[displayStart] >= 0;
        boolean claimedEnd = displayEnd < borderClaim.length && borderClaim[displayEnd] >= 0;
        List<String> lines = new ArrayList<>();
        Set<Integer> added = new HashSet<>();
        if (!claimedStart) {
            for (int newIndex : insertsAt.get(Math.min(displayStart, insertsAt.size() - 1))) {
                if (added.add(newIndex)) {
                    lines.add(editedLines.get(newIndex));
                }
            }
        }
        for (int d = displayStart; d < displayEnd && d < alignedNew.length; d++) {
            if (d > displayStart) {
                for (int newIndex : insertsAt.get(d)) {
                    if (added.add(newIndex)) {
                        lines.add(editedLines.get(newIndex));
                    }
                }
            }
            if (alignedNew[d] >= 0) {
                lines.add(editedLines.get(alignedNew[d]));
            }
        }
        if (!claimedEnd && displayEnd < insertsAt.size()) {
            for (int newIndex : insertsAt.get(displayEnd)) {
                if (added.add(newIndex)) {
                    lines.add(editedLines.get(newIndex));
                }
            }
        }
        updated.lines = lines;
        updated.orphaned = false;
        return updated;
    }

    /**
     * @return the display line range {@code [first, last]} (inclusive) an override currently
     * occupies, or {@code null} when the override is not placed in the display any more.
     */
    private static int[] displayRangeOf(MappedSource baseline, LineOverride override) {
        Map<String, List<Integer>> index =
                UserCodeInjector.buildTrimmedIndex(baseline.generatedLines);
        int at = UserCodeInjector.resolveOverrideIndex(baseline.generatedLines, index, override);
        if (at < 0) {
            return null;
        }
        int consume = Math.max(1, override.consumeLines);
        int first = -1;
        int last = -1;
        for (int d = 0; d < baseline.displayToGenerated.length; d++) {
            int g = baseline.displayToGenerated[d];
            if (g >= at && g < at + consume) {
                if (first < 0) {
                    first = d;
                }
                last = d;
            }
        }
        return first < 0 ? null : new int[]{first, last};
    }

    /**
     * @return the persisted override that produces the given generated line, or {@code null}.
     */
    private static LineOverride findOverrideOwner(MappedSource baseline, int generatedIndex,
                                                  Map<String, List<Integer>> index) {
        if (generatedIndex < 0) {
            return null;
        }
        for (LineOverride override : baseline.overrides) {
            if (override == null || override.anchorText == null || override.anchorText.trim().isEmpty()) {
                continue;
            }
            int at = UserCodeInjector.resolveOverrideIndex(baseline.generatedLines, index, override);
            int consume = Math.max(1, override.consumeLines);
            if (at >= 0 && generatedIndex >= at && generatedIndex < at + consume) {
                return override;
            }
        }
        return null;
    }

    /**
     * @return the orphaned override whose display range contains the given display line, or
     * {@code null}.
     */
    private static LineOverride findOrphanOverrideOwner(MappedSource baseline, int displayLine) {
        for (LineOverride override : baseline.overrides) {
            if (override == null || override.id == null || override.id.isEmpty()) {
                continue;
            }
            int[] range = baseline.unanchoredOverrideRanges.get(override.id);
            if (range != null && displayLine >= range[0] && displayLine <= range[1]) {
                return override;
            }
        }
        return null;
    }

    /**
     * Re-renders an orphaned override from the user's edit. Orphaned lines are shown with a
     * {@code "// "} prefix, so the prefix is stripped before storing; otherwise every sync would
     * add another comment layer. The whole orphan display range is rebuilt from the diff, so a
     * partial edit preserves the untouched lines around it.
     */
    private static LineOverride updatedOrphanOverride(MappedSource baseline, LineOverride orphan,
                                                      int[] alignedNew, List<List<Integer>> insertsAt,
                                                      List<String> editedLines, int[] borderClaim) {
        LineOverride updated = orphan.copy();
        updated.orphaned = true;
        int[] range = baseline.unanchoredOverrideRanges.get(orphan.id);
        if (range == null) {
            return updated;
        }
        List<String> lines = new ArrayList<>();
        Set<Integer> added = new HashSet<>();
        boolean claimedStart = range[0] < borderClaim.length && borderClaim[range[0]] >= 0;
        boolean claimedEnd = range[1] + 1 < borderClaim.length && borderClaim[range[1] + 1] >= 0;
        if (!claimedStart) {
            for (int newIndex : insertsAt.get(Math.min(range[0], insertsAt.size() - 1))) {
                if (added.add(newIndex)) {
                    lines.add(editedLines.get(newIndex));
                }
            }
        }
        for (int d = range[0]; d <= range[1] && d < alignedNew.length; d++) {
            if (d > range[0]) {
                for (int newIndex : insertsAt.get(Math.min(d, insertsAt.size() - 1))) {
                    if (added.add(newIndex)) {
                        lines.add(editedLines.get(newIndex));
                    }
                }
            }
            if (alignedNew[d] >= 0) {
                lines.add(editedLines.get(alignedNew[d]));
            }
        }
        if (!claimedEnd && range[1] + 1 < insertsAt.size()) {
            for (int newIndex : insertsAt.get(range[1] + 1)) {
                if (added.add(newIndex)) {
                    lines.add(editedLines.get(newIndex));
                }
            }
        }
        updated.lines = stripCommentPrefix(lines);
        return updated;
    }

    private static List<String> stripCommentPrefix(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                result.add("");
                continue;
            }
            int marker = line.indexOf("//");
            if (marker < 0) {
                result.add(line);
                continue;
            }
            String uncommented = line.substring(marker + 2);
            if (uncommented.startsWith(" ")) {
                uncommented = uncommented.substring(1);
            }
            result.add(uncommented);
        }
        return result;
    }

    /**
     * @return all persisted overrides that replace lines inside the given generated range.
     */
    private static List<LineOverride> findOverridesInRange(MappedSource baseline, int start, int end) {
        List<LineOverride> result = new ArrayList<>();
        if (end <= start) {
            return result;
        }
        Map<String, List<Integer>> index =
                UserCodeInjector.buildTrimmedIndex(baseline.generatedLines);
        for (LineOverride override : baseline.overrides) {
            if (override == null || override.anchorText == null || override.anchorText.trim().isEmpty()) {
                continue;
            }
            int at = UserCodeInjector.resolveOverrideIndex(baseline.generatedLines, index, override);
            if (at < 0) {
                continue;
            }
            int consume = Math.max(1, override.consumeLines);
            if (at < end && at + consume > start) {
                result.add(override);
            }
        }
        return result;
    }

    /**
     * Builds an override that reproduces an edit made inside a block region, used when the block
     * side cannot be updated (conflict, ambiguity, failed apply). It replaces the whole generated
     * line range of the region with the user's new lines.
     */
    private static LineOverride overrideForRegion(MappedSource baseline, CodeRegion region,
                                                  List<String> newLines) {
        LineOverride override = new LineOverride(newOverrideId());
        int generatedStart = region.generatedStartLine;
        int generatedEnd = region.generatedEndLine;
        int consume = generatedEnd > generatedStart ? generatedEnd - generatedStart : 1;
        override.consumeLines = Math.max(1, consume);
        int anchor = -1;
        if (generatedStart >= 0 && generatedStart < baseline.generatedLines.size()) {
            anchor = generatedStart;
            // Prefer a line with real code over a blank one for a stable, visible anchor.
            for (int g = generatedStart; g < generatedEnd && g < baseline.generatedLines.size(); g++) {
                if (!baseline.generatedLines.get(g).trim().isEmpty()) {
                    anchor = g;
                    break;
                }
            }
        }
        if (anchor < 0) {
            override.lines = new ArrayList<>(newLines);
            return override;
        }
        anchorFromGenerated(baseline, anchor, override);
        override.lines = new ArrayList<>(newLines);
        return override;
    }

    private static void anchorFromGenerated(MappedSource baseline, int generatedIndex,
                                            LineOverride override) {
        List<String> generated = baseline.generatedLines;
        override.anchorText = generated.get(generatedIndex).trim();
        override.anchorOccurrence = UserCodeInjector.occurrenceOf(generated, generatedIndex);
        override.beforeText = generatedIndex > 0 ? generated.get(generatedIndex - 1).trim() : "";
        override.afterText = generatedIndex + 1 < generated.size()
                ? generated.get(generatedIndex + 1).trim() : "";
        override.contextText = findEnclosingOpener(generated, generatedIndex);
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

    private static String newOverrideId() {
        return "o" + Long.toHexString(System.nanoTime()) + Integer.toHexString(COUNTER.incrementAndGet());
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

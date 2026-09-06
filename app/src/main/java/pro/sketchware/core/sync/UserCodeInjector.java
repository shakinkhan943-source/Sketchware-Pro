package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges user code back into a freshly generated Activity source.
 * <p>
 * Two layers of user content exist and are applied in a stable, deterministic order:
 * <ol>
 *     <li><b>Line overrides</b> ({@link LineOverride}) replace/suppress generated framework/core
 *     lines. They are the permanent home of edits the user made to generated infrastructure, so
 *     those edits can never be overwritten by a later generation.</li>
 *     <li><b>User chunks</b> ({@link UserCodeChunk}) are inserted before a generated anchor line
 *     (code with no block behind it).</li>
 * </ol>
 * The very same routine is used by the Java editor and by the build pipeline, so what the user sees
 * in the editor is exactly what gets compiled.
 * <p>
 * All anchors are content based (trimmed text + occurrence + neighbouring lines + enclosing block),
 * never line numbers, which keeps manual code stable when blocks above it are added, removed,
 * reordered or reformatted. Content that cannot be anchored any more is kept (commented out) at the
 * end of the file instead of being deleted.
 */
public final class UserCodeInjector {

    /**
     * Marker used for content whose anchor vanished completely. The code is kept (commented out)
     * instead of being lost.
     */
    public static final String ORPHAN_HEADER =
            "// Sketchware Pro: the following manually written code could not be placed automatically";

    private UserCodeInjector() {
    }

    public static class Result {
        public final List<String> lines = new ArrayList<>();
        /**
         * chunk id → line range inside {@link #lines} (inclusive, exclusive).
         */
        public final Map<String, int[]> chunkRanges = new HashMap<>();
        /**
         * override id → line range inside {@link #lines} (inclusive, exclusive). Orphaned overrides
         * are not part of this map.
         */
        public final Map<String, int[]> overrideRanges = new HashMap<>();
        /**
         * Insertion positions of <b>user chunks</b> in generated coordinates: generated line index →
         * inserted line count. Override replacements/deletions are <b>not</b> included.
         */
        public final Map<Integer, Integer> insertionsAt = new HashMap<>();
        public final List<String> unanchored = new ArrayList<>();
        public final List<String> unanchoredOverrides = new ArrayList<>();
        /**
         * For every generated line: first display line of its replacement, or {@code -1} when the
         * line is fully suppressed by an override.
         */
        public final int[] generatedStart;
        /**
         * For every generated line: exclusive end of its display range.
         */
        public final int[] generatedEnd;
        /**
         * For every displayed line: the generated line it came from, {@code -1} for inserted user
         * chunk/orphan lines.
         */
        public final int[] displayToGenerated;

        Result(int generatedLineCount) {
            generatedStart = new int[generatedLineCount];
            generatedEnd = new int[generatedLineCount];
            java.util.Arrays.fill(generatedStart, -1);
            java.util.Arrays.fill(generatedEnd, -1);
            displayToGenerated = new int[0];
        }
    }

    /**
     * @param generatedLines the purely generated source
     * @param chunks         user chunks to inject (may be {@code null}/empty)
     * @param overrides      generated-line overrides to inject (may be {@code null}/empty)
     */
    public static Result inject(List<String> generatedLines, List<UserCodeChunk> chunks,
                                List<LineOverride> overrides) {
        Result result = new Result(generatedLines.size());
        if (generatedLines.isEmpty()) {
            appendTrivial(result, chunks, overrides);
            return result;
        }

        Map<String, List<Integer>> index = buildTrimmedIndex(generatedLines);

        // --- 1. Resolve line overrides ------------------------------------------------
        List<PlacedOverride> placedOverrides = new ArrayList<>();
        List<LineOverride> orphanOverrides = new ArrayList<>();
        Set<Integer> consumedGenerated = new HashSet<>();
        if (overrides != null) {
            for (LineOverride override : overrides) {
                if (override == null) {
                    continue;
                }
                if (override.lines == null) {
                    override.lines = new ArrayList<>();
                }
                int at = resolveOverrideIndex(generatedLines, index, override);
                int consume = Math.max(1, override.consumeLines);
                if (at < 0 || consumedGenerated.contains(at)) {
                    orphanOverrides.add(override);
                    result.unanchoredOverrides.add(override.id);
                    continue;
                }
                placedOverrides.add(new PlacedOverride(at, consume, override));
                for (int k = 0; k < consume && at + k < generatedLines.size(); k++) {
                    consumedGenerated.add(at + k);
                }
            }
        }
        placedOverrides.sort(Comparator.comparingInt(p -> p.index));

        // --- 2. Resolve user chunks --------------------------------------------------
        List<Placement> placements = new ArrayList<>();
        List<UserCodeChunk> orphanChunks = new ArrayList<>();
        int order = 0;
        if (chunks != null) {
            for (UserCodeChunk chunk : chunks) {
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                int insertAt = resolveInsertPosition(generatedLines, index, chunk);
                if (insertAt < 0) {
                    orphanChunks.add(chunk);
                    result.unanchored.add(chunk.id);
                } else {
                    placements.add(new Placement(insertAt, order++, chunk));
                }
            }
        }
        placements.sort(Comparator.<Placement>comparingInt(p -> p.insertAt).thenComparingInt(p -> p.order));

        // --- 3. Emit merged source ---------------------------------------------------
        List<Integer> displayToGenerated = new ArrayList<>();
        int overrideCursor = 0;
        int chunkCursor = 0;
        // Insert chunks that belong before line 0, then the remaining lines/overrides.
        int n = generatedLines.size();
        int consumedUntil = -1;
        for (int position = 0; position <= n; position++) {
            while (chunkCursor < placements.size() && placements.get(chunkCursor).insertAt == position) {
                Placement placement = placements.get(chunkCursor++);
                int start = result.lines.size();
                for (String line : placement.chunk.lines) {
                    String emitted = line.isEmpty() ? "" : placement.chunk.indent + line;
                    result.lines.add(emitted);
                    displayToGenerated.add(-1);
                }
                result.chunkRanges.put(placement.chunk.id, new int[]{start, result.lines.size()});
                result.insertionsAt.merge(position, result.lines.size() - start, Integer::sum);
            }
            if (position == n) {
                break;
            }
            if (position < consumedUntil) {
                // Line consumed by the previous override: do not re-emit it.
                continue;
            }
            if (overrideCursor < placedOverrides.size()
                    && placedOverrides.get(overrideCursor).index == position) {
                PlacedOverride placed = placedOverrides.get(overrideCursor++);
                int start = result.lines.size();
                for (String line : placed.override.lines) {
                    result.lines.add(line == null ? "" : line);
                    displayToGenerated.add(placed.index);
                }
                result.overrideRanges.put(placed.override.id, new int[]{start, result.lines.size()});
                result.generatedStart[placed.index] = start;
                result.generatedEnd[placed.index] = result.lines.size();
                for (int k = 1; k < placed.consume && placed.index + k < n; k++) {
                    result.generatedStart[placed.index + k] = -1;
                    result.generatedEnd[placed.index + k] = -1;
                }
                consumedUntil = placed.index + placed.consume;
            } else {
                int start = result.lines.size();
                result.lines.add(generatedLines.get(position));
                displayToGenerated.add(position);
                result.generatedStart[position] = start;
                result.generatedEnd[position] = start + 1;
            }
        }

        // --- 4. Unanchorable content is kept, never deleted --------------------------
        appendOrphans(result, orphanChunks, orphanOverrides, displayToGenerated);

        result.displayToGenerated = new int[displayToGenerated.size()];
        for (int i = 0; i < displayToGenerated.size(); i++) {
            result.displayToGenerated[i] = displayToGenerated.get(i);
        }
        return result;
    }

    /**
     * Compatibility overload for callers that only inject user chunks.
     */
    public static Result inject(List<String> generatedLines, List<UserCodeChunk> chunks) {
        return inject(generatedLines, chunks, null);
    }

    private static void appendTrivial(Result result, List<UserCodeChunk> chunks,
                                      List<LineOverride> overrides) {
        List<Integer> displayToGenerated = new ArrayList<>();
        if (chunks != null && !chunks.isEmpty()) {
            for (UserCodeChunk chunk : chunks) {
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                result.unanchored.add(chunk.id);
            }
        }
        if (overrides != null) {
            for (LineOverride override : overrides) {
                if (override != null && override.id != null && !override.id.isEmpty()) {
                    result.unanchoredOverrides.add(override.id);
                }
            }
        }
        appendOrphans(result, null, null, displayToGenerated);
        result.displayToGenerated = new int[displayToGenerated.size()];
        for (int i = 0; i < displayToGenerated.size(); i++) {
            result.displayToGenerated[i] = displayToGenerated.get(i);
        }
    }

    private static void appendOrphans(Result result, List<UserCodeChunk> orphanChunks,
                                      List<LineOverride> orphanOverrides,
                                      List<Integer> displayToGenerated) {
        boolean hasChunks = orphanChunks != null && !orphanChunks.isEmpty();
        boolean hasOverrides = orphanOverrides != null && !orphanOverrides.isEmpty();
        if (!hasChunks && !hasOverrides) {
            return;
        }
        result.lines.add("");
        result.lines.add(ORPHAN_HEADER);
        displayToGenerated.add(-1);
        displayToGenerated.add(-1);
        if (hasChunks) {
            for (UserCodeChunk chunk : orphanChunks) {
                int start = result.lines.size();
                for (String line : chunk.lines) {
                    result.lines.add("// " + line);
                    displayToGenerated.add(-1);
                }
                result.chunkRanges.put(chunk.id, new int[]{start, result.lines.size()});
            }
            result.lines.add("");
            displayToGenerated.add(-1);
        }
        if (hasOverrides) {
            for (LineOverride override : orphanOverrides) {
                override.orphaned = true;
                int start = result.lines.size();
                for (String line : override.lines) {
                    result.lines.add("// " + line);
                    displayToGenerated.add(-1);
                }
                result.overrideRanges.put(override.id, new int[]{start, result.lines.size()});
            }
        }
    }

    /**
     * @return the index in {@code generatedLines} <b>before</b> which the chunk has to be inserted,
     * or {@code -1} when no anchor could be resolved.
     */
    private static int resolveInsertPosition(List<String> generatedLines, Map<String, List<Integer>> index,
                                             UserCodeChunk chunk) {
        String anchor = chunk.anchorText == null ? "" : chunk.anchorText.trim();
        if (anchor.isEmpty()) {
            // Anchored at the very beginning of the file.
            return 0;
        }
        List<Integer> candidates = index.get(anchor);
        if (candidates != null && !candidates.isEmpty()) {
            int occurrence = Math.max(0, chunk.anchorOccurrence);
            int line = occurrence < candidates.size() ? candidates.get(occurrence) : candidates.get(0);
            return line + 1;
        }
        // Fallback: append at the end of the enclosing block (method, listener, …).
        String context = chunk.contextText == null ? "" : chunk.contextText.trim();
        if (!context.isEmpty()) {
            List<Integer> contextCandidates = index.get(context);
            if (contextCandidates != null && !contextCandidates.isEmpty()) {
                int end = findBlockEnd(generatedLines, contextCandidates.get(0));
                if (end >= 0) {
                    return end;
                }
            }
        }
        return -1;
    }

    /**
     * Resolves the generated line an override starts at. Prefers candidates that still have the
     * same neighbouring generated lines, then the recorded occurrence, then the enclosing block.
     * Package visible so the synchronization engine can identify which persisted override owns a
     * generated line.
     */
    static int resolveOverrideIndex(List<String> generatedLines, Map<String, List<Integer>> index,
                                    LineOverride override) {
        String anchor = override.anchorText == null ? "" : override.anchorText.trim();
        if (anchor.isEmpty()) {
            return -1;
        }
        List<Integer> candidates = index.get(anchor);
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        List<Integer> positioned = new ArrayList<>();
        for (int candidate : candidates) {
            if (matchesNeighbours(generatedLines, candidate,
                    override.beforeText, override.afterText)) {
                positioned.add(candidate);
            }
        }
        List<Integer> pool = positioned.isEmpty() ? candidates : positioned;
        int occurrence = Math.max(0, override.anchorOccurrence);
        if (occurrence < pool.size()) {
            return pool.get(occurrence);
        }
        String context = override.contextText == null ? "" : override.contextText.trim();
        if (!context.isEmpty()) {
            for (int candidate : pool) {
                if (findEnclosingOpener(generatedLines, candidate).trim().equals(context)) {
                    return candidate;
                }
            }
        }
        return pool.isEmpty() ? -1 : pool.get(0);
    }

    private static boolean matchesNeighbours(List<String> lines, int index, String before, String after) {
        String b = before == null ? "" : before.trim();
        String a = after == null ? "" : after.trim();
        if (!b.isEmpty()) {
            if (index == 0 || !lines.get(index - 1).trim().equals(b)) {
                return false;
            }
        }
        if (!a.isEmpty()) {
            if (index + 1 >= lines.size() || !lines.get(index + 1).trim().equals(a)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the line of the closing brace matching the first {@code {} at or after {@code openLine}.
     *
     * @return the index of the closing brace line, or {@code -1}
     */
    private static int findBlockEnd(List<String> lines, int openLine) {
        int depth = 0;
        boolean started = false;
        for (int i = openLine; i < lines.size(); i++) {
            String line = stripLiterals(lines.get(i));
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    started = true;
                } else if (ch == '}') {
                    depth--;
                    if (started && depth <= 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * @return the trimmed text of the line that opens the block the given line lives in.
     */
    static String findEnclosingOpener(List<String> lines, int lineIndex) {
        int depth = 0;
        for (int i = lineIndex; i >= 0; i--) {
            String line = stripLiterals(lines.get(i));
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

    /**
     * Removes string/char literals and comments so brace counting isn't confused by them.
     */
    static String stripLiterals(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }
            if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    static Map<String, List<Integer>> buildTrimmedIndex(List<String> lines) {
        Map<String, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String key = lines.get(i).trim();
            if (key.isEmpty()) {
                continue;
            }
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        return index;
    }

    /**
     * @return which occurrence of its own trimmed text the given line is (0 based).
     */
    static int occurrenceOf(List<String> lines, int lineIndex) {
        String key = lines.get(lineIndex).trim();
        int occurrence = 0;
        for (int i = 0; i < lineIndex; i++) {
            if (lines.get(i).trim().equals(key)) {
                occurrence++;
            }
        }
        return occurrence;
    }

    private static class Placement {
        final int insertAt;
        final int order;
        final UserCodeChunk chunk;

        Placement(int insertAt, int order, UserCodeChunk chunk) {
            this.insertAt = insertAt;
            this.order = order;
            this.chunk = chunk;
        }
    }

    private static class PlacedOverride {
        final int index;
        final int consume;
        final LineOverride override;

        PlacedOverride(int index, int consume, LineOverride override) {
            this.index = index;
            this.consume = consume;
            this.override = override;
        }
    }
}

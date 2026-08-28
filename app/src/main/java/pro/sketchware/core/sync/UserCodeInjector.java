package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Injects manually written Java code ({@link UserCodeChunk}) back into a freshly generated Activity
 * source.
 * <p>
 * The very same routine is used by the Java editor and by the build pipeline, so what the user sees
 * in the editor is exactly what gets compiled.
 * <p>
 * Chunks are placed using content anchors instead of line numbers, which keeps manual code stable
 * when blocks above it are added, removed, reordered or reformatted.
 */
public final class UserCodeInjector {

    /**
     * Marker used for chunks whose anchor vanished completely. The code is kept (commented out)
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
         * Insertion positions in the source coordinates: generated line index → inserted line count.
         */
        public final Map<Integer, Integer> insertionsAt = new HashMap<>();
        public final List<String> unanchored = new ArrayList<>();
    }

    /**
     * @param generatedLines the purely generated source
     * @param chunks         user code to inject (may be {@code null}/empty)
     */
    public static Result inject(List<String> generatedLines, List<UserCodeChunk> chunks) {
        Result result = new Result();
        if (chunks == null || chunks.isEmpty()) {
            result.lines.addAll(generatedLines);
            return result;
        }

        // Resolve every chunk to an insertion index inside the generated source.
        List<Placement> placements = new ArrayList<>();
        List<UserCodeChunk> orphans = new ArrayList<>();
        Map<String, List<Integer>> index = buildTrimmedIndex(generatedLines);
        int order = 0;
        for (UserCodeChunk chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            int insertAt = resolveInsertPosition(generatedLines, index, chunk);
            if (insertAt < 0) {
                orphans.add(chunk);
                result.unanchored.add(chunk.id);
            } else {
                placements.add(new Placement(insertAt, order++, chunk));
            }
        }
        placements.sort(Comparator.<Placement>comparingInt(p -> p.insertAt).thenComparingInt(p -> p.order));

        int generatedIndex = 0;
        for (Placement placement : placements) {
            while (generatedIndex < placement.insertAt && generatedIndex < generatedLines.size()) {
                result.lines.add(generatedLines.get(generatedIndex++));
            }
            int start = result.lines.size();
            for (String line : placement.chunk.lines) {
                result.lines.add(line.isEmpty() ? "" : placement.chunk.indent + line);
            }
            result.chunkRanges.put(placement.chunk.id, new int[]{start, result.lines.size()});
            int inserted = result.lines.size() - start;
            result.insertionsAt.merge(placement.insertAt, inserted, Integer::sum);
        }
        while (generatedIndex < generatedLines.size()) {
            result.lines.add(generatedLines.get(generatedIndex++));
        }

        if (!orphans.isEmpty()) {
            result.lines.add("");
            result.lines.add(ORPHAN_HEADER);
            for (UserCodeChunk orphan : orphans) {
                int start = result.lines.size();
                for (String line : orphan.lines) {
                    result.lines.add("// " + line);
                }
                result.chunkRanges.put(orphan.id, new int[]{start, result.lines.size()});
            }
        }
        return result;
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
}

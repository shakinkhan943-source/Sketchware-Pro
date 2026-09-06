package pro.sketchware.core.sync;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.project.ProjectDataManager;

/**
 * Builds the {@link MappedSource} of an Activity: the complete generated source plus the
 * information which lines belong to which block/event, which lines are manually written code and
 * which generated framework lines the user replaced or deleted.
 * <p>
 * The generation itself is done by the <b>existing, unmodified</b> code generation pipeline
 * ({@link ProjectFilePaths#getFileSrc}). The only difference is that a
 * {@link CodeOwnershipRecorder} is active while generating, which makes
 * {@link pro.sketchware.core.codegen.BlockInterpreter} surround the code of every top level block
 * with marker comments. Those markers are removed again here and turned into line ranges, so the
 * source handed to the editor is identical to the one the compiler sees.
 * <p>
 * After generation, the persistent user layers are merged in: user chunks (new code) first and then
 * frame overrides (edits to generated/core sections). The merged source is exactly what the editor
 * shows and what gets compiled.
 */
public final class JavaSourceMapper {

    private JavaSourceMapper() {
    }

    /**
     * Generates and maps the source of {@code projectFile}. Must be called off the main thread.
     */
    public static MappedSource map(Context context, String scId, ProjectFileBean projectFile,
                                   JavaSyncMetadata metadata) {
        // Whole-file manual mode: the user's saved source is the only source of truth.
        if (metadata != null && metadata.isWholeSourceMode()) {
            return buildFromWholeSource(metadata);
        }
        String sourceName = projectFile.getSourceFileName();
        CodeOwnershipRecorder recorder = CodeOwnershipRecorder.start();
        String code;
        try {
            code = new ProjectFilePaths(context.getApplicationContext(), scId).getFileSrc(
                    sourceName,
                    ProjectDataManager.getFileManager(scId),
                    ProjectDataManager.getProjectDataManager(scId),
                    ProjectDataManager.getLibraryManager(scId));
        } finally {
            CodeOwnershipRecorder.stop();
        }
        if (code == null) {
            code = "";
        }
        return build(code, recorder, metadata);
    }

    /**
     * Turns raw generated code that still contains ownership markers into a {@link MappedSource}.
     * Package visible for testing.
     */
    public static MappedSource build(String codeWithMarkers, CodeOwnershipRecorder recorder,
                                     JavaSyncMetadata metadata) {
        List<String> generatedLines = new ArrayList<>();
        List<CodeRegion> regions = new ArrayList<>();

        String normalized = codeWithMarkers.replace("\r\n", "\n").replace('\r', '\n');
        Deque<int[]> open = new ArrayDeque<>();
        for (String line : normalized.split("\n", -1)) {
            int begin = CodeOwnershipRecorder.parseMarker(line, true);
            if (begin >= 0) {
                open.push(new int[]{begin, generatedLines.size()});
                continue;
            }
            int end = CodeOwnershipRecorder.parseMarker(line, false);
            if (end >= 0) {
                int[] started = open.peek();
                if (started != null && started[0] == end) {
                    open.pop();
                    // Only outermost regions are tracked; nested chains stay part of their parent.
                    if (open.isEmpty()) {
                        addRegion(regions, recorder, started[0], started[1], generatedLines.size());
                    }
                }
                continue;
            }
            generatedLines.add(line);
        }
        markDuplicates(regions);

        if (metadata != null && metadata.isWholeSourceMode()) {
            return buildFromWholeSource(metadata);
        }

        List<UserCodeChunk> chunks = metadata == null ? new ArrayList<>()
                : new ArrayList<>(metadata.getUserCode());
        List<LineOverride> overrides = metadata == null ? new ArrayList<>()
                : new ArrayList<>(metadata.getLineOverrides());

        UserCodeInjector.Result injection = UserCodeInjector.inject(generatedLines, chunks, overrides);

        for (CodeRegion region : regions) {
            if (region.kind == CodeRegion.Kind.BLOCK) {
                mapBlockRegion(region, injection);
            }
        }
        for (Map.Entry<String, int[]> entry : injection.chunkRanges.entrySet()) {
            int[] range = entry.getValue();
            if (range[1] > range[0]) {
                regions.add(CodeRegion.ofUserCode(entry.getKey(), range[0], range[1]));
            }
        }

        Map<String, int[]> orphanOverrideRanges = new HashMap<>();
        for (String id : injection.unanchoredOverrides) {
            int[] range = injection.overrideRanges.get(id);
            if (range != null) {
                orphanOverrideRanges.put(id, range);
            }
        }

        return new MappedSource(injection.lines, generatedLines, regions, chunks, overrides,
                injection.unanchored, injection.unanchoredOverrides, orphanOverrideRanges,
                injection.displayToGenerated);
    }

    /**
     * Maps a block region from purely generated coordinates to the merged display coordinates.
     * Because line overrides can remove or multiply generated lines, this can no longer be a simple
     * "generated index + insertions-before" computation; the injector records the exact display
     * span of every generated line instead.
     */
    private static void mapBlockRegion(CodeRegion region, UserCodeInjector.Result injection) {
        int start = region.generatedStartLine;
        int end = region.generatedEndLine;
        if (start < 0 || end <= start || start >= injection.generatedStart.length) {
            return;
        }
        int displayStart = -1;
        int displayEnd = -1;
        for (int g = start; g < end && g < injection.generatedStart.length; g++) {
            if (injection.generatedStart[g] >= 0) {
                if (displayStart < 0) {
                    displayStart = injection.generatedStart[g];
                }
                displayEnd = injection.generatedEnd[g];
            }
        }
        if (displayStart < 0) {
            // The whole region is suppressed; keep an empty display range.
            region.startLine = Math.min(start, injection.lines.size());
            region.endLine = region.startLine;
            return;
        }
        // User chunks inserted between the region's own generated lines belong to the region.
        for (int position = start + 1; position < end; position++) {
            Integer inserted = injection.insertionsAt.get(position);
            if (inserted != null) {
                displayEnd += inserted;
            }
        }
        region.startLine = displayStart;
        region.endLine = displayEnd;
    }

    private static void addRegion(List<CodeRegion> regions, CodeOwnershipRecorder recorder,
                                  int token, int startLine, int endLine) {
        if (endLine <= startLine) {
            return;
        }
        CodeOwnershipRecorder.Owner owner = recorder == null ? null : recorder.get(token);
        if (owner == null) {
            return;
        }
        regions.add(CodeRegion.ofBlock(owner.ownerKey, owner.blockId, owner.opCode, startLine, endLine));
    }

    private static void markDuplicates(List<CodeRegion> regions) {
        Map<String, CodeRegion> seen = new HashMap<>();
        for (CodeRegion region : regions) {
            CodeRegion first = seen.get(region.id());
            if (first == null) {
                seen.put(region.id(), region);
            } else {
                first.duplicate = true;
                region.duplicate = true;
            }
        }
    }

    private static MappedSource buildFromWholeSource(JavaSyncMetadata metadata) {
        List<String> lines = new ArrayList<>(Arrays.asList(
                metadata.wholeSource.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        int[] noMapping = new int[lines.size()];
        Arrays.fill(noMapping, -1);
        return new MappedSource(lines, new ArrayList<>(lines), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new HashMap<>(), noMapping, true);
    }

    /**
     * Convenience helper used by the build pipeline: injects the persisted user code + frame
     * overrides into plainly generated code (no markers involved).
     *
     * @return {@code code} with all manual code and frame overrides applied.
     */
    public static String injectUserCode(String code, JavaSyncMetadata metadata) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        if (metadata != null && metadata.isWholeSourceMode()) {
            return metadata.wholeSource;
        }
        if (metadata == null || (metadata.getUserCode().isEmpty() && metadata.getLineOverrides().isEmpty())) {
            return code;
        }
        boolean crlf = code.contains("\r\n");
        String normalized = crlf ? code.replace("\r\n", "\n") : code;
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        UserCodeInjector.Result injection = UserCodeInjector.inject(lines,
                metadata.getUserCode(), metadata.getLineOverrides());
        String result = String.join("\n", injection.lines);
        return crlf ? result.replace("\n", "\r\n") : result;
    }

    /**
     * Small stable fingerprint used to detect whether the source the user edited has been
     * regenerated since (worst-case fallback bookkeeping).
     */
    static String hash(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}

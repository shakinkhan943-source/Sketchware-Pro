package pro.sketchware.core.sync;

import android.content.Context;

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
 * Builds the {@link MappedSource} of an Activity: the complete generated Java source plus the
 * information which lines belong to which block/event and which lines are manually written code.
 * <p>
 * The generation itself is done by the <b>existing, unmodified</b> code generation pipeline
 * ({@link ProjectFilePaths#getFileSrc}). The only difference is that a
 * {@link CodeOwnershipRecorder} is active while generating, which makes
 * {@link pro.sketchware.core.codegen.BlockInterpreter} surround the code of every top level block
 * with marker comments. Those markers are removed again here and turned into line ranges, so the
 * source handed to the editor is identical to the one the compiler sees.
 */
public final class JavaSourceMapper {

    private JavaSourceMapper() {
    }

    /**
     * Generates and maps the source of {@code projectFile}. Must be called off the main thread.
     */
    public static MappedSource map(Context context, String scId, ProjectFileBean projectFile,
                                   JavaSyncMetadata metadata) {
        String javaName = projectFile.getJavaName();
        CodeOwnershipRecorder recorder = CodeOwnershipRecorder.start();
        String code;
        try {
            code = new ProjectFilePaths(context.getApplicationContext(), scId).getFileSrc(
                    javaName,
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

        List<UserCodeChunk> chunks = metadata == null ? new ArrayList<>()
                : new ArrayList<>(metadata.getUserCode());
        UserCodeInjector.Result injection = UserCodeInjector.inject(generatedLines, chunks);

        int[] prefix = buildPrefix(injection.insertionsAt, generatedLines.size());
        for (CodeRegion region : regions) {
            region.startLine = region.generatedStartLine + prefixAt(prefix, region.generatedStartLine);
            region.endLine = region.generatedEndLine + prefixAt(prefix, region.generatedEndLine - 1);
        }
        for (Map.Entry<String, int[]> entry : injection.chunkRanges.entrySet()) {
            int[] range = entry.getValue();
            if (range[1] > range[0]) {
                regions.add(CodeRegion.ofUserCode(entry.getKey(), range[0], range[1]));
            }
        }

        int[] displayToGenerated = new int[injection.lines.size()];
        boolean[] isUserLine = new boolean[injection.lines.size()];
        for (int[] range : injection.chunkRanges.values()) {
            for (int i = range[0]; i < range[1] && i < isUserLine.length; i++) {
                isUserLine[i] = true;
            }
        }
        int generatedIndex = 0;
        for (int i = 0; i < displayToGenerated.length; i++) {
            if (isUserLine[i]) {
                displayToGenerated[i] = -1;
            } else {
                displayToGenerated[i] = generatedIndex < generatedLines.size() ? generatedIndex : -1;
                generatedIndex++;
            }
        }

        return new MappedSource(injection.lines, generatedLines, regions, chunks,
                injection.unanchored, displayToGenerated);
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

    private static int[] buildPrefix(Map<Integer, Integer> insertionsAt, int generatedLineCount) {
        int[] prefix = new int[generatedLineCount + 2];
        if (insertionsAt.isEmpty()) {
            return prefix;
        }
        for (Map.Entry<Integer, Integer> entry : insertionsAt.entrySet()) {
            int position = Math.max(0, Math.min(entry.getKey(), generatedLineCount + 1));
            prefix[position] += entry.getValue();
        }
        for (int i = 1; i < prefix.length; i++) {
            prefix[i] += prefix[i - 1];
        }
        return prefix;
    }

    private static int prefixAt(int[] prefix, int index) {
        if (index < 0) {
            return 0;
        }
        return prefix[Math.min(index, prefix.length - 1)];
    }

    /**
     * Convenience helper used by the build pipeline: injects the persisted user code into plainly
     * generated code (no markers involved).
     *
     * @return {@code code} with all manual code of that Activity injected.
     */
    public static String injectUserCode(String code, JavaSyncMetadata metadata) {
        if (code == null || code.isEmpty() || metadata == null || metadata.getUserCode().isEmpty()) {
            return code;
        }
        boolean crlf = code.contains("\r\n");
        String normalized = crlf ? code.replace("\r\n", "\n") : code;
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        UserCodeInjector.Result injection = UserCodeInjector.inject(lines, metadata.getUserCode());
        String result = String.join("\n", injection.lines);
        return crlf ? result.replace("\n", "\r\n") : result;
    }
}

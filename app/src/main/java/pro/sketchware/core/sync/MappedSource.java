package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete Java source of one Activity together with the mapping of which lines belong to which
 * block or to manually written user code.
 * <p>
 * Instances are produced by {@link JavaSourceMapper} and consumed by {@link JavaSyncEngine}. A
 * mapped source is always <i>self consistent</i>: {@link #lines} is exactly what the user sees in
 * the Java editor and every {@link CodeRegion} points into it.
 */
public class MappedSource {

    /**
     * Lines of the source the user sees (generated code + injected user code).
     */
    public final List<String> lines;
    /**
     * Lines of the purely generated source, without any injected user code.
     * Used to compute content anchors for user code chunks.
     */
    public final List<String> generatedLines;
    /**
     * All known regions, sorted by {@link CodeRegion#startLine} and never overlapping.
     */
    public final List<CodeRegion> regions;
    /**
     * User code chunks that were injected into {@link #lines}.
     */
    public final List<UserCodeChunk> chunks;
    /**
     * Ids of chunks whose anchor could not be found any more. They are appended at the end of the
     * file, commented out, and reported to the user instead of being deleted.
     */
    public final List<String> unanchoredChunkIds;
    /**
     * For every displayed line the index of the line inside {@link #generatedLines}, or {@code -1}
     * when the line is manually written user code. Used to anchor user code by content.
     */
    public final int[] displayToGenerated;

    public MappedSource(List<String> lines, List<String> generatedLines, List<CodeRegion> regions,
                        List<UserCodeChunk> chunks, List<String> unanchoredChunkIds,
                        int[] displayToGenerated) {
        this.lines = lines;
        this.generatedLines = generatedLines;
        this.regions = regions;
        this.chunks = chunks;
        this.unanchoredChunkIds = unanchoredChunkIds;
        this.displayToGenerated = displayToGenerated;
        Collections.sort(this.regions, (a, b) -> Integer.compare(a.startLine, b.startLine));
    }

    public String getText() {
        return String.join("\n", lines);
    }

    public String getGeneratedText() {
        return String.join("\n", generatedLines);
    }

    /**
     * @return the text of a region as it currently appears in the source.
     */
    public String textOf(CodeRegion region) {
        return textOf(region.startLine, region.endLine);
    }

    public String textOf(int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < endLine && i < lines.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * @return the region containing {@code line}, or {@code null} for framework/generated code.
     * User code regions win over block regions when a manual snippet sits inside a block's code.
     */
    public CodeRegion regionAt(int line) {
        for (CodeRegion region : regions) {
            if (region.kind == CodeRegion.Kind.USER && line >= region.startLine && line < region.endLine) {
                return region;
            }
        }
        for (CodeRegion region : regions) {
            if (line >= region.startLine && line < region.endLine) {
                return region;
            }
        }
        return null;
    }

    /**
     * @return the index inside {@link #generatedLines} of the last generated line at or before
     * {@code displayLine}, or {@code -1} when there is none.
     */
    public int generatedLineAtOrBefore(int displayLine) {
        for (int i = Math.min(displayLine, displayToGenerated.length - 1); i >= 0; i--) {
            if (displayToGenerated[i] >= 0) {
                return displayToGenerated[i];
            }
        }
        return -1;
    }

    public CodeRegion findBlockRegion(String ownerKey, String blockId) {
        for (CodeRegion region : regions) {
            if (region.kind == CodeRegion.Kind.BLOCK
                    && region.ownerKey.equals(ownerKey) && region.blockId.equals(blockId)) {
                return region;
            }
        }
        return null;
    }

    public CodeRegion findById(String regionId) {
        for (CodeRegion region : regions) {
            if (region.id().equals(regionId)) {
                return region;
            }
        }
        return null;
    }

    public List<CodeRegion> blockRegions() {
        List<CodeRegion> result = new ArrayList<>();
        for (CodeRegion region : regions) {
            if (region.kind == CodeRegion.Kind.BLOCK) {
                result.add(region);
            }
        }
        return result;
    }
}

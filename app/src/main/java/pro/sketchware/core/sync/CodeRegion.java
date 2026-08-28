package pro.sketchware.core.sync;

/**
 * A range of lines inside a generated Activity source file together with the information about
 * <i>who owns it</i>.
 * <p>
 * Two kinds of ownership exist:
 * <ul>
 *     <li>{@link Kind#BLOCK} — the lines were generated from a block of an event/More Block
 *     ({@link #ownerKey} + {@link #blockId}). Editing them in the Java editor is pushed back
 *     into the block system.</li>
 *     <li>{@link Kind#USER} — the lines were typed by the user directly in the Java editor and have
 *     no corresponding block ({@link #chunkId}). They are kept in the project's synchronization
 *     metadata and re-inserted into every generated version of the file.</li>
 * </ul>
 * Everything that is <i>not</i> covered by a region is framework/generated infrastructure which the
 * synchronization layer never removes (see {@code JavaSyncEngine}).
 */
public class CodeRegion {

    public enum Kind {
        BLOCK,
        USER
    }

    public final Kind kind;
    /**
     * Block entry key, e.g. {@code "button1_onClick"}, {@code "onCreate_initializeLogic"},
     * {@code "myMoreBlock_moreBlock"}. Only set for {@link Kind#BLOCK}.
     */
    public final String ownerKey;
    /**
     * {@link pro.sketchware.beans.BlockBean#id} of the owning block. Only set for {@link Kind#BLOCK}.
     */
    public final String blockId;
    public final String opCode;
    /**
     * Id of the user code chunk. Only set for {@link Kind#USER}.
     */
    public final String chunkId;

    /**
     * Line range inside the source the user sees (inclusive, exclusive).
     */
    public int startLine;
    public int endLine;

    /**
     * Line range inside the purely generated source (without injected user code).
     * {@code -1} for user regions.
     */
    public int generatedStartLine = -1;
    public int generatedEndLine = -1;

    /**
     * {@code true} when the same block produced more than one region in the file (for example a
     * More Block that is inlined twice). Duplicated regions are shown but never used as the source
     * of truth for a reverse synchronization, to avoid ambiguous updates.
     */
    public boolean duplicate;

    private CodeRegion(Kind kind, String ownerKey, String blockId, String opCode, String chunkId,
                       int startLine, int endLine) {
        this.kind = kind;
        this.ownerKey = ownerKey;
        this.blockId = blockId;
        this.opCode = opCode;
        this.chunkId = chunkId;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public static CodeRegion ofBlock(String ownerKey, String blockId, String opCode, int startLine, int endLine) {
        CodeRegion region = new CodeRegion(Kind.BLOCK, ownerKey, blockId, opCode, null, startLine, endLine);
        region.generatedStartLine = startLine;
        region.generatedEndLine = endLine;
        return region;
    }

    public static CodeRegion ofUserCode(String chunkId, int startLine, int endLine) {
        return new CodeRegion(Kind.USER, null, null, null, chunkId, startLine, endLine);
    }

    public String id() {
        return kind == Kind.BLOCK ? "block:" + ownerKey + "#" + blockId : "user:" + chunkId;
    }

    public int lineCount() {
        return endLine - startLine;
    }

    public boolean isSourceCodeBlock() {
        return "addSourceDirectly".equals(opCode);
    }

    @Override
    public String toString() {
        return id() + "[" + startLine + "," + endLine + ")";
    }
}

package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of Java code the user typed directly into the Java editor and which has no corresponding
 * block ("user-managed code", see requirement "Handling User-Added Java Code").
 * <p>
 * Chunks are persisted with the project ({@link JavaSyncMetadata}) and are re-inserted into every
 * generated version of the Activity — both when the Java editor is opened and when the project is
 * compiled/exported — so manual code is never lost.
 * <p>
 * A chunk is anchored by <b>content</b>, never by a line number:
 * <ol>
 *     <li>{@link #anchorText}: the trimmed text of the generated line the chunk follows, together
 *     with {@link #anchorOccurrence} (which occurrence of that text).</li>
 *     <li>{@link #contextText}: the trimmed text of the enclosing block opener (method signature,
 *     listener, …). Used when the exact anchor line disappeared; the chunk is then appended at the
 *     end of that block.</li>
 *     <li>If both fail the chunk is kept, but commented out at the end of the file, so the user's
 *     code is preserved instead of silently deleted.</li>
 * </ol>
 */
public class UserCodeChunk {

    /**
     * Stable id, kept across saves so the editor can track a chunk.
     */
    public String id = "";
    /**
     * Trimmed text of the line this chunk is placed after. Empty means "top of file".
     */
    public String anchorText = "";
    /**
     * 0 based index of the occurrence of {@link #anchorText} inside the generated file.
     */
    public int anchorOccurrence;
    /**
     * Trimmed text of the enclosing block opener, used as fallback anchor.
     */
    public String contextText = "";
    /**
     * Region id ({@link CodeRegion#id()}) the anchor line belonged to, purely informational: it
     * lets the UI tell the user which event a manual snippet lives in.
     */
    public String anchorRegionId = "";
    /**
     * The user's code, one entry per line, stored without the surrounding indentation.
     */
    public List<String> lines = new ArrayList<>();
    /**
     * Indentation that was used in front of the first line, re-applied on injection.
     */
    public String indent = "";

    public UserCodeChunk() {
    }

    public UserCodeChunk(String id) {
        this.id = id;
    }

    public boolean isEmpty() {
        if (lines == null || lines.isEmpty()) {
            return true;
        }
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public String text() {
        return String.join("\n", lines);
    }

    public UserCodeChunk copy() {
        UserCodeChunk copy = new UserCodeChunk(id);
        copy.anchorText = anchorText;
        copy.anchorOccurrence = anchorOccurrence;
        copy.contextText = contextText;
        copy.anchorRegionId = anchorRegionId;
        copy.indent = indent;
        copy.lines = new ArrayList<>(lines);
        return copy;
    }
}

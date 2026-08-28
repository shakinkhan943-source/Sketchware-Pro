package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent synchronization metadata of a single Activity (one {@code *.java} file of a project).
 * <p>
 * It is stored as JSON next to the other project data
 * ({@code .sketchware/data/<sc_id>/java_sync/<Activity>.java.json}) so it survives closing,
 * reopening, exporting, importing and compiling a project. Projects that never used the Java editor
 * simply have no metadata file and behave exactly like before.
 */
public class JavaSyncMetadata {

    /**
     * Metadata format version, so future changes can migrate old projects.
     */
    public int version = 1;

    /**
     * Java file this metadata belongs to, e.g. {@code "MainActivity.java"}.
     */
    public String javaName = "";

    /**
     * Code the user wrote manually inside the Java editor.
     */
    public List<UserCodeChunk> userCode = new ArrayList<>();

    /**
     * Last known generated text of every block region ({@link CodeRegion#id()} → code).
     * Used to detect three-way conflicts: if the freshly generated code of a region differs from
     * this snapshot the blocks changed, and if the editor content differs too both sides changed.
     */
    public Map<String, String> regionSnapshots = new HashMap<>();

    /**
     * Hash of the complete source the editor showed the last time it was synchronized.
     */
    public String lastSyncedSourceHash = "";

    /**
     * {@code System.currentTimeMillis()} of the last successful synchronization.
     */
    public long lastSyncedAt;

    public JavaSyncMetadata() {
    }

    public JavaSyncMetadata(String javaName) {
        this.javaName = javaName;
    }

    public boolean isEmpty() {
        return (userCode == null || userCode.isEmpty())
                && (regionSnapshots == null || regionSnapshots.isEmpty());
    }

    public List<UserCodeChunk> getUserCode() {
        if (userCode == null) {
            userCode = new ArrayList<>();
        }
        return userCode;
    }

    public Map<String, String> getRegionSnapshots() {
        if (regionSnapshots == null) {
            regionSnapshots = new HashMap<>();
        }
        return regionSnapshots;
    }
}

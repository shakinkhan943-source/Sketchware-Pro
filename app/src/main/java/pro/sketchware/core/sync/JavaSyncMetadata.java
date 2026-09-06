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
 * <p>
 * Version 2 adds {@link #lineOverrides} (edits to generated framework/core lines) and
 * {@link #wholeSourceOverride} (an emergency, whole-file manual mode used when an edit is so large
 * that line alignment is no longer trustworthy). Version 1 files load unchanged — the new lists
 * simply default to empty.
 */
public class JavaSyncMetadata {

    /**
     * Metadata format version, so future changes can migrate old projects.
     */
    public int version = 2;

    /**
     * Java file this metadata belongs to, e.g. {@code "MainActivity.java"}.
     */
    public String javaName = "";

    /**
     * Code the user wrote manually inside the Java editor (code with no block behind it, plus
     * updated user chunks). Re-injected on top of every generated version.
     */
    public List<UserCodeChunk> userCode = new ArrayList<>();

    /**
     * Edits the user made to generated framework/core lines. Re-applied on top of every generated
     * version so generated code never overwrites them again.
     */
    public List<LineOverride> lineOverrides = new ArrayList<>();

    /**
     * {@code true} when the whole file is user-managed because the edit was too large to align
     * safely with the generated source. While set, {@link #wholeSource} is used verbatim for the
     * editor and for builds; block changes do not regenerate it until the user reloads from blocks.
     */
    public boolean wholeSourceOverride;

    /**
     * The complete user source when {@link #wholeSourceOverride} is {@code true}.
     */
    public String wholeSource = "";

    /**
     * Hash of the source that was shown when whole-file mode was activated. Informational: lets
     * tooling detect how far the generated version has moved since.
     */
    public String wholeSourceBaseHash = "";

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
                && (lineOverrides == null || lineOverrides.isEmpty())
                && (regionSnapshots == null || regionSnapshots.isEmpty())
                && !isWholeSourceMode();
    }

    public boolean isWholeSourceMode() {
        return wholeSourceOverride && wholeSource != null && !wholeSource.isEmpty();
    }

    public List<UserCodeChunk> getUserCode() {
        if (userCode == null) {
            userCode = new ArrayList<>();
        }
        return userCode;
    }

    public List<LineOverride> getLineOverrides() {
        if (lineOverrides == null) {
            lineOverrides = new ArrayList<>();
        }
        return lineOverrides;
    }

    public Map<String, String> getRegionSnapshots() {
        if (regionSnapshots == null) {
            regionSnapshots = new HashMap<>();
        }
        return regionSnapshots;
    }

    /**
     * Clears whole-file mode and all user layers, returning to pure block generation.
     * Used by the explicit "Reload from blocks" action.
     */
    public void clearWholeSourceMode() {
        wholeSourceOverride = false;
        wholeSource = "";
        wholeSourceBaseHash = "";
    }
}

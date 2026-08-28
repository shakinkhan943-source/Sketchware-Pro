package pro.sketchware.core.sync;

import android.content.Context;

import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.util.LogUtil;

/**
 * Entry point of the Java ↔ blocks synchronization layer.
 * <pre>
 *                    ┌────────────────────┐
 *                    │   Project Activity │
 *                    └─────────┬──────────┘
 *                              │
 *                  ┌───────────▼───────────┐
 *                  │  JavaSyncManager      │
 *                  └───────────┬───────────┘
 *                            ↙   ↘
 *                 ┌───────────┐   ┌──────────────┐
 *                 │  Blocks   │   │ Java editor  │
 *                 │  /Events  │   │ Full source  │
 *                 └───────────┘   └──────────────┘
 * </pre>
 * <ul>
 *     <li>Blocks → Java: {@link #loadSource} regenerates the Activity with the normal pipeline and
 *     re-injects manually written code.</li>
 *     <li>Java → Blocks: {@link #analyze} maps the user's edits back onto the blocks and
 *     {@link #apply} writes them.</li>
 *     <li>Build/export: {@link #injectUserCode} makes sure manually written code is part of the
 *     compiled source as well.</li>
 * </ul>
 * Everything is a no-op for projects that never used the Java editor.
 */
public final class JavaSyncManager {

    private JavaSyncManager() {
    }

    /**
     * Generates the full Java source of an Activity including the ownership mapping.
     * Must be called from a background thread.
     */
    public static MappedSource loadSource(Context context, String scId, ProjectFileBean projectFile) {
        JavaSyncMetadata metadata = JavaSyncStore.load(scId, projectFile.getJavaName());
        return JavaSourceMapper.map(context, scId, projectFile, metadata);
    }

    /**
     * Compares the edited source with the blocks and returns what would change.
     * Must be called from a background thread.
     *
     * @param baseline the mapping that was shown to the user when the editor was filled
     * @param edited   the current editor content
     */
    public static SyncPlan analyze(Context context, String scId, ProjectFileBean projectFile,
                                   MappedSource baseline, String edited) {
        MappedSource current = loadSource(context, scId, projectFile);
        return JavaSyncEngine.analyze(baseline, edited, current);
    }

    public static JavaSyncApplier.Report apply(String scId, ProjectFileBean projectFile, SyncPlan plan,
                                               JavaSyncApplier.ConflictResolution resolution,
                                               boolean allowConversion) {
        return JavaSyncApplier.apply(scId, projectFile.getJavaName(), plan, resolution, allowConversion);
    }

    /**
     * Injects the manually written Java code of an Activity into freshly generated code.
     * <p>
     * Called by the build/export pipeline, so what gets compiled is what the user saw in the Java
     * editor. Returns {@code code} unchanged when the Activity has no synchronization metadata,
     * which keeps existing projects byte-identical to before.
     */
    public static String injectUserCode(String scId, String javaName, String code) {
        if (scId == null || javaName == null || code == null || code.isEmpty()) {
            return code;
        }
        try {
            if (!JavaSyncStore.hasMetadata(scId, javaName)) {
                return code;
            }
            JavaSyncMetadata metadata = JavaSyncStore.load(scId, javaName);
            if (metadata.getUserCode().isEmpty()) {
                return code;
            }
            return JavaSourceMapper.injectUserCode(code, metadata);
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncManager", "Failed to inject user Java code into " + javaName, e);
            return code;
        }
    }

    /**
     * @return {@code true} when the given Activity has manually written Java code attached.
     */
    public static boolean hasUserCode(String scId, String javaName) {
        return JavaSyncStore.hasMetadata(scId, javaName)
                && !JavaSyncStore.load(scId, javaName).getUserCode().isEmpty();
    }
}

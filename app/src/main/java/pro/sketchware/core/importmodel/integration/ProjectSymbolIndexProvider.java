package pro.sketchware.core.importmodel.integration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.SymbolIndex;
import pro.sketchware.core.importmodel.SymbolIndexCache;
import pro.sketchware.core.importmodel.SymbolIndexer;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.LogUtil;

/**
 * Builds and caches the {@link SymbolIndex} for a Sketchware project.
 *
 * <p>Flow: {@link ProjectClasspath} (Sketchware's own dependency data) &rarr;
 * {@link SymbolIndexer} &rarr; cached {@link SymbolIndex}.</p>
 *
 * <p>The index is held in memory per project and invalidated only when the classpath fingerprint
 * changes; {@link SymbolIndexCache} persists that fingerprint so the reason for a rebuild survives
 * a process restart. Indexing is never triggered by typing — only by the manual Resolve/Organize
 * Imports actions, and it must be called off the main thread.</p>
 */
public final class ProjectSymbolIndexProvider {

    private static final String TAG = "ImportModel";

    /** Priorities: lower wins. Mirrors the source ranking required by the import model. */
    private static final int PRIORITY_PROJECT = 10;
    private static final int PRIORITY_LOCAL_DEPENDENCY = 30;
    private static final int PRIORITY_ANDROID_SDK = 40;
    private static final int PRIORITY_ANDROIDX = 50;
    private static final int PRIORITY_KOTLIN = 60;
    private static final int PRIORITY_OTHER = 70;

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private ProjectSymbolIndexProvider() {
    }

    private static final class Entry {
        final String fingerprint;
        final SymbolIndex index;

        Entry(String fingerprint, SymbolIndex index) {
            this.fingerprint = fingerprint;
            this.index = index;
        }
    }

    /**
     * Returns the symbol index for {@code scId}, rebuilding it only when the project's
     * dependencies or sources changed. Blocking; call from a background thread.
     */
    public static SymbolIndex get(String scId) {
        ProjectClasspath classpath = ProjectClasspath.of(scId);
        synchronized (CACHE) {
            Entry cached = CACHE.get(scId);
            if (cached != null && cached.fingerprint.equals(classpath.fingerprint())) {
                return cached.index;
            }
        }

        SymbolIndex index = build(classpath);

        try {
            new SymbolIndexCache(cacheFile(scId)).writeFingerprint(classpath.fingerprint());
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to persist symbol index fingerprint", e);
        }

        synchronized (CACHE) {
            CACHE.put(scId, new Entry(classpath.fingerprint(), index));
        }
        return index;
    }

    /** @return {@code true} when the persisted fingerprint still matches the project on disk. */
    public static boolean isPersistedIndexValid(String scId) {
        return new SymbolIndexCache(cacheFile(scId)).isValid(ProjectClasspath.of(scId).fingerprint());
    }

    public static void invalidate(String scId) {
        synchronized (CACHE) {
            CACHE.remove(scId);
        }
    }

    private static File cacheFile(String scId) {
        return new File(SketchwarePaths.getDataPath(scId), "import_index.bin");
    }

    private static SymbolIndex build(ProjectClasspath classpath) {
        SymbolIndex index = new SymbolIndex();

        // Project-local sources first: they must win over anything on the classpath.
        for (File root : classpath.sourceRoots()) {
            indexSourceRoot(index, root);
        }

        for (File library : classpath.libraries()) {
            int priority = priorityOf(library);
            SymbolIndex.Origin origin = originOf(library);
            List<File> single = new ArrayList<>(1);
            single.add(library);
            // Types from a JVM archive are visible to both languages, so index them twice —
            // once per language — because SymbolIndex.find() filters by language.
            for (ImportLanguage language : ImportLanguage.values()) {
                try {
                    SymbolIndexer.indexClasspath(index, single, language, origin, priority);
                } catch (Throwable e) {
                    LogUtil.w(TAG, "Failed to index " + library.getName(), e);
                }
            }
            indexKotlinFacades(index, library, origin, priority);
        }

        LogUtil.d(TAG, "Symbol index built for project " + classpath.scId()
                + ": " + index.size() + " candidates from " + classpath.libraries().size()
                + " archives and " + classpath.sourceRoots().size() + " source roots");
        return index;
    }

    /**
     * Kotlin top-level functions/properties live in {@code *Kt} facade classes. JARs are read
     * directly; an AAR's {@code classes.jar} is extracted to a temporary file first, exactly like
     * {@link SymbolIndexer} does for types.
     */
    private static void indexKotlinFacades(SymbolIndex index, File archive,
                                           SymbolIndex.Origin origin, int priority) {
        String name = archive.getName().toLowerCase(Locale.US);
        try {
            if (name.endsWith(".jar")) {
                KotlinFacadeIndexer.indexJar(index, archive, origin, priority);
            } else if (name.endsWith(".aar")) {
                File extracted = extractClassesJar(archive);
                if (extracted != null) {
                    try {
                        KotlinFacadeIndexer.indexJar(index, extracted, origin, priority);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        extracted.delete();
                    }
                }
            }
        } catch (Throwable e) {
            LogUtil.w(TAG, "Failed to index Kotlin facades of " + archive.getName(), e);
        }
    }

    private static File extractClassesJar(File aar) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(aar)) {
            java.util.zip.ZipEntry entry = zip.getEntry("classes.jar");
            if (entry == null) return null;
            File temp = File.createTempFile("importmodel-facade", ".jar");
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.OutputStream out = new java.io.FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            return temp;
        }
    }

    private static void indexSourceRoot(SymbolIndex index, File root) {
        for (ImportLanguage language : ImportLanguage.values()) {
            try {
                SymbolIndexer.indexSource(index, root, language, SymbolIndex.Origin.PROJECT, PRIORITY_PROJECT);
            } catch (Throwable e) {
                LogUtil.w(TAG, "Failed to index source root " + root, e);
            }
        }
    }

    private static SymbolIndex.Origin originOf(File file) {
        String path = file.getAbsolutePath().toLowerCase(Locale.US);
        String name = file.getName().toLowerCase(Locale.US);
        if (name.equals("android.jar")) return SymbolIndex.Origin.ANDROID_SDK;
        if (path.contains("kotlin-stdlib") || path.contains("kotlinx-")) return SymbolIndex.Origin.KOTLIN;
        if (isAndroidXArchive(path)) return SymbolIndex.Origin.ANDROIDX;
        return SymbolIndex.Origin.DEPENDENCY;
    }

    private static int priorityOf(File file) {
        switch (originOf(file)) {
            case ANDROID_SDK:
                return PRIORITY_ANDROID_SDK;
            case ANDROIDX:
                return PRIORITY_ANDROIDX;
            case KOTLIN:
                return PRIORITY_KOTLIN;
            case DEPENDENCY:
                return isProjectLocalDependency(file) ? PRIORITY_LOCAL_DEPENDENCY : PRIORITY_OTHER;
            default:
                return PRIORITY_OTHER;
        }
    }

    private static boolean isProjectLocalDependency(File file) {
        String path = file.getAbsolutePath();
        return path.contains(File.separator + "classpath" + File.separator)
                || path.contains(File.separator + "local_library" + File.separator);
    }

    private static boolean isAndroidXArchive(String lowerCasePath) {
        // AndroidX artifacts are shipped by name (e.g. "appcompat-1.7.1/classes.jar"); the built-in
        // library directory is the authoritative list, so anything under it that is not the Kotlin
        // runtime is treated as an AndroidX/Google dependency.
        return lowerCasePath.contains("androidx")
                || lowerCasePath.contains(File.separator + "libs" + File.separator + "libs" + File.separator);
    }
}

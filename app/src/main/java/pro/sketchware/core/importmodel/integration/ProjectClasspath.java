package pro.sketchware.core.importmodel.integration;

import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import pro.sketchware.core.build.BuildSettings;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.util.library.ComposeBuiltInLibraries;
import pro.sketchware.util.library.ManageLocalLibrary;

/**
 * Snapshot of the classpath and source roots a Sketchware project compiles against.
 *
 * <p>This class does not invent a second dependency system: every entry comes from the managers
 * Sketchware already uses when building a project
 * ({@link BuildSettings}, {@link BuiltInLibraries}, {@link ComposeBuiltInLibraries},
 * {@link ManageLocalLibrary} and the project's own {@code classpath} folder), i.e. the exact same
 * inputs that {@code ProjectBuilder#getClasspath()} assembles for ECJ/kotlinc.</p>
 *
 * <p>The snapshot is cheap to create (it only stats files) and carries a {@link #fingerprint()}
 * so the symbol index can be rebuilt only when the dependency set really changed.</p>
 */
public final class ProjectClasspath {

    private final String scId;
    private final List<File> libraries;
    private final List<File> sourceRoots;
    private final String fingerprint;

    private ProjectClasspath(String scId, List<File> libraries, List<File> sourceRoots, String fingerprint) {
        this.scId = scId;
        this.libraries = libraries;
        this.sourceRoots = sourceRoots;
        this.fingerprint = fingerprint;
    }

    public String scId() {
        return scId;
    }

    /** JAR/AAR files available to the project, in resolution order. */
    public List<File> libraries() {
        return libraries;
    }

    /** Source directories owned by the project (user sources first). */
    public List<File> sourceRoots() {
        return sourceRoots;
    }

    /** Changes whenever an entry is added, removed, resized or re-written. */
    public String fingerprint() {
        return fingerprint;
    }

    public static ProjectClasspath of(String scId) {
        Set<File> libs = new LinkedHashSet<>();

        BuildSettings buildSettings = new BuildSettings(scId);

        /* android.jar, exactly like the builder resolves it */
        File defaultAndroidJar = new File(BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH, "android.jar");
        addIfFile(libs, new File(buildSettings.getValue(BuildSettings.SETTING_ANDROID_JAR_PATH,
                defaultAndroidJar.getAbsolutePath())));

        /* Built-in libraries that are extracted on this device. */
        File[] builtIn = BuiltInLibraries.EXTRACTED_BUILT_IN_LIBRARIES_PATH.listFiles();
        if (builtIn != null) {
            for (File libraryDir : builtIn) {
                addIfFile(libs, new File(libraryDir, "classes.jar"));
            }
        }

        /* Legacy built-in Compose bundle, when the device has it. */
        try {
            if (ComposeBuiltInLibraries.isBundleAvailable()) {
                for (ComposeBuiltInLibraries.ComposeArtifact artifact : ComposeBuiltInLibraries.getManifest().artifacts) {
                    addIfFile(libs, ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id));
                }
            }
        } catch (Throwable ignored) {
            // A missing/!invalid bundle simply contributes nothing to the index.
        }

        /* Local libraries activated by this project (also covers the shared Jetpack store). */
        try {
            for (File jar : new ManageLocalLibrary(scId).getLocalLibraryJars()) {
                addIfFile(libs, jar);
            }
        } catch (Throwable ignored) {
        }

        /* User-defined classpath from Build Settings. */
        String extra = buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, "");
        if (!TextUtils.isEmpty(extra)) {
            for (String part : extra.split(":")) {
                if (!part.trim().isEmpty()) addIfFile(libs, new File(part.trim()));
            }
        }

        /* JARs dropped into the project's own classpath folder. */
        String classpathDirectory = SketchwarePaths.getProjectClasspathPath(scId) + File.separator;
        for (String jar : FileUtil.listFiles(classpathDirectory, "jar")) {
            addIfFile(libs, new File(jar));
        }

        /* Project sources: user-written files first, then generated ones. */
        Set<File> roots = new LinkedHashSet<>();
        addIfDirectory(roots, new File(SketchwarePaths.getProjectJavaPath(scId)));
        addIfDirectory(roots, new File(SketchwarePaths.getMyscPath(scId)
                + File.separator + "app" + File.separator + "src" + File.separator + "main"
                + File.separator + "java"));

        List<File> libList = new ArrayList<>(libs);
        List<File> rootList = new ArrayList<>(roots);
        return new ProjectClasspath(scId, libList, rootList, buildFingerprint(libList, rootList));
    }

    private static void addIfFile(Set<File> target, File file) {
        if (file == null) return;
        String name = file.getName().toLowerCase(Locale.US);
        if ((name.endsWith(".jar") || name.endsWith(".aar")) && file.isFile()) {
            target.add(file);
        }
    }

    private static void addIfDirectory(Set<File> target, File directory) {
        if (directory != null && directory.isDirectory()) target.add(directory);
    }

    private static String buildFingerprint(List<File> libraries, List<File> sourceRoots) {
        StringBuilder builder = new StringBuilder("v1");
        for (File file : libraries) {
            builder.append('|').append(file.getAbsolutePath())
                    .append(':').append(file.length())
                    .append(':').append(file.lastModified());
        }
        for (File root : sourceRoots) {
            builder.append('#').append(root.getAbsolutePath()).append(':').append(directoryStamp(root, 0));
        }
        return Long.toHexString(builder.toString().hashCode() & 0xffffffffL) + "-" + builder.length();
    }

    /** Cheap recursive stamp of a source root: newest modification time plus file count. */
    private static long directoryStamp(File directory, int depth) {
        if (depth > 8) return 0L;
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long stamp = children.length;
        for (File child : children) {
            if (child.isDirectory()) {
                stamp = stamp * 31 + directoryStamp(child, depth + 1);
            } else {
                String name = child.getName();
                if (name.endsWith(".java") || name.endsWith(".kt")) {
                    stamp = stamp * 31 + child.lastModified() + child.length();
                }
            }
        }
        return stamp;
    }
}

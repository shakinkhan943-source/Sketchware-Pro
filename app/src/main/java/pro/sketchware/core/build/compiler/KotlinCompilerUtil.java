package pro.sketchware.core.build.compiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.project.SketchwarePaths;

public class KotlinCompilerUtil {

    public static boolean areAnyKtFilesPresent(ProjectBuilder bui) {
        return areAnyKtFilesPresent(bui.projectFilePaths);
    }

    public static boolean areAnyKtFilesPresent(ProjectFilePaths projectFilePaths) {
        return getFilesToCompile(projectFilePaths).stream()
                .anyMatch(it -> it.getName().endsWith(".kt"));
    }

    public static List<File> getFilesToCompile(ProjectFilePaths workspace) {
        String scId = workspace.sc_id;
        List<File> mFilesToCompile = new ArrayList<>();

        mFilesToCompile.addAll(getSourceFiles(new File(workspace.javaFilesPath)));
        mFilesToCompile.addAll(getSourceFiles(new File(workspace.rJavaDirectoryPath)));
        mFilesToCompile.addAll(getSourceFiles(new File(SketchwarePaths.getProjectJavaPath(scId))));

        return mFilesToCompile;
    }

    /**
     * Returns only JARs that actually advertise a Kotlin compiler plugin via
     * Kotlin's ServiceLoader descriptors. Runtime dependency JARs (for example
     * kotlin-stdlib shipped beside the Compose plugin) remain available in the
     * project plugin directory but are not accidentally treated as plugins.
     */
    public static List<File> getCompilerPlugins(ProjectFilePaths workspace) {
        String scId = workspace.sc_id;
        File pluginDir = new File(SketchwarePaths.getProjectKotlinCompilerPluginsPath(scId));
        if (!pluginDir.exists()) {
            return Collections.emptyList();
        }

        File[] children = pluginDir.listFiles(c -> c.isFile() && c.getName().endsWith(".jar"));
        if (children == null) {
            return Collections.emptyList();
        }

        List<File> plugins = new ArrayList<>();
        for (File child : children) {
            if (containsCompilerPluginService(child)) {
                plugins.add(child);
            }
        }
        return plugins;
    }

    private static boolean containsCompilerPluginService(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar") != null
                    || zip.getEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor") != null
                    || zip.getEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static List<File> getSourceFiles(File dir) {
        List<File> files = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children == null) return files;

        for (File child : children) {
            if (child.isDirectory()) {
                files.addAll(getSourceFiles(child));
            } else if (child.getName().endsWith(".kt") || child.getName().endsWith(".java")) {
                files.add(child);
            }
        }

        return files;
    }
}

package pro.sketchware.core.build.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * Returns the Compose compiler plugin JARs and the runtime dependency JARs
     * provisioned beside them. Kotlin's plugin classloader must see the plugin's
     * runtime dependencies as part of the plugin classpath; the normal project
     * classpath is not a reliable parent for compiler plugins.
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

        return new ArrayList<>(Arrays.asList(children));
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

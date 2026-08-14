package pro.sketchware.core.build.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.build.BuildProgressReceiver;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.util.library.BuiltInLibraryManager;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.LogUtil;
import pro.sketchware.SketchApplication;

/**
 * Bridge class for Kotlin compilation in the build pipeline.
 *
 * Uses KotlinCompilerEnhanced for faster incremental compilation with:
 * - Automatic file change detection via CRC32 hashing
 * - Compiled class file caching
 * - Optional parallel compilation
 *
 * This maintains the same public interface as before, ensuring
 * compatibility with the rest of the build system.
 */
public class KotlinCompilerBridge {
    private static final String TAG = "KotlinCompilerBridge";

    /** Filename of the bundled Kotlin Compose compiler plugin. */
    private static final String COMPOSE_COMPILER_PLUGIN_JAR = "kotlin-compose-compiler-plugin-2.1.21.jar";

    /** Directory containing the Compose compiler plugin and its runtime dependencies. */
    private static final String COMPOSE_COMPILER_PLUGIN_ASSET_DIR = "libs/kt_plugins";

    /**
     * Ensures the Jetpack Compose compiler plugin and its runtime dependencies are
     * available in the project's kt_plugins folder.
     *
     * The non-embeddable Compose plugin has a runtime dependency on the matching
     * Kotlin stdlib. Compiler plugin classloaders can be isolated from kotlinc's
     * normal runtime classpath, so provisioning only the plugin JAR can make
     * ServiceLoaderLite report the registrar itself as missing even though the
     * registrar is present in the JAR. We therefore mirror every JAR shipped in
     * the APK's kt_plugins asset directory and let KotlinCompilerUtil select only
     * actual compiler plugins when constructing pluginClasspaths.
     *
     * @return true when any Compose plugin asset was installed/upgraded.
     */
    public static boolean maybeProvisionComposeCompilerPlugin(ProjectBuilder builder) {
        if (!projectUsesCompose(builder)) {
            return false;
        }
        try {
            String pluginDirPath = SketchwarePaths.getProjectKotlinCompilerPluginsPath(builder.projectFilePaths.sc_id);
            File pluginDir = new File(pluginDirPath);
            if (!pluginDir.exists() && !pluginDir.mkdirs()) {
                LogUtil.w(TAG, "Failed to create kt_plugins directory: " + pluginDirPath);
                return false;
            }

            boolean changed = false;
            String[] assets = SketchApplication.getAppContext().getAssets().list(COMPOSE_COMPILER_PLUGIN_ASSET_DIR);
            if (assets != null) {
                for (String assetName : assets) {
                    if (!assetName.endsWith(".jar")) {
                        continue;
                    }
                    String assetPath = COMPOSE_COMPILER_PLUGIN_ASSET_DIR + "/" + assetName;
                    File target = new File(pluginDir, assetName);
                    if (ProjectBuilder.hasFileChanged(assetPath, target.getAbsolutePath())) {
                        changed = true;
                    }
                }
            }

            File plugin = new File(pluginDir, COMPOSE_COMPILER_PLUGIN_JAR);
            if (plugin.exists()) {
                LogUtil.d(TAG, "Kotlin Compose compiler plugin is ready: " + plugin.getAbsolutePath());
            } else {
                LogUtil.w(TAG, "Kotlin Compose compiler plugin could not be provisioned from assets");
            }
            return changed;
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to provision Kotlin Compose compiler plugin", e);
            return false;
        }
    }

    private static boolean projectUsesCompose(ProjectBuilder builder) {
        try {
            for (File sourceFile : KotlinCompilerUtil.getFilesToCompile(builder.projectFilePaths)) {
                if (sourceFile.isFile() && sourceFileReferencesCompose(sourceFile)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to scan sources for Compose usage", e);
        }
        return false;
    }

    private static boolean sourceFileReferencesCompose(File sourceFile) {
        if (!sourceFile.canRead() || sourceFile.length() > 2 * 1024 * 1024) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sourceFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("composable") || lower.contains("androidx.compose")
                        || lower.contains("@stable") || lower.contains("@immutable")) {
                    return true;
                }
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "Failed to read source file " + sourceFile.getAbsolutePath(), e);
        }
        return false;
    }

    public static void compileKotlinCodeIfPossible(BuildProgressReceiver receiver,
                                                     ProjectBuilder builder) throws Throwable {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            boolean pluginChanged = maybeProvisionComposeCompilerPlugin(builder);
            receiver.onProgress("Kotlin is compiling...", 12);
            try {
                KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
                if (pluginChanged) {
                    compiler.clearCache();
                }
                compiler.compile();
                LogUtil.d(TAG, compiler.getCacheStats());
            } catch (Exception e) {
                LogUtil.e(TAG, "Kotlin compilation failed", e);
                throw e;
            }
        }
    }

    public static void maybeAddKotlinBuiltInLibraryDependenciesIfPossible(
            ProjectBuilder builder,
            BuiltInLibraryManager builtInLibraryManager) {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.JETBRAINS_KOTLIN_STDLIB);
        }
    }

    public static void maybeAddKotlinFilesToClasspath(StringBuilder classpath,
                                                        ProjectFilePaths workspace) {
        if (FileUtil.isExistFile(workspace.compiledClassesPath)) {
            classpath.append(workspace.compiledClassesPath);
            classpath.append(":");
        }
    }

    public static String getKotlinHome(ProjectFilePaths workspace) {
        return workspace.binDirectoryPath + File.separator + "kotlin_home";
    }

    public static void clearKotlinCompilationCache(ProjectBuilder builder) {
        try {
            KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
            compiler.clearCache();
            LogUtil.d(TAG, "Kotlin compilation cache cleared");
        } catch (Exception e) {
            LogUtil.w(TAG, "Error clearing Kotlin incremental cache", e);
        }
    }
}

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

    /**
     * Filename of the bundled Kotlin Compose compiler plugin. It must match the
     * asset in app/src/main/assets/libs/kt_plugins/ and the module
     * kotlin-compose-compiler-plugin in gradle/libs.versions.toml.
     * Keep all three in lockstep when bumping the Kotlin version.
     * <p>
     * Use the NON-embeddable artifact: the bundled kotlinc-for-sketchware fork is
     * built against the unshaded com.intellij.* compiler classes, so the
     * -embeddable plugin (which references org.jetbrains.kotlin.com.intellij.*)
     * throws NoClassDefFoundError mid-IR-lowering.
     */
    private static final String COMPOSE_COMPILER_PLUGIN_JAR = "kotlin-compose-compiler-plugin-2.1.21.jar";

    /** Path of the plugin jar inside the APK assets, relative to assets/. */
    private static final String COMPOSE_COMPILER_PLUGIN_ASSET_PATH = "libs/kt_plugins/" + COMPOSE_COMPILER_PLUGIN_JAR;

    /**
     * Ensures the Jetpack Compose compiler plugin is available in the project's
     * kt_plugins folder by extracting it from the bundled APK asset when missing
     * or outdated. Once present, {@link KotlinCompilerUtil#getCompilerPlugins}
     * passes it to kotlinc via pluginClasspaths.
     * <p>
     * The plugin is what turns {@code @Composable} into real Compose code;
     * without it kotlinc treats the annotation as a no-op. It is only installed
     * for projects that actually reference Compose so existing non-Compose
     * projects keep building exactly as before.
     *
     * @return true when the plugin was just installed or upgraded, which means any
     *         previously cached kotlinc output must be discarded so the Kotlin
     *         incremental compiler recompiles the module with the active plugin.
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

            File target = new File(pluginDir, COMPOSE_COMPILER_PLUGIN_JAR);
            // Copies the asset only when it is missing or the bundled version differs.
            boolean pluginChanged = ProjectBuilder.hasFileChanged(COMPOSE_COMPILER_PLUGIN_ASSET_PATH, target.getAbsolutePath());
            if (target.exists()) {
                LogUtil.d(TAG, "Kotlin Compose compiler plugin is ready: " + target.getAbsolutePath());
            } else {
                LogUtil.w(TAG, "Kotlin Compose compiler plugin could not be provisioned from assets");
            }
            return pluginChanged;
        } catch (Exception e) {
            // Provisioning must never break the build; kotlinc just falls back to no Compose support.
            LogUtil.w(TAG, "Failed to provision Kotlin Compose compiler plugin", e);
            return false;
        }
    }

    /**
     * Cheap heuristic: returns true when any project source file references the
     * Jetpack Compose runtime ({@code @Composable}/{@code @Stable}/{@code @Immutable}
     * or {@code androidx.compose.*} imports). The compiler plugin is only needed
     * in that case.
     */
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

    /**
     * Compile Kotlin code if any .kt files are present in the project.
     * Uses enhanced incremental compiler for faster builds.
     *
     * @param receiver Build progress callback
     * @param builder  Project builder context
     * @throws Throwable if compilation fails
     */
    public static void compileKotlinCodeIfPossible(BuildProgressReceiver receiver,
                                                     ProjectBuilder builder) throws Throwable {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            boolean pluginChanged = maybeProvisionComposeCompilerPlugin(builder);
            receiver.onProgress("Kotlin is compiling...", 12);
            try {
                // Use enhanced compiler with incremental compilation support
                KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
                if (pluginChanged) {
                    // Discard cached kotlinc output from before the Compose plugin
                    // was active, otherwise the incremental cache would skip
                    // recompilation and keep the old non-Compose bytecode.
                    compiler.clearCache();
                }
                compiler.compile();

                // Log cache statistics for debugging
                LogUtil.d(TAG, compiler.getCacheStats());
            } catch (Exception e) {
                LogUtil.e(TAG, "Kotlin compilation failed", e);
                throw e;
            }
        }
    }

    /**
     * Add Kotlin standard library dependency if Kotlin files are present.
     *
     * @param builder               Project builder context
     * @param builtInLibraryManager Library manager for adding dependencies
     */
    public static void maybeAddKotlinBuiltInLibraryDependenciesIfPossible(
            ProjectBuilder builder,
            BuiltInLibraryManager builtInLibraryManager) {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.JETBRAINS_KOTLIN_STDLIB);
        }
    }

    /**
     * Add compiled Kotlin classes to the classpath for subsequent compilation steps.
     *
     * @param classpath String builder accumulating classpath entries
     * @param workspace Project file paths
     */
    public static void maybeAddKotlinFilesToClasspath(StringBuilder classpath,
                                                        ProjectFilePaths workspace) {
        if (FileUtil.isExistFile(workspace.compiledClassesPath)) {
            classpath.append(workspace.compiledClassesPath);
            classpath.append(":");
        }
    }

    /**
     * Get the Kotlin home directory for compiler runtime.
     *
     * @param workspace Project file paths
     * @return Path to kotlin_home directory in bin
     */
    public static String getKotlinHome(ProjectFilePaths workspace) {
        return workspace.binDirectoryPath + File.separator + "kotlin_home";
    }

    /**
     * Clear Kotlin compilation cache and force full rebuild on next build.
     * Useful for troubleshooting or when cache becomes corrupted.
     *
     * @param builder Project builder context
     */
    public static void clearKotlinCompilationCache(ProjectBuilder builder) {
        try {
            KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
            compiler.clearCache();
            LogUtil.d(TAG, "Kotlin compilation cache cleared");
        } catch (Exception e) {
            LogUtil.w(TAG, "Error clearing Kotlin compilation cache", e);
        }
    }
      }

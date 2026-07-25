package pro.sketchware.core.build.compiler;

import java.io.File;

import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.build.BuildProgressReceiver;
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
            receiver.onProgress("Kotlin is compiling...", 12);
            try {
                // Use enhanced compiler with incremental compilation support
                KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
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

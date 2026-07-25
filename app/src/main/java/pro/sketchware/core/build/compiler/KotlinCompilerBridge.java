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
public class KotlinCompilerBridge {\n    private static final String TAG = \"KotlinCompilerBridge\";\n\n    /**\n     * Compile Kotlin code if any .kt files are present in the project.\n     * Uses enhanced incremental compiler for faster builds.\n     *\n     * @param receiver Build progress callback\n     * @param builder Project builder context\n     * @throws Throwable if compilation fails\n     */\n    public static void compileKotlinCodeIfPossible(BuildProgressReceiver receiver, \n                                                    ProjectBuilder builder) throws Throwable {\n        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {\n            receiver.onProgress(\"Kotlin is compiling...\", 12);\n            try {\n                // Use enhanced compiler with incremental compilation support\n                KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);\n                compiler.compile();\n                \n                // Log cache statistics for debugging\n                LogUtil.d(TAG, compiler.getCacheStats());\n            } catch (Exception e) {\n                LogUtil.e(TAG, \"Kotlin compilation failed\", e);\n                throw e;\n            }\n        }\n    }\n\n    /**\n     * Add Kotlin standard library dependency if Kotlin files are present.\n     *\n     * @param builder Project builder context\n     * @param builtInLibraryManager Library manager for adding dependencies\n     */\n    public static void maybeAddKotlinBuiltInLibraryDependenciesIfPossible(\n            ProjectBuilder builder, \n            BuiltInLibraryManager builtInLibraryManager) {\n        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {\n            builtInLibraryManager.addLibrary(BuiltInLibraries.JETBRAINS_KOTLIN_STDLIB);\n        }\n    }\n\n    /**\n     * Add compiled Kotlin classes to the classpath for subsequent compilation steps.\n     *\n     * @param classpath String builder accumulating classpath entries\n     * @param workspace Project file paths\n     */\n    public static void maybeAddKotlinFilesToClasspath(StringBuilder classpath, \n                                                      ProjectFilePaths workspace) {\n        if (FileUtil.isExistFile(workspace.compiledClassesPath)) {\n            classpath.append(workspace.compiledClassesPath);\n            classpath.append(\":\");\n        }\n    }\n\n    /**\n     * Get the Kotlin home directory for compiler runtime.\n     *\n     * @param workspace Project file paths\n     * @return Path to kotlin_home directory in bin\n     */\n    public static String getKotlinHome(ProjectFilePaths workspace) {\n        return workspace.binDirectoryPath + File.separator + \"kotlin_home\";\n    }\n\n    /**\n     * Clear Kotlin compilation cache and force full rebuild on next build.\n     * Useful for troubleshooting or when cache becomes corrupted.\n     *\n     * @param builder Project builder context\n     */\n    public static void clearKotlinCompilationCache(ProjectBuilder builder) {\n        try {\n            KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);\n            compiler.clearCache();\n            LogUtil.d(TAG, \"Kotlin compilation cache cleared\");\n        } catch (Exception e) {\n            LogUtil.w(TAG, \"Error clearing Kotlin compilation cache\", e);\n        }\n    }\n}\n
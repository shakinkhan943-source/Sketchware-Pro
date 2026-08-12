package pro.sketchware.core.build.compiler

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import pro.sketchware.core.build.BuildSettings
import pro.sketchware.core.build.ProjectBuilder
import pro.sketchware.core.build.compiler.KotlinCompilerUtil.*
import pro.sketchware.util.LogUtil
import java.io.File

/**
 * Android-safe incremental Kotlin compiler for Sketchware.
 *
 * This keeps the same compiler invocation model as the original KotlinCompiler:
 * one K2JVMCompiler invocation for a compilation batch, using the complete
 * project classpath and the Android-safe noJdk/noStdlib/noReflect settings.
 *
 * Incremental behavior is deliberately conservative:
 * - If nothing changed and compiled output exists, Kotlin compilation is skipped.
 * - If sources changed, only the changed source files are passed to kotlinc.
 * - If compiled output is missing, all Kotlin sources are compiled.
 * - Cache hashes are updated only after successful compilation.
 */
class KotlinCompilerEnhanced(
    private val builder: ProjectBuilder
) {

    private val workspace = builder.projectFilePaths

    private val incrementalCache: IncrementalKotlinCompilationCache by lazy {
        val cacheDir = File(workspace.binDirectoryPath, "kotlin_build_cache")
        cacheDir.mkdirs()
        IncrementalKotlinCompilationCache(cacheDir)
    }

    companion object {
        private const val TAG = "KotlinCompilerEnhanced"
    }

    @Throws(Throwable::class)
    fun compile() {
        val startTime = System.currentTimeMillis()

        val allKtFiles = getFilesToCompile(workspace)
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }

        if (allKtFiles.isEmpty()) {
            LogUtil.d(TAG, "No Kotlin source files found, skipping kotlinc")
            return
        }

        val outputDir = File(workspace.compiledClassesPath).apply { mkdirs() }
        val hasCompiledOutput = hasCompiledKotlinOutput(outputDir)
        val changedFiles = incrementalCache.getChangedFiles(allKtFiles)

        // Fast path: nothing changed and previous compiler output is present.
        if (changedFiles.isEmpty() && hasCompiledOutput) {
            LogUtil.d(
                TAG,
                "Skipping Kotlin compilation - no source changes (${elapsed(startTime)}ms)"
            )
            return
        }

        // If output disappeared, hashes cannot be trusted because bytecode must
        // be recreated even when the source files themselves are unchanged.
        val filesToCompile = if (!hasCompiledOutput) {
            LogUtil.d(TAG, "Compiled Kotlin output missing; performing full rebuild")
            allKtFiles
        } else {
            changedFiles
        }

        if (filesToCompile.isEmpty()) {
            LogUtil.d(TAG, "Nothing requires Kotlin compilation")
            return
        }

        LogUtil.d(
            TAG,
            "Compiling ${filesToCompile.size}/${allKtFiles.size} Kotlin files " +
                "(incremental=${filesToCompile.size != allKtFiles.size})"
        )

        compileKotlin(filesToCompile, outputDir)

        // Only update the cache after kotlinc completed successfully.
        filesToCompile.forEach { incrementalCache.updateFileHash(it) }
        incrementalCache.saveCacheManifest()

        LogUtil.d(
            TAG,
            "Kotlin compilation completed in ${elapsed(startTime)}ms"
        )
    }

    private fun hasCompiledKotlinOutput(outputDir: File): Boolean {
        if (!outputDir.exists()) return false

        return outputDir.walkTopDown().any {
            it.isFile && it.extension.equals("class", ignoreCase = true)
        }
    }

    @Throws(Throwable::class)
    private fun compileKotlin(
        filesToCompile: List<File>,
        outputDir: File
    ) {
        val kotlinHome = File(
            KotlinCompilerBridge.getKotlinHome(workspace)
        ).apply { mkdirs() }

        val compiler = K2JVMCompiler()
        val collector = DiagnosticCollector()
        val plugins = getCompilerPlugins(workspace)
            .filter { it.exists() }
            .map(File::getAbsolutePath)
            .toTypedArray()

        /*
         * These settings intentionally match the original working
         * Sketchware KotlinCompiler. Android does not provide a desktop JDK
         * or Swing classes such as javax.swing.Icon.
         */
        val args = K2JVMCompilerArguments().apply {
            compileJava = false
            includeRuntime = false
            noJdk = true
            noReflect = true
            noStdlib = true

            this.kotlinHome = kotlinHome.absolutePath
            destination = outputDir.absolutePath
            pluginClasspaths = plugins
        }

        // IMPORTANT: pass classpath and source files through the compiler's
        // argument parser, exactly like the original KotlinCompiler. Do not
        // assign sourceRoots/classpath properties because their types differ
        // between Kotlin compiler versions used by Sketchware.
        val argumentList = mutableListOf<String>().apply {
            add("-cp")
            add(builder.getClasspath())
            addAll(filesToCompile.map { it.absolutePath })
        }

        LogUtil.d(
            TAG,
            "Running kotlinc with ${filesToCompile.size} source files"
        )

        compiler.parseArguments(argumentList.toTypedArray(), args)
        compiler.exec(collector, Services.EMPTY, args)

        LogUtil.d(TAG, "kotlinc MessageCollector: $collector")

        if (collector.hasErrors()) {
            LogUtil.e(TAG, "Failed to compile Kotlin files")
            throw RuntimeException(
                "Kotlin compilation failed:\n" +
                    collector.getDiagnostics(areWarningsEnabled())
            )
        }

        // Match the original Sketchware compiler behavior.
        File(outputDir, "META-INF").deleteRecursively()
    }

    @Throws(Throwable::class)
    fun clearCache() {
        incrementalCache.clearCache()
        LogUtil.d(TAG, "Kotlin incremental cache cleared")
    }

    fun getCacheStats(): String = incrementalCache.getCacheStats()

    private fun areWarningsEnabled(): Boolean {
        return builder.buildSettings.getValue(
            BuildSettings.SETTING_NO_WARNINGS,
            BuildSettings.SETTING_GENERIC_VALUE_TRUE
        ) != BuildSettings.SETTING_GENERIC_VALUE_TRUE
    }

    private fun elapsed(startTime: Long): Long {
        return System.currentTimeMillis() - startTime
    }
}

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
 * Important design rules:
 * - Uses ONE K2JVMCompiler invocation per build.
 * - Never runs multiple Kotlin compiler instances in parallel.
 * - Keeps the same Android/JDK-safe compiler flags as the original compiler.
 * - Uses the incremental cache only for deciding which source files changed.
 * - Uses the complete project classpath from ProjectBuilder.
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

    @Throws(Throwable::class)
    fun compile() {
        val start = System.currentTimeMillis()

        val allKtFiles = getFilesToCompile(workspace)
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }

        if (allKtFiles.isEmpty()) {
            LogUtil.d(TAG, "No Kotlin source files found, skipping kotlinc")
            return
        }

        val outputDir = File(workspace.compiledClassesPath)
        outputDir.mkdirs()

        /*
         * If the output directory has disappeared/been emptied, the source
         * manifest alone is not enough to safely skip compilation.
         */
        val hasCompiledOutput = outputDir.walkTopDown().any {
            it.isFile && it.extension.equals("class", ignoreCase = true)
        }

        val changedFiles = incrementalCache.getChangedFiles(allKtFiles)

        if (changedFiles.isEmpty() && hasCompiledOutput) {
            LogUtil.d(
                TAG,
                "Skipping Kotlin compilation - no source changes (${elapsed(start)}ms)"
            )
            return
        }

        /*
         * If output is missing, compile all sources. This is necessary because
         * unchanged Kotlin files may need their bytecode recreated.
         */
        val filesToCompile = if (!hasCompiledOutput) {
            LogUtil.d(TAG, "Compiled output missing; performing full Kotlin rebuild")
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

        /*
         * Only mark files as compiled after the compiler succeeds.
         * Never update the manifest after a failed compilation.
         */
        filesToCompile.forEach { incrementalCache.updateFileHash(it) }
        incrementalCache.saveCacheManifest()

        LogUtil.d(TAG, "Kotlin compilation completed in ${elapsed(start)}ms")
    }

    @Throws(Throwable::class)
    private fun compileKotlin(filesToCompile: List<File>, outputDir: File) {
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
         * Keep these flags aligned with the original Sketchware Android
         * compiler. In particular, noJdk=true prevents the compiler from
         * assuming a desktop JDK/Swing environment.
         */
        val args = K2JVMCompilerArguments().apply {
            compileJava = false

            classpath = builder.getClasspath()
            sourceRoots = filesToCompile.map { it.absolutePath }.toTypedArray()

            kotlinHome = kotlinHome.absolutePath
            destination = outputDir.absolutePath

            includeRuntime = false
            noJdk = true
            noReflect = true
            noStdlib = true

            pluginClasspaths = plugins
        }

        val argumentList = mutableListOf<String>()

        argumentList.add("-cp")
        argumentList.add(builder.getClasspath())
        argumentList.addAll(filesToCompile.map { it.absolutePath })

        LogUtil.d(
            TAG,
            "Running Android-safe kotlinc for ${filesToCompile.size} source files"
        )

        compiler.parseArguments(argumentList.toTypedArray(), args)
        compiler.exec(collector, Services.EMPTY, args)

        LogUtil.d(TAG, "kotlinc diagnostics: $collector")

        if (collector.hasErrors()) {
            LogUtil.e(TAG, "Kotlin compilation failed")
            throw RuntimeException(
                "Kotlin compilation failed:\n${collector.getDiagnostics(areWarningsEnabled())}"
            )
        }

        /*
         * Kotlin generates META-INF/*.kotlin_module files. The original
         * Sketchware compiler removes META-INF because the downstream D8
         * pipeline does not expect these files.
         */
        File(outputDir, "META-INF").deleteRecursively()
    }

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

    private fun elapsed(start: Long): Long =
        System.currentTimeMillis() - start

    companion object {
        private const val TAG = "KotlinCompilerEnhanced"
    }
}

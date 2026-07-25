package pro.sketchware.core.build.compiler

import pro.sketchware.util.LogUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Manages incremental Kotlin compilation by tracking source file changes
 * and caching compiled bytecode. Supports parallel compilation.
 *
 * Key features:
 * - CRC32-based file hashing for fast change detection
 * - Persistent cache across builds
 * - Compiled .class file reuse for unchanged sources
 * - Parallel compilation of multiple files
 *
 * Usage:
 * ```kotlin
 * val cache = IncrementalKotlinCompilationCache(cacheDir)
 * val changedFiles = cache.getChangedFiles(allKtFiles)
 * // Compile only changed files, reuse .class from cache for others
 * ```
 */
class IncrementalKotlinCompilationCache(
    private val cacheDir: File,
    private val parallelThreadCount: Int = Runtime.getRuntime().availableProcessors()
) {
    companion object {
        private const val TAG = "IncrementalKotlinCache"
        private const val CACHE_MANIFEST = "kotlin_build_hashes.json"
        private const val CLASS_CACHE_DIR = "kotlin_classes_cache"
    }

    private val sourceHashes = ConcurrentHashMap<String, String>()
    private val compiledClassCache = ConcurrentHashMap<String, File>()
    private val cacheManifestFile = File(cacheDir, CACHE_MANIFEST)
    private val classCacheDir = File(cacheDir, CLASS_CACHE_DIR)

    init {
        classCacheDir.mkdirs()
        loadCacheManifest()
    }

    /**
     * Calculate CRC32 hash of a file for change detection
     * Fast and reliable for detecting file modifications
     */
    private fun calculateFileHash(file: File): String {
        return try {
            val crc = CRC32()
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    crc.update(buffer, 0, bytesRead)
                }
            }
            // Use absolute value to ensure positive hash
            abs(crc.value).toString(16)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error calculating file hash for ${file.absolutePath}", e)
            "0" // Return dummy hash on error, treat as changed
        }
    }

    /**
     * Check if a source file has changed since last compilation
     */
    private fun hasSourceChanged(file: File): Boolean {
        if (!file.exists()) return false
        
        val currentHash = calculateFileHash(file)
        val previousHash = sourceHashes[file.absolutePath]
        val changed = currentHash != previousHash
        
        if (changed) {
            LogUtil.d(TAG, "File changed: ${file.name} (hash: $previousHash → $currentHash)")
        }
        
        return changed
    }

    /**
     * Filter files that have changed since last build
     * @return Only .kt files that were modified or are new
     */
    fun getChangedFiles(allKtFiles: List<File>): List<File> {
        return allKtFiles.filter { hasSourceChanged(it) }
    }

    /**
     * Get reusable compiled .class files from cache for unchanged sources
     * @return Map of source file path to cached .class file
     */
    fun getCachedCompiledClasses(allKtFiles: List<File>): Map<String, File> {
        val cachedClasses = mutableMapOf<String, File>()
        
        allKtFiles.forEach { file ->
            if (!hasSourceChanged(file)) {
                // Source hasn't changed, try to get cached .class
                val cachedClassFile = getCachedClassFile(file)
                if (cachedClassFile.exists()) {
                    cachedClasses[file.absolutePath] = cachedClassFile
                    LogUtil.d(TAG, "Reusing cached class: ${file.name}")
                }
            }
        }
        
        return cachedClasses
    }

    /**
     * Get the cache file path for a compiled .class file
     */
    private fun getCachedClassFile(sourceFile: File): File {
        val classFileName = sourceFile.nameWithoutExtension + ".class"
        return File(classCacheDir, classFileName)
    }

    /**
     * Cache a compiled .class file after successful compilation
     */
    fun cacheCompiledClass(sourceFile: File, classFile: File) {
        try {
            if (!classFile.exists()) return
            
            val cachedFile = getCachedClassFile(sourceFile)
            classFile.copyTo(cachedFile, overwrite = true)
            compiledClassCache[sourceFile.absolutePath] = cachedFile
            
            // Update source hash after successful compilation
            sourceHashes[sourceFile.absolutePath] = calculateFileHash(sourceFile)
            LogUtil.d(TAG, "Cached compiled class: ${sourceFile.name}")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error caching compiled class", e)
        }
    }

    /**
     * Update file hash after compilation (marks file as compiled)
     */
    fun updateFileHash(file: File) {
        sourceHashes[file.absolutePath] = calculateFileHash(file)
    }

    /**
     * Prepare files for parallel compilation
     * Groups files into chunks for efficient parallel processing
     */
    fun prepareParallelBatches(files: List<File>): List<List<File>> {
        if (files.isEmpty()) return emptyList()
        
        val batchSize = (files.size + parallelThreadCount - 1) / parallelThreadCount
        return files.chunked(batchSize)
    }

    /**
     * Get recommended parallel thread count
     */
    fun getParallelThreadCount(): Int = parallelThreadCount

    /**
     * Clear the entire cache (full rebuild)
     */
    fun clearCache() {
        try {
            sourceHashes.clear()
            compiledClassCache.clear()
            classCacheDir.deleteRecursively()
            classCacheDir.mkdirs()
            cacheManifestFile.delete()
            LogUtil.d(TAG, "Cache cleared - full rebuild will be performed")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Save cache manifest for persistence across builds
     */
    fun saveCacheManifest() {
        try {
            val manifest = mutableMapOf<String, String>()
            sourceHashes.forEach { (path, hash) ->
                manifest[path] = hash
            }
            
            val json = com.google.gson.Gson().toJson(manifest)
            cacheManifestFile.writeText(json)
            LogUtil.d(TAG, "Cache manifest saved (${sourceHashes.size} entries)")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error saving cache manifest", e)
        }
    }

    /**
     * Load cache manifest from previous builds
     */
    private fun loadCacheManifest() {
        try {
            if (!cacheManifestFile.exists()) return
            
            val json = cacheManifestFile.readText()
            val manifest = com.google.gson.Gson()
                .fromJson(json, Map::class.java) as? Map<String, String> ?: return
            
            sourceHashes.putAll(manifest)
            LogUtil.d(TAG, "Cache manifest loaded (${sourceHashes.size} entries)")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error loading cache manifest", e)
            sourceHashes.clear() // Start fresh on error
        }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        val cachedCount = compiledClassCache.size
        val totalTracked = sourceHashes.size
        val cacheSize = classCacheDir.walk().map { it.length() }.sum() / 1024 // KB
        
        return """Cache Stats:
            |  Total tracked files: $totalTracked
            |  Cached classes: $cachedCount
            |  Cache directory size: ${cacheSize}KB
            |  Parallel threads: $parallelThreadCount
        """.trimMargin()
    }
}

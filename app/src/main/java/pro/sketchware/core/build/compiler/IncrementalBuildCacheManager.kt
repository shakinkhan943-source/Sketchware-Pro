package pro.sketchware.core.build.compiler

import pro.sketchware.util.LogUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified manager for incremental build caches.
 */
class IncrementalBuildCacheManager(
    private val projectBinDir: File
) {
    companion object {
        private const val TAG = "IncrementalBuildCacheManager"
        private const val CACHE_ROOT = "build_cache"
        private const val JAVA_CACHE_DIR = "java_build_cache"
        private const val KOTLIN_CACHE_DIR = "kotlin_build_cache"
        private const val RESOURCE_CACHE_DIR = "resource_build_cache"
    }

    private val cacheRoot = File(projectBinDir, CACHE_ROOT)
    private val javaCacheDir = File(cacheRoot, JAVA_CACHE_DIR)
    private val kotlinCacheDir = File(cacheRoot, KOTLIN_CACHE_DIR)
    private val resourceCacheDir = File(cacheRoot, RESOURCE_CACHE_DIR)

    private val kotlinCache: IncrementalKotlinCompilationCache?
    private val cacheStats = ConcurrentHashMap<String, Map<String, String>>()

    init {
        cacheRoot.mkdirs()
        javaCacheDir.mkdirs()
        kotlinCacheDir.mkdirs()
        resourceCacheDir.mkdirs()

        kotlinCache = try {
            IncrementalKotlinCompilationCache(kotlinCacheDir).also {
                LogUtil.d(TAG, "Incremental build cache initialized")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error initializing Kotlin cache", e)
            null
        }
    }

    fun getKotlinCache(): IncrementalKotlinCompilationCache? = kotlinCache

    fun getJavaCacheDir(): File = javaCacheDir

    fun getResourceCacheDir(): File = resourceCacheDir

    fun getCacheStatistics(): String {
        val javaSize = directorySize(javaCacheDir)
        val resourceSize = directorySize(resourceCacheDir)
        val kotlinManifestSize = File(
            kotlinCacheDir,
            "kotlin_build_hashes.json"
        ).let { if (it.exists()) it.length() else 0L }

        return buildString {
            appendLine("INCREMENTAL BUILD CACHE STATISTICS")
            appendLine("Kotlin tracked files: ${kotlinCache?.getCacheStats() ?: "unavailable"}")
            appendLine("Java cache: ${javaSize / 1024} KB")
            appendLine("Resource cache: ${resourceSize / 1024} KB")
            appendLine("Kotlin manifest: ${kotlinManifestSize / 1024} KB")
            appendLine("Total cache: ${getTotalCacheSize() / 1024} KB")
        }
    }

    fun invalidateAllCaches() {
        try {
            kotlinCache?.clearCache()
            recreateDirectory(javaCacheDir)
            recreateDirectory(resourceCacheDir)
            LogUtil.d(TAG, "All build caches invalidated")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating all caches", e)
        }
    }

    fun invalidateKotlinCache() {
        try {
            kotlinCache?.clearCache()
            LogUtil.d(TAG, "Kotlin cache invalidated")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating Kotlin cache", e)
        }
    }

    fun invalidateJavaCache() {
        recreateDirectory(javaCacheDir)
    }

    fun invalidateResourceCache() {
        recreateDirectory(resourceCacheDir)
    }

    fun getTotalCacheSize(): Long = directorySize(cacheRoot)

    fun validateCaches(): Boolean {
        return cacheRoot.exists() &&
            cacheRoot.isDirectory &&
            (kotlinCache != null)
    }

    fun cleanupCache() {
        try {
            val thirtyDaysAgo =
                System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L

            cacheRoot.walkTopDown().forEach { file ->
                if (file.isFile && file.lastModified() < thirtyDaysAgo) {
                    file.delete()
                }
            }

            LogUtil.d(TAG, "Cache cleanup completed")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error during cache cleanup", e)
        }
    }

    fun saveAllCaches() {
        try {
            kotlinCache?.saveCacheManifest()
            LogUtil.d(TAG, "All cache manifests saved")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error saving caches", e)
        }
    }

    private fun recreateDirectory(directory: File) {
        try {
            directory.deleteRecursively()
            directory.mkdirs()
        } catch (e: Exception) {
            LogUtil.e(TAG, "Unable to recreate ${directory.absolutePath}", e)
        }
    }

    private fun directorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        return try {
            directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
    }
}

package pro.sketchware.core.build.compiler

import pro.sketchware.util.LogUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified manager for all incremental build caches (Java, Kotlin, Resources).
 * Provides centralized control for cache statistics, invalidation, and cleanup.
 *
 * This class coordinates between different compilation caches to ensure
 * consistency and provide a single point of control for cache management.
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
        
        try {
            kotlinCache = IncrementalKotlinCompilationCache(kotlinCacheDir)
            LogUtil.d(TAG, "Incremental build cache initialized")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error initializing Kotlin cache", e)
            throw e
        }
    }

    /**
     * Get the Kotlin compilation cache
     */
    fun getKotlinCache(): IncrementalKotlinCompilationCache? = kotlinCache

    /**
     * Get the Java cache directory
     */
    fun getJavaCacheDir(): File = javaCacheDir

    /**
     * Get the resource cache directory
     */
    fun getResourceCacheDir(): File = resourceCacheDir

    /**
     * Get comprehensive cache statistics
     */
    fun getCacheStatistics(): String {
        val stats = StringBuilder()
        stats.append("\n╔════════════════════════════════════════════════════╗\n")
        stats.append("║         INCREMENTAL BUILD CACHE STATISTICS         ║\n")
        stats.append("╠════════════════════════════════════════════════════╣\n")
        
        // Kotlin cache stats
        try {
            stats.append("║ KOTLIN COMPILATION CACHE:                          ║\n")
            val kotlinStats = kotlinCache?.getCacheStats()
            if (kotlinStats != null) {
                for (line in kotlinStats.lines()) {
                    stats.append("║ $line\n")
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error getting Kotlin cache stats", e)
        }
        
        stats.append("║                                                    ║\n")
        
        // Java cache stats
        stats.append("║ JAVA COMPILATION CACHE:                            ║\n")
        val javaSize = javaCacheDir.walk().map { it.length() }.sum() / 1024 // KB
        val javaFiles = javaCacheDir.walk().filter { it.isFile }.count()
        stats.append("║   Files cached: $javaFiles                          ║\n")
        stats.append("║   Directory size: ${javaSize}KB                         ║\n")
        
        stats.append("║                                                    ║\n")
        
        // Resource cache stats
        stats.append("║ RESOURCE COMPILATION CACHE:                        ║\n")
        val resourceSize = resourceCacheDir.walk().map { it.length() }.sum() / 1024 // KB
        val resourceFiles = resourceCacheDir.walk().filter { it.isFile }.count()
        stats.append("║   Files cached: $resourceFiles                          ║\n")
        stats.append("║   Directory size: ${resourceSize}KB                         ║\n")
        
        stats.append("║                                                    ║\n")
        
        // Total stats
        val totalSize = (javaSize + kotlinCache?.getCacheStats()?.let { 0 } ?: 0 + resourceSize) / 1024
        stats.append("║ TOTAL CACHE SIZE: ${totalSize}MB                          ║\n")
        stats.append("╚════════════════════════════════════════════════════╝\n")
        
        return stats.toString()
    }

    /**
     * Invalidate all caches (force full rebuild)
     */
    fun invalidateAllCaches() {
        try {
            LogUtil.d(TAG, "Invalidating all build caches...")
            
            // Clear Kotlin cache
            try {
                kotlinCache?.clearCache()
            } catch (e: Exception) {
                LogUtil.w(TAG, "Error clearing Kotlin cache", e)
            }
            
            // Clear Java cache
            try {
                javaCacheDir.deleteRecursively()
                javaCacheDir.mkdirs()
                LogUtil.d(TAG, "Java cache cleared")
            } catch (e: Exception) {
                LogUtil.w(TAG, "Error clearing Java cache", e)
            }
            
            // Clear resource cache
            try {
                resourceCacheDir.deleteRecursively()
                resourceCacheDir.mkdirs()
                LogUtil.d(TAG, "Resource cache cleared")
            } catch (e: Exception) {
                LogUtil.w(TAG, "Error clearing resource cache", e)
            }
            
            LogUtil.d(TAG, "All build caches invalidated - full rebuild will be performed")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating caches", e)
        }
    }

    /**
     * Invalidate only Kotlin cache (for Kotlin-specific changes)
     */
    fun invalidateKotlinCache() {
        try {
            kotlinCache?.clearCache()
            LogUtil.d(TAG, "Kotlin cache invalidated")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating Kotlin cache", e)
        }
    }

    /**
     * Invalidate only Java cache (for Java-specific changes)
     */
    fun invalidateJavaCache() {
        try {
            javaCacheDir.deleteRecursively()
            javaCacheDir.mkdirs()
            LogUtil.d(TAG, "Java cache invalidated")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating Java cache", e)
        }
    }

    /**
     * Invalidate only resource cache (for resource changes)
     */
    fun invalidateResourceCache() {
        try {
            resourceCacheDir.deleteRecursively()
            resourceCacheDir.mkdirs()
            LogUtil.d(TAG, "Resource cache invalidated")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error invalidating resource cache", e)
        }
    }

    /**
     * Get total cache size in bytes
     */
    fun getTotalCacheSize(): Long {
        var total = 0L
        try {
            total += cacheRoot.walk().map { it.length() }.sum()
        } catch (e: Exception) {
            LogUtil.w(TAG, "Error calculating cache size", e)
        }
        return total
    }

    /**
     * Check if any cache is corrupted and needs clearing
     */
    fun validateCaches(): Boolean {
        var isValid = true
        
        try {
            // Validate Kotlin cache
            if (kotlinCache == null) {
                LogUtil.w(TAG, "Kotlin cache is null")
                isValid = false
            }
            
            // Validate directory structure
            if (!cacheRoot.exists() || !cacheRoot.isDirectory) {
                LogUtil.w(TAG, "Cache root directory is invalid")
                isValid = false
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error validating caches", e)
            isValid = false
        }
        
        return isValid
    }

    /**
     * Clean up unnecessary cache files (optional cleanup)
     */
    fun cleanupCache() {
        try {
            LogUtil.d(TAG, "Starting cache cleanup...")
            
            // Remove old/stale cache entries (older than 30 days)
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000)
            
            cacheRoot.walk().forEach { file ->
                if (file.isFile && file.lastModified() < thirtyDaysAgo) {
                    try {
                        file.delete()
                        LogUtil.d(TAG, "Removed stale cache file: ${file.name}")
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "Error removing cache file", e)
                    }
                }
            }
            
            LogUtil.d(TAG, "Cache cleanup completed")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error during cache cleanup", e)
        }
    }

    /**
     * Save all cache manifests for persistence
     */
    fun saveAllCaches() {
        try {
            LogUtil.d(TAG, "Saving all cache manifests...")
            
            try {
                kotlinCache?.saveCacheManifest()
            } catch (e: Exception) {
                LogUtil.w(TAG, "Error saving Kotlin cache manifest", e)
            }
            
            LogUtil.d(TAG, "All caches saved")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error saving caches", e)
        }
    }
}

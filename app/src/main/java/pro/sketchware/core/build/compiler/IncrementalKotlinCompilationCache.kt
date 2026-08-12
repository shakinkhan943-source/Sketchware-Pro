package pro.sketchware.core.build.compiler

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import pro.sketchware.util.LogUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Persistent source-change tracker for incremental Kotlin compilation.
 *
 * This class deliberately does NOT pretend that one .kt file always produces
 * one .class file. Kotlin can generate multiple class files and module
 * metadata from one source file, so bytecode ownership is handled by the
 * compiler output directory instead.
 */
class IncrementalKotlinCompilationCache(
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "IncrementalKotlinCache"
        private const val CACHE_MANIFEST = "kotlin_build_hashes.json"
        private const val CACHE_VERSION = 2
    }

    private val sourceHashes = ConcurrentHashMap<String, String>()
    private val cacheManifestFile = File(cacheDir, CACHE_MANIFEST)

    init {
        cacheDir.mkdirs()
        loadCacheManifest()
    }

    private fun calculateFileHash(file: File): String {
        return try {
            val crc = CRC32()
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    crc.update(buffer, 0, read)
                }
            }
            crc.value.toString(16)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Unable to hash ${file.absolutePath}", e)
            "ERROR"
        }
    }

    private fun hasSourceChanged(file: File): Boolean {
        if (!file.exists()) return true

        val currentHash = calculateFileHash(file)
        val previousHash = sourceHashes[file.absolutePath]

        return currentHash != previousHash
    }

    fun getChangedFiles(allKtFiles: List<File>): List<File> {
        val currentPaths = allKtFiles
            .map { it.absolutePath }
            .toSet()

        /*
         * A deleted source can leave stale .class files in the output
         * directory. We cannot safely perform a partial build in that case.
         * The caller should clear the cache/full rebuild when it detects this.
         */
        val deletedSourceExists = sourceHashes.keys.any { it !in currentPaths }

        if (deletedSourceExists) {
            LogUtil.w(
                TAG,
                "Deleted Kotlin source detected; forcing full rebuild"
            )
            return allKtFiles
        }

        return allKtFiles.filter { hasSourceChanged(it) }
    }

    fun updateFileHash(file: File) {
        if (file.exists()) {
            sourceHashes[file.absolutePath] = calculateFileHash(file)
        }
    }

    fun clearCache() {
        try {
            sourceHashes.clear()
            cacheManifestFile.delete()
            LogUtil.d(TAG, "Kotlin incremental cache cleared")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error clearing Kotlin cache", e)
        }
    }

    fun saveCacheManifest() {
        try {
            cacheDir.mkdirs()

            val data = Manifest(
                version = CACHE_VERSION,
                hashes = sourceHashes.toMap()
            )

            cacheManifestFile.writeText(
                Gson().toJson(data)
            )

            LogUtil.d(
                TAG,
                "Cache manifest saved (${sourceHashes.size} files)"
            )
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error saving Kotlin cache manifest", e)
        }
    }

    private fun loadCacheManifest() {
        try {
            if (!cacheManifestFile.exists()) return

            val json = cacheManifestFile.readText()
            val type = object : TypeToken<Manifest>() {}.type
            val manifest = Gson().fromJson<Manifest>(json, type)

            if (manifest == null || manifest.version != CACHE_VERSION) {
                LogUtil.d(TAG, "Old Kotlin cache format; starting fresh")
                sourceHashes.clear()
                return
            }

            sourceHashes.putAll(manifest.hashes)

            LogUtil.d(
                TAG,
                "Cache manifest loaded (${sourceHashes.size} files)"
            )
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error loading Kotlin cache manifest", e)
            sourceHashes.clear()
        }
    }

    fun getCacheStats(): String {
        return buildString {
            appendLine("Cache Stats:")
            appendLine("  Tracked Kotlin files: ${sourceHashes.size}")
            appendLine("  Manifest size: ${if (cacheManifestFile.exists()) cacheManifestFile.length() else 0} bytes")
        }
    }

    private data class Manifest(
        val version: Int,
        val hashes: Map<String, String>
    )
}

package pro.sketchware.util.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import pro.sketchware.SketchApplication;
import pro.sketchware.util.FileUtil;

/**
 * Owns the user-selected Jetpack Compose dependency package.
 *
 * The ZIP and JSON are copied into app cache immediately, so future builds do
 * not depend on the original picker URI/path. The package is keyed by a hash
 * of both files. Changing either input therefore invalidates the old index and
 * extraction automatically.
 */
public final class ComposeDependencyManager {
    private static final String TAG = "ComposeDependencyManager";
    private static final String PREFS = "compose_dependency_package";
    private static final String PREF_HASH = "package_hash";
    private static final String PREF_ZIP = "zip_name";
    private static final String PREF_JSON = "json_name";

    private static final File ROOT = new File(SketchApplication.getAppContext().getCacheDir(), "compose-dependencies");
    private static final File SOURCE_ROOT = new File(ROOT, "source");
    private static final File EXTRACT_ROOT = new File(ROOT, "extracted");
    private static final File INDEX_ROOT = new File(ROOT, "index");

    private static ComposeBuiltInLibraries.ComposeManifest cachedManifest;
    private static String cachedHash;

    private ComposeDependencyManager() {}

    public static synchronized boolean isConfigured() {
        return !TextUtils.isEmpty(getPrefs().getString(PREF_HASH, null));
    }

    public static synchronized String getConfiguredPackageName() {
        String hash = getPrefs().getString(PREF_HASH, null);
        return hash == null ? "" : hash;
    }

    public static synchronized void configure(File zipFile, File jsonFile) throws IOException {
        if (zipFile == null || !zipFile.isFile() || !zipFile.canRead()) {
            throw new IOException("Selected Compose ZIP is missing or unreadable");
        }
        if (jsonFile == null || !jsonFile.isFile() || !jsonFile.canRead()) {
            throw new IOException("Selected Compose JSON is missing or unreadable");
        }
        if (!zipFile.getName().toLowerCase().endsWith(".zip")) {
            throw new IOException("Compose dependency package must be a ZIP file");
        }
        if (!jsonFile.getName().toLowerCase().endsWith(".json")) {
            throw new IOException("Compose dependency manifest must be a JSON file");
        }

        ROOT.mkdirs();
        SOURCE_ROOT.mkdirs();
        INDEX_ROOT.mkdirs();

        String hash = sha256(zipFile) + sha256(jsonFile);
        File targetZip = new File(SOURCE_ROOT, "compose-package.zip");
        File targetJson = new File(SOURCE_ROOT, "compose-package.json");
        copyFile(zipFile, targetZip);
        copyFile(jsonFile, targetJson);

        validateJson(targetJson);
        validateZip(targetZip);

        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(PREF_HASH, hash);
        editor.putString(PREF_ZIP, zipFile.getName());
        editor.putString(PREF_JSON, jsonFile.getName());
        editor.apply();

        cachedHash = null;
        cachedManifest = null;
        FileUtil.deleteFile(new File(EXTRACT_ROOT, hash).getAbsolutePath());
        FileUtil.deleteFile(new File(INDEX_ROOT, hash + ".json").getAbsolutePath());
    }

    public static synchronized void clear() {
        getPrefs().edit().clear().apply();
        cachedHash = null;
        cachedManifest = null;
        FileUtil.deleteFile(ROOT.getAbsolutePath());
    }

    public static synchronized ComposeBuiltInLibraries.ComposeManifest getManifest() {
        ensureReady();
        return cachedManifest;
    }

    public static synchronized void ensureReady() {
        if (!isConfigured()) {
            throw new IllegalStateException("No Jetpack Compose dependency package is configured. Open Settings → Jetpack Compose Dependencies.");
        }
        File zip = new File(SOURCE_ROOT, "compose-package.zip");
        File json = new File(SOURCE_ROOT, "compose-package.json");
        if (!zip.isFile() || !json.isFile()) {
            throw new IllegalStateException("Configured Jetpack Compose dependency package is incomplete. Re-select the ZIP and JSON files.");
        }
        String hash = getConfiguredPackageName();
        File extraction = new File(EXTRACT_ROOT, hash);
        if (!extraction.isDirectory()) {
            try {
                validateJson(json);
                validateZip(zip);
                extractSafely(zip, extraction);
                buildIndex(json, extraction, hash);
            } catch (IOException | RuntimeException e) {
                FileUtil.deleteFile(extraction.getAbsolutePath());
                throw new IllegalStateException("Failed to prepare Jetpack Compose dependency package: " + e.getMessage(), e);
            }
        }
        if (!hash.equals(cachedHash) || cachedManifest == null) {
            cachedManifest = readManifest(json, extraction, hash);
            cachedHash = hash;
        }
        validateManifestFiles(cachedManifest);
    }

    public static synchronized List<ComposeBuiltInLibraries.ComposeArtifact> getSelectedArtifacts(List<String> optionalFeatureIds) {
        ComposeBuiltInLibraries.ComposeManifest manifest = getManifest();
        if (manifest.artifacts == null || manifest.artifacts.isEmpty()) return Collections.emptyList();

        Set<String> selectedFeatures = new LinkedHashSet<>();
        if (optionalFeatureIds != null) selectedFeatures.addAll(optionalFeatureIds);

        Set<String> ids = new LinkedHashSet<>();
        if (manifest.features == null || manifest.features.isEmpty()) {
            for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
                if (artifact != null && !TextUtils.isEmpty(artifact.id)) ids.add(artifact.id);
            }
        } else {
            for (ComposeBuiltInLibraries.ComposeFeature feature : manifest.features) {
                if (feature != null && (feature.required || selectedFeatures.contains(feature.id)) && feature.artifacts != null) {
                    ids.addAll(feature.artifacts);
                }
            }
        }

        List<ComposeBuiltInLibraries.ComposeArtifact> result = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && ids.contains(artifact.id)) result.add(artifact);
        }
        return result;
    }

    public static synchronized File resolveFile(String artifactId, String kind) {
        ensureReady();
        ComposeBuiltInLibraries.ComposeArtifact artifact = findArtifact(artifactId);
        if (artifact == null) return new File("");
        String path = artifact.paths.get(kind);
        if (TextUtils.isEmpty(path)) return new File("");
        File file = safeResolvedPath(path);
        return file == null ? new File("") : file;
    }

    public static synchronized File resolveClassesJar(String artifactId) { return resolveFile(artifactId, "classesJar"); }
    public static synchronized File resolveResources(String artifactId) { return resolveFile(artifactId, "resources"); }
    public static synchronized File resolveAssets(String artifactId) { return resolveFile(artifactId, "assets"); }
    public static synchronized File resolveProguard(String artifactId) { return resolveFile(artifactId, "proguard"); }
    public static synchronized File resolveDex(String artifactId) { return resolveFile(artifactId, "dex"); }

    private static ComposeBuiltInLibraries.ComposeArtifact findArtifact(String id) {
        ComposeBuiltInLibraries.ComposeManifest manifest = cachedManifest;
        if (manifest == null || manifest.artifacts == null) return null;
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && id.equals(artifact.id)) return artifact;
        }
        return null;
    }

    private static SharedPreferences getPrefs() {
        return SketchApplication.getAppContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void validateManifestFiles(ComposeBuiltInLibraries.ComposeManifest manifest) {
        if (manifest == null || manifest.artifacts == null) {
            throw new IllegalStateException("Compose JSON is valid JSON but does not contain a usable dependency manifest");
        }
        List<String> missing = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact == null || TextUtils.isEmpty(artifact.id)) {
                throw new IllegalStateException("Compose JSON contains an artifact without a stable id");
            }
            String classes = artifact.paths.get("classesJar");
            String dex = artifact.paths.get("dex");
            if (TextUtils.isEmpty(classes) && TextUtils.isEmpty(dex)) {
                missing.add(artifact.id);
            }
            for (String path : artifact.paths.values()) {
                if (path != null && !path.isEmpty() && safeResolvedPath(path) == null) {
                    throw new IllegalStateException("Compose artifact " + artifact.id + " contains an unsafe path: " + path);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Compose dependency package is incomplete; artifacts without classes.jar or dex: " + missing);
        }
    }

    private static ComposeBuiltInLibraries.ComposeManifest readManifest(File json, File extraction, String hash) throws IOException {
        String text = readText(json);
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) throw new IOException("Compose JSON root must be an object");

        ComposeBuiltInLibraries.ComposeManifest manifest = new ComposeBuiltInLibraries.ComposeManifest();
        JsonObject object = root.getAsJsonObject();
        if (object.has("schemaVersion")) manifest.schemaVersion = object.get("schemaVersion").getAsInt();
        if (object.has("composeVersion")) manifest.composeVersion = object.get("composeVersion").getAsString();

        JsonArray features = object.has("features") && object.get("features").isJsonArray() ? object.getAsJsonArray("features") : null;
        if (features != null) {
            for (JsonElement element : features) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                ComposeBuiltInLibraries.ComposeFeature feature = new ComposeBuiltInLibraries.ComposeFeature();
                feature.id = stringValue(value, "id", "name");
                feature.name = stringValue(value, "name", "id");
                feature.description = stringValue(value, "description", "");
                feature.required = booleanValue(value, "required", false);
                feature.tag = stringValue(value, "tag", "");
                feature.artifacts = stringList(value.get("artifacts"));
                manifest.features.add(feature);
            }
        }

        JsonArray artifacts = object.has("artifacts") && object.get("artifacts").isJsonArray() ? object.getAsJsonArray("artifacts") : null;
        if (artifacts != null) {
            for (JsonElement element : artifacts) {
                if (!element.isJsonObject()) continue;
                manifest.artifacts.add(parseArtifact(element.getAsJsonObject(), extraction));
            }
        }

        // Also accept a package/file-oriented JSON without an artifacts array.
        if (manifest.artifacts.isEmpty()) {
            LinkedHashSet<String> discovered = new LinkedHashSet<>();
            collectFilePaths(root, discovered);
            for (String path : discovered) {
                String lower = path.toLowerCase();
                if (!(lower.endsWith(".jar") || lower.endsWith(".aar") || lower.endsWith(".dex"))) continue;
                ComposeBuiltInLibraries.ComposeArtifact artifact = new ComposeBuiltInLibraries.ComposeArtifact();
                artifact.id = stableId(path);
                artifact.coordinate = artifact.id;
                artifact.paths.put(pathType(path), path);
                manifest.artifacts.add(artifact);
            }
        }
        return manifest;
    }

    private static ComposeBuiltInLibraries.ComposeArtifact parseArtifact(JsonObject value, File extraction) {
        ComposeBuiltInLibraries.ComposeArtifact artifact = new ComposeBuiltInLibraries.ComposeArtifact();
        artifact.id = stringValue(value, "id", null);
        if (TextUtils.isEmpty(artifact.id)) artifact.id = stringValue(value, "name", null);
        if (TextUtils.isEmpty(artifact.id)) artifact.id = stringValue(value, "coordinate", null);
        String root = stringValue(value, "path", null);
        if (TextUtils.isEmpty(root)) root = stringValue(value, "root", null);
        if (TextUtils.isEmpty(root)) root = stringValue(value, "directory", null);
        if (TextUtils.isEmpty(artifact.id) && !TextUtils.isEmpty(root)) artifact.id = stableId(root);
        if (TextUtils.isEmpty(artifact.id)) throw new IllegalStateException("Compose JSON contains an artifact without id/name/coordinate/path");

        artifact.coordinate = stringValue(value, "coordinate", artifact.id);
        artifact.packageName = stringValue(value, "packageName", stringValue(value, "package", ""));
        artifact.dependencies.addAll(stringList(value.get("dependencies")));

        JsonObject files = value.has("files") && value.get("files").isJsonObject() ? value.getAsJsonObject("files") : null;
        if (files != null) {
            putPath(artifact, "classesJar", files, "classesJar", "classes", "jar");
            putPath(artifact, "resources", files, "resources", "res", "resource");
            putPath(artifact, "assets", files, "assets", "asset");
            putPath(artifact, "proguard", files, "proguard", "proguardFile", "rules");
            putPath(artifact, "dex", files, "dex", "dexFile");
        }
        putPath(artifact, "classesJar", value, "classesJar", "classesJarPath", "jar");
        putPath(artifact, "resources", value, "resources", "res", "resourcesPath");
        putPath(artifact, "assets", value, "assets", "assetsPath");
        putPath(artifact, "proguard", value, "proguard", "proguardFile", "proguardPath");
        putPath(artifact, "dex", value, "dex", "dexFile", "dexPath");

        if (!TextUtils.isEmpty(root)) {
            if (!artifact.paths.containsKey("classesJar")) artifact.paths.put("classesJar", join(root, "classes.jar"));
            if (!artifact.paths.containsKey("resources")) artifact.paths.put("resources", join(root, "res"));
            if (!artifact.paths.containsKey("assets")) artifact.paths.put("assets", join(root, "assets"));
            if (!artifact.paths.containsKey("proguard")) artifact.paths.put("proguard", join(root, "proguard.txt"));
            if (!artifact.paths.containsKey("dex")) artifact.paths.put("dex", join("dex", artifact.id + ".dex"));
        } else {
            // Backwards-compatible package layout used by the old Compose pipeline.
            String rootPath = "libraries/" + artifact.id;
            if (!artifact.paths.containsKey("classesJar")) artifact.paths.put("classesJar", rootPath + "/classes.jar");
            if (!artifact.paths.containsKey("resources")) artifact.paths.put("resources", rootPath + "/res");
            if (!artifact.paths.containsKey("assets")) artifact.paths.put("assets", rootPath + "/assets");
            if (!artifact.paths.containsKey("proguard")) artifact.paths.put("proguard", rootPath + "/proguard.txt");
            if (!artifact.paths.containsKey("dex")) artifact.paths.put("dex", "dex/" + artifact.id + ".dex");
        }
        return artifact;
    }

    private static void putPath(ComposeBuiltInLibraries.ComposeArtifact artifact, String type, JsonObject source, String... keys) {
        for (String key : keys) {
            if (source.has(key) && source.get(key).isJsonPrimitive() && source.get(key).getAsJsonPrimitive().isString()) {
                String path = source.get(key).getAsString();
                if (!TextUtils.isEmpty(path)) {
                    artifact.paths.put(type, normalize(path));
                    return;
                }
            }
        }
    }

    private static void collectFilePaths(JsonElement element, Set<String> result) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = normalize(element.getAsString());
            String lower = value.toLowerCase();
            if (lower.endsWith(".jar") || lower.endsWith(".aar") || lower.endsWith(".dex") || lower.endsWith(".zip")) result.add(value);
            return;
        }
        if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) collectFilePaths(child, result);
        if (element.isJsonObject()) for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) collectFilePaths(entry.getValue(), result);
    }

    private static void buildIndex(File json, File extraction, String hash) throws IOException {
        // The JSON itself is the authoritative index; keep a small marker so a
        // partially extracted package is never mistaken for a complete cache.
        File marker = new File(INDEX_ROOT, hash + ".json");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(readText(json).getBytes(StandardCharsets.UTF_8));
        }
        if (!extraction.isDirectory()) throw new IOException("Extraction directory was not created");
    }

    private static File safeResolvedPath(String path) {
        if (TextUtils.isEmpty(path)) return null;
        String normalized = normalize(path);
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) return null;
        File root = new File(EXTRACT_ROOT, getConfiguredPackageName());
        File file = new File(root, normalized);
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            return filePath.startsWith(rootPath) || filePath.equals(root.getCanonicalPath()) ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void extractSafely(File zip, File destination) throws IOException {
        if (destination.exists()) FileUtil.deleteFile(destination.getAbsolutePath());
        if (!destination.mkdirs()) throw new IOException("Unable to create extraction directory");
        byte[] buffer = new byte[8192];
        Set<String> written = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name.isEmpty() || name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                File target = new File(destination, name);
                String canonicalRoot = destination.getCanonicalPath() + File.separator;
                if (!target.getCanonicalPath().startsWith(canonicalRoot)) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                if (!written.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create directory: " + name);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create parent for: " + name);
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
            }
        }
    }

    private static void validateZip(File zip) throws IOException {
        boolean hasFile = false;
        Set<String> names = new HashSet<>();
        try (ZipFile file = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalize(entry.getName());
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                if (!names.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
                if (!entry.isDirectory()) hasFile = true;
            }
        }
        if (!hasFile) throw new IOException("Compose ZIP is empty");
    }

    private static void validateJson(File json) throws IOException {
        String text = readText(json);
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) throw new IOException("Compose JSON root must be an object");
        } catch (RuntimeException e) {
            throw new IOException("Compose JSON is invalid: " + e.getMessage(), e);
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IOException("Failed to hash Compose package file", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString()) return object.get(key).getAsString();
        return fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; } catch (RuntimeException e) { return fallback; }
    }

    private static List<String> stringList(JsonElement element) {
        List<String> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement item : element.getAsJsonArray()) if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) result.add(item.getAsString());
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceFirst("^\\./", "");
    }

    private static String join(String parent, String child) {
        return normalize(parent + "/" + child);
    }

    private static String stableId(String path) {
        String normalized = normalize(path);
        String name = new File(normalized).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String pathType(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".dex") ? "dex" : "classesJar";
    }
}

package pro.sketchware.util.library;

import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable facade used by the existing Compose build pipeline.
 *
 * File locations are no longer hard-coded to APK assets. ComposeDependencyManager
 * owns package selection, extraction, JSON detection and caching while this class
 * preserves the existing id-based API used by ProjectBuilder and ResourceCompiler.
 */
public final class ComposeBuiltInLibraries {
    public static final String ZIP_ASSET = "";
    public static final String MANIFEST_ASSET = "";

    private ComposeBuiltInLibraries() {}

    public static void ensureExtracted() {
        ComposeDependencyManager.ensureReady();
    }

    public static ComposeManifest getManifest() {
        return ComposeDependencyManager.getManifest();
    }

    public static List<ComposeFeature> getFeatures() {
        return getManifest().features;
    }

    public static boolean isBundleAvailable() {
        return ComposeDependencyManager.isConfigured();
    }

    public static List<ComposeArtifact> getSelectedArtifacts(List<String> optionalFeatureIds) {
        return ComposeDependencyManager.getSelectedArtifacts(optionalFeatureIds);
    }

    public static File getLibraryClassesJarPath(String artifactId) {
        return ComposeDependencyManager.resolveClassesJar(artifactId);
    }

    public static File getLibraryResourcesPath(String artifactId) {
        return ComposeDependencyManager.resolveResources(artifactId);
    }

    public static File getLibraryAssetsPath(String artifactId) {
        return ComposeDependencyManager.resolveAssets(artifactId);
    }

    public static File getLibraryProguardConfiguration(String artifactId) {
        return ComposeDependencyManager.resolveProguard(artifactId);
    }

    public static File getLibraryDexFile(String artifactId) {
        return ComposeDependencyManager.resolveDex(artifactId);
    }

    public static final class ComposeManifest {
        public int schemaVersion;
        public String composeVersion;
        public List<ComposeFeature> features = new ArrayList<>();
        public List<ComposeArtifact> artifacts = new ArrayList<>();
    }

    public static final class ComposeFeature {
        public String id;
        public String name;
        public String description;
        public boolean required;
        public String tag;
        public List<String> artifacts = new ArrayList<>();
    }

    public static final class ComposeArtifact {
        public String id;
        public String coordinate;
        public String packageName;
        public List<String> dependencies = new ArrayList<>();
        /** Detected package paths keyed by stable role, never by generated filename. */
        public Map<String, String> paths = new LinkedHashMap<>();

        public boolean hasClassesJar() {
            return !TextUtils.isEmpty(paths.get("classesJar"));
        }
    }
}

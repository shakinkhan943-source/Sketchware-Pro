package pro.sketchware.util.library;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import pro.sketchware.beans.ProjectLibraryBean;

/** Project-scoped selection state for the user-configured Compose dependency package. */
public final class ComposeBuiltInLibraryManager {
    public static final String OPTIONAL_FEATURES_KEY = "compose_optional_features";

    private final ProjectLibraryBean compose;

    public ComposeBuiltInLibraryManager(ProjectLibraryBean compose) {
        this.compose = compose;
    }

    public boolean isEnabled() {
        return compose != null && compose.isEnabled();
    }

    public List<String> getOptionalFeatureIds() {
        List<String> result = new ArrayList<>();
        if (compose == null || compose.configurations == null) return result;
        Object value = compose.configurations.get(OPTIONAL_FEATURES_KEY);
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (item instanceof String && !((String) item).isEmpty()) result.add((String) item);
            }
        }
        return result;
    }

    public void setOptionalFeatureIds(List<String> featureIds) {
        if (compose == null) return;
        if (compose.configurations == null) compose.configurations = new java.util.HashMap<>();
        compose.configurations.put(OPTIONAL_FEATURES_KEY,
                featureIds == null ? new ArrayList<>() : new ArrayList<>(featureIds));
    }

    /** Stable artifact lookup. Callers must use the JSON artifact id, never a filename. */
    public ComposeBuiltInLibraries.ComposeArtifact getArtifactById(String artifactId) {
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedArtifacts()) {
            if (artifact != null && artifactId != null && artifactId.equals(artifact.id)) return artifact;
        }
        return null;
    }

    public File getClassesJarById(String artifactId) {
        return ComposeBuiltInLibraries.getLibraryClassesJarPath(requireArtifactId(artifactId));
    }

    public File getResourcesById(String artifactId) {
        return ComposeBuiltInLibraries.getLibraryResourcesPath(requireArtifactId(artifactId));
    }

    public File getAssetsById(String artifactId) {
        return ComposeBuiltInLibraries.getLibraryAssetsPath(requireArtifactId(artifactId));
    }

    public File getProguardById(String artifactId) {
        return ComposeBuiltInLibraries.getLibraryProguardConfiguration(requireArtifactId(artifactId));
    }

    public File getDexById(String artifactId) {
        return ComposeBuiltInLibraries.getLibraryDexFile(requireArtifactId(artifactId));
    }

    public List<ComposeBuiltInLibraries.ComposeArtifact> getSelectedArtifacts() {
        return ComposeBuiltInLibraries.getSelectedArtifacts(getOptionalFeatureIds());
    }

    private String requireArtifactId(String artifactId) {
        if (artifactId == null || artifactId.isEmpty()) throw new IllegalArgumentException("Compose artifact id is empty");
        if (getArtifactById(artifactId) == null) throw new IllegalArgumentException("Compose artifact id is not present in the selected package: " + artifactId);
        return artifactId;
    }
}

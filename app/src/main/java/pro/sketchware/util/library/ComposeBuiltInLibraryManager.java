package pro.sketchware.util.library;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.beans.ProjectLibraryBean;

/** Project-scoped selection state for the separate built-in Compose bundle. */
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
        if (compose == null || compose.configurations == null) {
            return result;
        }
        Object value = compose.configurations.get(OPTIONAL_FEATURES_KEY);
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    result.add((String) item);
                }
            }
        }
        return result;
    }

    public void setOptionalFeatureIds(List<String> featureIds) {
        if (compose == null) return;
        if (compose.configurations == null) {
            compose.configurations = new java.util.HashMap<>();
        }
        compose.configurations.put(OPTIONAL_FEATURES_KEY,
                featureIds == null ? new ArrayList<>() : new ArrayList<>(featureIds));
    }

    public List<ComposeBuiltInLibraries.ComposeArtifact> getSelectedArtifacts() {
        return ComposeBuiltInLibraries.getSelectedArtifacts(getOptionalFeatureIds());
    }
}

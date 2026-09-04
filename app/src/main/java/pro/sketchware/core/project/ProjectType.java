package pro.sketchware.core.project;

import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.beans.ProjectFileBean;

import java.util.List;
import java.util.Map;

/**
 * Project UI/build mode for Sketchware Pro generated apps.
 *
 * <p>Every project carries exactly one mode in its metadata ({@code project_type}). The mode is the
 * first decision made before dependency resolution: it tells the builder whether the project is
 * meant to be a traditional Java + XML (with optional Kotlin source files) app or a Kotlin +
 * Jetpack Compose app.</p>
 *
 * <p>Keeping the mode in one place prevents call sites from re-inferring it from individual
 * features (e.g. "a Kotlin file exists, therefore this is a Compose project"). A Kotlin file may
 * exist in either mode; only {@link #COMPOSE} enables Compose dependencies and the Compose source
 * pipeline.</p>
 */
public final class ProjectType {

    /** Traditional Android project: Java + XML resources, optional Kotlin source files. */
    public static final String JAVA_XML = "java_xml";

    /** Compose-first Android project: Kotlin + Jetpack Compose, no XML UI system. */
    public static final String COMPOSE = "compose";

    /** Backward-compatible default for projects created before project-type metadata existed. */
    public static final String DEFAULT = JAVA_XML;

    /** Metadata key used to persist the project type. */
    public static final String METADATA_KEY = "project_type";

    private ProjectType() {
    }

    public static boolean isCompose(String projectType) {
        return COMPOSE.equals(projectType);
    }

    public static boolean isJavaXml(String projectType) {
        return !isCompose(projectType);
    }

    /**
     * Reads the project type from metadata, falling back to the compatible default for legacy
     * projects. A legacy project that already uses Compose (because its Compose library is enabled
     * or it contains a Kotlin/Compose Activity) is still resolved as {@link #COMPOSE}, so existing
     * Compose projects are not broken by the new metadata.
     */
    public static String fromMetadata(Map<String, Object> metadata) {
        if (metadata != null) {
            Object value = metadata.get(METADATA_KEY);
            if (value instanceof String valueString && COMPOSE.equals(valueString)) {
                return COMPOSE;
            }
            if (value instanceof String valueString && JAVA_XML.equals(valueString)) {
                return JAVA_XML;
            }
        }

        if (metadata != null && isLegacyComposeProject(metadata)) {
            return COMPOSE;
        }
        return DEFAULT;
    }

    /**
     * Resolves the type at file level, using the project metadata if available and otherwise
     * falling back to the feature-based legacy detection.
     */
    public static String fromProjectFileManager(Map<String, Object> metadata,
                                                List<ProjectFileBean> activities) {
        Object stored = metadata != null ? metadata.get(METADATA_KEY) : null;
        if (stored instanceof String valueString
                && (COMPOSE.equals(valueString) || JAVA_XML.equals(valueString))) {
            return valueString;
        }
        if (metadata != null && isLegacyComposeProject(metadata)) {
            return COMPOSE;
        }
        if (activities != null) {
            for (ProjectFileBean activity : activities) {
                if (activity != null && activity.isComposeActivity()) {
                    return COMPOSE;
                }
            }
        }
        return DEFAULT;
    }

    /** Legacy heuristic: an old project that explicitly enabled Compose is treated as Compose. */
    private static boolean isLegacyComposeProject(Map<String, Object> metadata) {
        Object projType = metadata.get("proj_type");
        if (projType instanceof Number number && number.intValue() == 1) {
            return false;
        }

        // Keep the public metadata key and the library flag in sync: if either already says Compose,
        // treat the project as a Compose project.
        Object stored = metadata.get(METADATA_KEY);
        if (stored instanceof String valueString && COMPOSE.equals(valueString)) {
            return true;
        }
        return false;
    }

    /**
     * Resolves from stored metadata and the active built-in library configuration. Used by build
     * pipeline callers that have already loaded library beans.
     */
    public static String resolve(Map<String, Object> metadata, ProjectLibraryBean composeLibrary,
                                 boolean hasComposeActivity) {
        Object stored = metadata != null ? metadata.get(METADATA_KEY) : null;
        if (stored instanceof String valueString && !valueString.trim().isEmpty()) {
            return normalize(valueString);
        }
        if (composeLibrary != null && ProjectLibraryBean.LIB_USE_Y.equals(composeLibrary.useYn)) {
            return COMPOSE;
        }
        if (hasComposeActivity) {
            return COMPOSE;
        }
        return DEFAULT;
    }

    public static String normalize(String projectType) {
        return COMPOSE.equals(projectType) ? COMPOSE : JAVA_XML;
    }
}

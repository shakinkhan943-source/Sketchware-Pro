package pro.sketchware.activities.editor.manage.library.compose;

/**
 * Represents a single downloadable Jetpack Compose dependency.
 */
public class ComposeDependency {

    public final String name;
    public final String coordinate;

    public ComposeDependency(String name, String coordinate) {
        this.name = name;
        this.coordinate = coordinate;
    }

    /**
     * The local library folder name the dependency system uses for a Maven
     * coordinate of {@code group:artifact:version}, e.g.
     * {@code androidx.compose.ui:ui:1.7.8} -> {@code ui-v1.7.8}.
     */
    public String getLibraryName() {
        String[] parts = coordinate.split(":");
        if (parts.length != 3) {
            return "";
        }
        return parts[1] + "-v" + parts[2];
    }
}

package pro.sketchware.activities.editor.manage.library.compose;

import java.util.Arrays;
import java.util.List;

/**
 * The curated list of downloadable Jetpack Compose dependencies shown when the
 * Compose toggle is enabled. Each entry is a Maven coordinate that can be
 * resolved by the existing Sketchware dependency download system.
 */
public final class ComposeDependencies {

    private ComposeDependencies() {
    }

    public static List<ComposeDependency> getDefaults() {
        return Arrays.asList(
                new ComposeDependency("Compose UI", "androidx.compose.ui:ui:1.7.8"),
                new ComposeDependency("Compose Foundation", "androidx.compose.foundation:foundation:1.7.8"),
                new ComposeDependency("Compose Runtime", "androidx.compose.runtime:runtime:1.7.8"),
                new ComposeDependency("Compose Animation", "androidx.compose.animation:animation:1.7.8"),
                new ComposeDependency("Compose Material", "androidx.compose.material:material:1.7.8"),
                new ComposeDependency("Compose Material 3", "androidx.compose.material3:material3:1.3.1"),
                new ComposeDependency("Compose Material Icons Extended", "androidx.compose.material:material-icons-extended:1.7.8"),
                new ComposeDependency("Compose UI Tooling Preview", "androidx.compose.ui:ui-tooling-preview:1.7.8"),
                new ComposeDependency("Activity Compose", "androidx.activity:activity-compose:1.9.3"),
                new ComposeDependency("Lifecycle ViewModel Compose", "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7"),
                new ComposeDependency("Navigation Compose", "androidx.navigation:navigation-compose:2.8.5")
        );
    }
}

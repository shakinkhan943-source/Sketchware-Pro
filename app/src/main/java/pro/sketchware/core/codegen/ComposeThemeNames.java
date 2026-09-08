package pro.sketchware.core.codegen;

/**
 * Shared naming rules for the generated Compose theme files of a project.
 *
 * <p>Compose projects own their Material 3 theme in Kotlin source ({@code Color.kt} and
 * {@code Theme.kt}). The identifiers in those files are derived from the project name so every
 * project gets its own theme namespace instead of a shared "Sketchware" one, and the generated
 * activities reference exactly the names the theme files declare — both sides go through this
 * class so they can never drift apart.</p>
 */
public final class ComposeThemeNames {

    /** Fallback identifier for projects whose name has no usable characters. */
    public static final String FALLBACK_IDENTIFIER = "App";

    private ComposeThemeNames() {
    }

    /**
     * Turns a project name into a PascalCase Kotlin identifier, e.g.
     * {@code "my notes app"} → {@code MyNotesApp}, {@code "3D Game!"} → {@code _3DGame}.
     */
    public static String themeIdentifier(String projectName) {
        String source = projectName == null ? "" : projectName.trim();
        StringBuilder identifier = new StringBuilder(source.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (identifier.length() == 0 && Character.isDigit(c)) {
                    // Kotlin identifiers may not start with a digit.
                    identifier.append('_');
                }
                identifier.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else {
                capitalizeNext = true;
            }
        }
        if (identifier.length() == 0) {
            return FALLBACK_IDENTIFIER;
        }
        return identifier.toString();
    }

    /** Name of the light {@code ColorScheme} value in the generated Color.kt. */
    public static String lightColorsName(String projectName) {
        return themeIdentifier(projectName) + "LightColors";
    }

    /** Name of the dark {@code ColorScheme} value in the generated Color.kt. */
    public static String darkColorsName(String projectName) {
        return themeIdentifier(projectName) + "DarkColors";
    }

    /** Name of the generated {@code @Composable} theme function in Theme.kt. */
    public static String themeFunctionName(String projectName) {
        return themeIdentifier(projectName) + "Theme";
    }
}

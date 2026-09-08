package pro.sketchware.util.library;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.HashMap;

import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.util.ThemeUtils;

/**
 * Material 3 management for <b>Jetpack Compose</b> projects.
 *
 * <p>Java/XML projects and Compose projects each own a separate Material 3 system, so each gets
 * its own manager: {@link Material3LibraryManager} reads the Java/XML configuration stored on the
 * AppCompat library bean, while this manager reads the Compose Material 3 configuration stored on
 * the Compose library bean. The generated {@code Theme.kt}/{@code Color.kt} only ever consults
 * this Compose configuration — never the Sketchware/Java theme system.</p>
 *
 * <p>Configuration keys (stored in the Compose bean's {@code configurations}):</p>
 * <ul>
 *     <li>{@code material3} — whether the generated theme applies the configured M3 options.</li>
 *     <li>{@code dynamic_colors} — Android 12+ dynamic color sources.</li>
 *     <li>{@code theme} — {@code DayNight} (follow system), {@code Light} or {@code Dark}.</li>
 * </ul>
 */
public class ComposeMaterial3LibraryManager {

    public static final String CONFIG_MATERIAL3 = "material3";
    public static final String CONFIG_DYNAMIC_COLORS = "dynamic_colors";
    public static final String CONFIG_THEME = "theme";

    public static final String THEME_DAY_NIGHT = "DayNight";
    public static final String THEME_LIGHT = "Light";
    public static final String THEME_DARK = "Dark";

    private final ProjectLibraryBean composeLibraryBean;
    private final Context context;

    public ComposeMaterial3LibraryManager(String sc_id) {
        this(ProjectDataManager.getLibraryManager(sc_id).getCompose());
    }

    public ComposeMaterial3LibraryManager(@Nullable ProjectLibraryBean composeLibraryBean) {
        this.composeLibraryBean = composeLibraryBean;
        this.context = pro.sketchware.SketchApplication.getContext();
    }

    public boolean isMaterial3Enabled() {
        // A Compose project's UI is always wrapped in its generated MaterialTheme; the
        // configuration only decides whether the user's M3 options are applied. Unconfigured
        // projects therefore behave as enabled with the defaults below.
        return safeGetBoolean(CONFIG_MATERIAL3, true);
    }

    public boolean isDynamicColorsEnabled() {
        return isMaterial3Enabled() && safeGetBoolean(CONFIG_DYNAMIC_COLORS, false);
    }

    public String getTheme() {
        String theme = safeGetString(CONFIG_THEME, THEME_DAY_NIGHT);
        return theme.isEmpty() ? THEME_DAY_NIGHT : theme;
    }

    /** True when the generated theme should render its dark palette. */
    public boolean canUseNightVariantColors() {
        return !THEME_LIGHT.equals(getTheme())
                && (THEME_DARK.equals(getTheme()) || ThemeUtils.isDarkThemeEnabled(context));
    }

    @Nullable
    public ProjectLibraryBean getComposeLibraryBean() {
        return composeLibraryBean;
    }

    /** Stores one configuration value on the Compose library bean (no-op without a bean). */
    public void setConfiguration(String key, Object value) {
        if (composeLibraryBean == null) {
            return;
        }
        if (composeLibraryBean.configurations == null) {
            composeLibraryBean.configurations = new HashMap<>();
        }
        if (value == null) {
            composeLibraryBean.configurations.remove(key);
        } else {
            composeLibraryBean.configurations.put(key, value);
        }
    }

    private String safeGetString(String key, String fallback) {
        if (composeLibraryBean != null
                && composeLibraryBean.configurations != null
                && composeLibraryBean.configurations.get(key) instanceof String value) {
            return value;
        }
        return fallback;
    }

    private boolean safeGetBoolean(String key, boolean fallback) {
        if (composeLibraryBean != null
                && composeLibraryBean.configurations != null
                && composeLibraryBean.configurations.get(key) instanceof Boolean value) {
            return value;
        }
        return fallback;
    }
}

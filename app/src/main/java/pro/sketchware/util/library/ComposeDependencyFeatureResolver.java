package pro.sketchware.util.library;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pro.sketchware.SketchApplication;

/**
 * Resolves feature roots from the selected Compose JSON without hard-coded artifact filenames.
 *
 * <p>A feature lists the artifacts the user asked for — its <em>roots</em>. Compose splits one
 * library across many artifacts, so the roots alone are never enough: {@code foundation} does not
 * contain {@code androidx.compose.foundation.layout}, {@code ui} does not contain {@code Color}
 * ({@code ui-graphics}), {@code Dp} ({@code ui-unit}) or {@code TextStyle} ({@code ui-text}), and
 * {@code material3} needs {@code material-ripple} and {@code material-icons-core} at runtime.
 * Selecting the roots alone reported imports that "exist" in the bundle yet resolve to nothing.</p>
 *
 * <p>The selection therefore treats the package as what it is: a set the user assembled for one
 * purpose. It contains the <em>union</em> of everything the build needs, so every packed artifact is
 * used, minus whatever an <em>unselected optional feature</em> claims together with everything only
 * it reaches. Declared {@code dependencies} are still followed, because they are the precise answer
 * when a bundle does describe them — but a bundle whose graph is pruned (a generator that lists only
 * the dependencies it had to fetch from elsewhere, which is common) still builds, which the closure
 * alone could not promise.</p>
 */
final class ComposeDependencyFeatureResolver {
    private static final String TAG = "ComposeDependencyResolver";

    private ComposeDependencyFeatureResolver() {}

    static List<ComposeBuiltInLibraries.ComposeArtifact> select(
            ComposeBuiltInLibraries.ComposeManifest manifest,
            List<String> optionalFeatureIds) {
        if (manifest == null || manifest.artifacts == null || manifest.artifacts.isEmpty()) {
            return Collections.emptyList();
        }

        FeatureRoots roots = readFeatureRoots(optionalFeatureIds);
        if (roots.isEmpty()) roots = readFeatureRoots(manifest, optionalFeatureIds);
        if (roots.isEmpty()) {
            // Nothing to prune: a flat (feature-less) package uses everything it carries.
            return usableArtifacts(manifest);
        }

        Map<String, ComposeBuiltInLibraries.ComposeArtifact> byName = indexByNames(manifest);
        Map<String, String> unresolved = new HashMap<>();
        Set<String> requested = expand(byName, roots.selected, unresolved);
        // Turning a feature off has to remove something, otherwise a "large optional artifact"
        // toggle (Material Icons Extended is megabytes of DEX) would be a no-op.
        Set<String> turnedOff = expand(byName, roots.deselected, null);

        Set<String> selected = new LinkedHashSet<>(requested);
        int packed = 0;
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact == null || TextUtils.isEmpty(artifact.id)) continue;
            packed++;
            if (!turnedOff.contains(artifact.id)) selected.add(artifact.id);
        }

        reportUnresolved(unresolved, selected);

        List<ComposeBuiltInLibraries.ComposeArtifact> result = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && selected.contains(artifact.id) && emitted.add(artifact.id)) {
                result.add(artifact);
            }
        }
        Log.d(TAG, "Selected " + result.size() + " of " + packed + " Compose artifacts ("
                + turnedOff.size() + " turned off by unselected features, " + requested.size()
                + " reachable from the features): " + describe(result));
        return result;
    }

    private static List<ComposeBuiltInLibraries.ComposeArtifact> usableArtifacts(
            ComposeBuiltInLibraries.ComposeManifest manifest) {
        List<ComposeBuiltInLibraries.ComposeArtifact> all = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && !TextUtils.isEmpty(artifact.id)) all.add(artifact);
        }
        Log.d(TAG, "Compose package declares no features, using all " + all.size() + " artifacts");
        return all;
    }

    /**
     * Follows {@link ComposeBuiltInLibraries.ComposeArtifact#dependencies} transitively from
     * {@code names} and returns the artifact ids it reached. Names are matched leniently (see
     * {@link #indexByNames}), the visited set makes a cycle harmless, and every name that reaches no
     * artifact is handed to {@code unresolved} together with whatever asked for it.
     */
    private static Set<String> expand(Map<String, ComposeBuiltInLibraries.ComposeArtifact> byName,
                                      Collection<String> names,
                                      Map<String, String> unresolved) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String name : names) {
            if (TextUtils.isEmpty(name)) continue;
            pending.add(name);
            if (unresolved != null) unresolved.put(name, "the selected Compose features");
        }

        while (!pending.isEmpty()) {
            String name = pending.poll();
            ComposeBuiltInLibraries.ComposeArtifact artifact = find(byName, name);
            if (artifact == null) continue;
            if (unresolved != null) unresolved.remove(name);
            if (!reached.add(artifact.id)) continue;
            for (String dependency : artifact.dependencies) {
                if (TextUtils.isEmpty(dependency)) continue;
                pending.add(dependency);
                if (unresolved != null) unresolved.putIfAbsent(dependency, artifact.id);
            }
        }
        return reached;
    }

    /**
     * A dependency nobody can satisfy is a broken build rather than a warning, but only once the whole
     * bundle has been consulted: a name the package simply does not carry is a gap that cannot be
     * filled from anywhere else, while {@code androidx.core}, {@code lifecycle}, {@code collection}
     * and {@code kotlinx.coroutines} are legitimately provided by the app or {@code android.jar}.
     */
    private static void reportUnresolved(Map<String, String> unresolved, Set<String> selected) {
        if (unresolved.isEmpty() || selected.isEmpty()) return;

        List<String> missing = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, String> entry : unresolved.entrySet()) {
            if (isComposeArtifact(entry.getKey())) {
                missing.add(entry.getKey() + " (required by " + entry.getValue() + ")");
            } else {
                ignored.add(entry.getKey());
            }
        }
        if (!ignored.isEmpty()) {
            Log.d(TAG, "Ignoring dependencies the bundle does not declare, they are provided by the"
                    + " app or android.jar: " + ignored);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The Jetpack Compose dependency bundle is incomplete: "
                    + TextUtils.join("; ", missing) + " " + (missing.size() > 1 ? "are" : "is")
                    + " neither declared in the Compose JSON nor packed into the ZIP, and no other"
                    + " part of a Sketchware build provides androidx.compose classes. kotlinc would"
                    + " only report the resulting gaps as unrelated 'Unresolved reference' or 'Cannot"
                    + " access class' errors. Add every required artifact to the JSON and pack its"
                    + " classes.jar plus its dex file into the ZIP, then re-select both in Settings.");
        }
    }

    private static boolean isComposeArtifact(String name) {
        return name != null && name.toLowerCase().contains("compose");
    }

    /**
     * Indexes artifacts by every name they can be referenced with: their id, their Maven coordinate,
     * and a loose form of both where every non-alphanumeric character collapses to a single '_'.
     * A hand-written bundle tends to use {@code androidx.compose.ui:ui-unit} in one place and
     * {@code androidx_compose_ui_ui_unit} as a directory or dex file name in another.
     */
    private static Map<String, ComposeBuiltInLibraries.ComposeArtifact> indexByNames(
            ComposeBuiltInLibraries.ComposeManifest manifest) {
        Map<String, ComposeBuiltInLibraries.ComposeArtifact> index = new LinkedHashMap<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact == null) continue;
            putName(index, artifact.id, artifact);
            putName(index, artifact.coordinate, artifact);
            putName(index, looseName(artifact.id), artifact);
            putName(index, looseName(artifact.coordinate), artifact);
            // Coordinate without its version, e.g. "androidx.compose.ui:ui" for
            // "androidx.compose.ui:ui:1.6.0".
            String coordinate = artifact.coordinate;
            if (coordinate != null) {
                int lastColon = coordinate.lastIndexOf(':');
                if (lastColon > 0 && looksLikeVersion(coordinate.substring(lastColon + 1))) {
                    putName(index, coordinate.substring(0, lastColon), artifact);
                    putName(index, looseName(coordinate.substring(0, lastColon)), artifact);
                }
            }
        }
        return index;
    }

    private static void putName(Map<String, ComposeBuiltInLibraries.ComposeArtifact> index,
                                String name, ComposeBuiltInLibraries.ComposeArtifact artifact) {
        if (!TextUtils.isEmpty(name)) index.putIfAbsent(name, artifact);
    }

    private static ComposeBuiltInLibraries.ComposeArtifact find(
            Map<String, ComposeBuiltInLibraries.ComposeArtifact> index, String name) {
        if (TextUtils.isEmpty(name)) return null;
        ComposeBuiltInLibraries.ComposeArtifact artifact = index.get(name);
        if (artifact != null) return artifact;
        artifact = index.get(looseName(name));
        if (artifact != null) return artifact;

        int lastColon = name.lastIndexOf(':');
        if (lastColon > 0 && looksLikeVersion(name.substring(lastColon + 1))) {
            String withoutVersion = name.substring(0, lastColon);
            artifact = index.get(withoutVersion);
            if (artifact != null) return artifact;
            return index.get(looseName(withoutVersion));
        }
        return null;
    }

    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder result = new StringBuilder(name.length());
        boolean separator = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (separator && result.length() > 0) result.append('_');
                separator = false;
                result.append(Character.toLowerCase(c));
            } else {
                separator = result.length() > 0;
            }
        }
        return result.toString();
    }

    private static boolean looksLikeVersion(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != '-' && c != '_' && c != '+') return false;
        }
        return Character.isDigit(value.charAt(0));
    }

    private static String describe(List<ComposeBuiltInLibraries.ComposeArtifact> artifacts) {
        List<String> ids = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : artifacts) {
            ids.add(artifact.id);
        }
        return TextUtils.join(", ", ids);
    }

    /** Which artifact names the required and chosen optional features ask for, and which they do not. */
    private static final class FeatureRoots {
        final Set<String> selected = new LinkedHashSet<>();
        final Set<String> deselected = new LinkedHashSet<>();

        boolean isEmpty() {
            return selected.isEmpty() && deselected.isEmpty();
        }

        void add(boolean enabled, JsonElement roots) {
            if (!(roots instanceof JsonArray)) return;
            for (JsonElement item : roots.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                String artifactId = item.getAsString();
                if (!artifactId.isEmpty()) (enabled ? selected : deselected).add(artifactId);
            }
        }

        void add(boolean enabled, String artifactId) {
            if (artifactId == null || artifactId.isEmpty()) return;
            (enabled ? selected : deselected).add(artifactId);
        }
    }

    /**
     * Reads the feature roots straight from the selected JSON, because a feature may spell its roots
     * either {@code roots} or {@code artifacts} and the parsed manifest keeps only one of them.
     */
    private static FeatureRoots readFeatureRoots(List<String> optionalFeatureIds) {
        FeatureRoots roots = new FeatureRoots();
        File json = new File(SketchApplication.getAppContext().getCacheDir(),
                "compose-dependencies/source/compose-package.json");
        if (!json.isFile()) return roots;

        try (InputStream input = new FileInputStream(json)) {
            byte[] bytes = new byte[(int) Math.min(json.length(), Integer.MAX_VALUE)];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            JsonElement root = JsonParser.parseString(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return roots;
            JsonElement featureElement = root.getAsJsonObject().get("features");
            if (featureElement == null || !featureElement.isJsonArray()) return roots;

            Set<String> optional = optionalFeatureIds == null
                    ? Collections.emptySet()
                    : new LinkedHashSet<>(optionalFeatureIds);
            for (JsonElement element : featureElement.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject feature = element.getAsJsonObject();
                boolean required = feature.has("required") && feature.get("required").getAsBoolean();
                String id = feature.has("id") ? feature.get("id").getAsString() : "";
                boolean enabled = required || optional.contains(id);

                JsonElement artifacts = feature.get("roots");
                if (artifacts == null) artifacts = feature.get("artifacts");
                roots.add(enabled, artifacts);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Compose feature roots from the selected JSON: " + e.getMessage(), e);
        }
        return roots;
    }

    /** Fallback to the parsed manifest, e.g. when only the cached index of a package survives. */
    private static FeatureRoots readFeatureRoots(
            ComposeBuiltInLibraries.ComposeManifest manifest, List<String> optionalFeatureIds) {
        FeatureRoots roots = new FeatureRoots();
        if (manifest.features == null) return roots;
        Set<String> optional = optionalFeatureIds == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(optionalFeatureIds);
        for (ComposeBuiltInLibraries.ComposeFeature feature : manifest.features) {
            if (feature == null || feature.artifacts == null) continue;
            boolean enabled = feature.required || optional.contains(feature.id);
            for (String artifactId : feature.artifacts) roots.add(enabled, artifactId);
        }
        return roots;
    }
}
